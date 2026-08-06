/*
 * Copyright (C) 2026 Entrevoix / grepon.cc — AGPL-3.0-or-later.
 *
 * Receives opt-in AI-content reports from Relais (#258), satisfying the "to developers" half of
 * Play's AI-Generated Content policy. See docs/store-submission.md gate 1.
 *
 * THREAT MODEL — read before changing anything here.
 *
 * Relais is AGPL and its source is public, so **any credential shipped in the APK is public too**.
 * There is therefore no useful client authentication: this endpoint must be safe while effectively
 * open to the internet. Everything below follows from that — hard caps before parsing, a strict
 * allowlist schema, per-IP rate limiting, and fixed response strings that never echo input.
 *
 * PRIVACY. Reports are user-authored content the operator chose to send. We store the report and
 * nothing else: **no raw IP is ever persisted.** The rate limiter needs to distinguish callers, so
 * it keys on a salted SHA-256 of the IP, which is not reversible to an address and expires with the
 * window. This is what lets the privacy policy say the only thing collected is the report itself.
 */

export interface Env {
  /** KV namespace holding submitted reports and rate-limit counters. */
  REPORTS: KVNamespace;
  /** Secret salt for rate-limit IP hashing. Rotating it just resets in-flight windows. */
  RATE_LIMIT_SALT: string;
}

/** Hard cap on the request body, enforced before any parsing. */
const MAX_BODY_BYTES = 8 * 1024;

/**
 * Field caps. These mirror the client's own limits (`ContentReportShaping.kt`) so a well-behaved app
 * can never trip them — they exist to bound a hostile caller, not to validate our own UI.
 */
const MAX_EXCERPT = 2000;
const MAX_NOTE = 500;
const MAX_IDENT = 200;

/** Reason ids the client can send. Anything else is a malformed or forged report. */
const REASONS = new Set(['harmful', 'sexual', 'hate', 'violent', 'misinformation', 'other']);

/** Surfaces the client can attribute a report to. */
const SURFACES = new Set(['chat', 'gallery_chat']);

/** Reports retained for 180 days, then dropped automatically — we have no reason to hold them longer. */
const RETENTION_SECONDS = 180 * 24 * 60 * 60;

/** Per-IP submissions allowed per window. Generous for a human, useless for a flood. */
const RATE_LIMIT = 10;
const RATE_WINDOW_SECONDS = 60 * 60;

interface ReportBody {
  reasonId: string;
  excerpt: string;
  note: string | null;
  modelId: string | null;
  backend: string | null;
  surface: string;
}

/** Fixed responses. Never interpolate request data — an echo turns this into a reflection gadget. */
const reply = (status: number, message: string): Response =>
  new Response(JSON.stringify({ message }), {
    status,
    headers: { 'content-type': 'application/json', 'cache-control': 'no-store' },
  });

function isBoundedString(value: unknown, max: number): value is string {
  return typeof value === 'string' && value.length > 0 && value.length <= max;
}

function isBoundedOrNull(value: unknown, max: number): value is string | null {
  return value === null || value === undefined || isBoundedString(value, max);
}

/**
 * Validates the parsed body against the schema. Returns the normalized report or null.
 *
 * Deliberately allowlist-shaped: unknown fields are dropped rather than stored, so a caller cannot
 * smuggle extra data into KV by adding keys.
 */
export function parseReport(raw: unknown): ReportBody | null {
  if (typeof raw !== 'object' || raw === null) return null;
  const r = raw as Record<string, unknown>;

  if (!isBoundedString(r.reasonId, 64) || !REASONS.has(r.reasonId)) return null;
  if (!isBoundedString(r.surface, 64) || !SURFACES.has(r.surface)) return null;
  if (!isBoundedString(r.excerpt, MAX_EXCERPT)) return null;
  if (!isBoundedOrNull(r.note, MAX_NOTE)) return null;
  if (!isBoundedOrNull(r.modelId, MAX_IDENT)) return null;
  if (!isBoundedOrNull(r.backend, MAX_IDENT)) return null;

  return {
    reasonId: r.reasonId,
    surface: r.surface,
    excerpt: r.excerpt,
    note: (r.note as string | null) ?? null,
    modelId: (r.modelId as string | null) ?? null,
    backend: (r.backend as string | null) ?? null,
  };
}

/**
 * Reads the body, aborting as soon as it exceeds [maxBytes]. Returns null if the cap is exceeded or
 * there is no body.
 *
 * Counts **bytes off the stream**, not `String.length`. Two reasons, both of which were real bugs:
 * `await request.text()` buffers the ENTIRE payload before any check can run — so a chunked request,
 * or one with a lying Content-Length, defeats a cap applied afterwards — and `String.length` counts
 * UTF-16 code units, so a multi-byte UTF-8 payload well over the cap passes a length test.
 *
 * Cancelling the reader on overflow tells the runtime to stop pulling, so an attacker streaming a
 * large body is cut off rather than fully buffered.
 */
export async function readBoundedBody(
  stream: ReadableStream<Uint8Array> | null,
  maxBytes: number,
): Promise<string | null> {
  if (stream === null) return null;

  const reader = stream.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;

  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    if (value === undefined) continue;
    total += value.byteLength;
    if (total > maxBytes) {
      await reader.cancel();
      return null;
    }
    chunks.push(value);
  }

  const merged = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    merged.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return new TextDecoder().decode(merged);
}

/** Salted, non-reversible caller identity for rate limiting. The raw IP is never stored. */
async function callerHash(ip: string, salt: string): Promise<string> {
  const data = new TextEncoder().encode(`${salt}:${ip}`);
  const digest = await crypto.subtle.digest('SHA-256', data);
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

/**
 * Fixed-window rate limit. Not exact under concurrency — two simultaneous requests can both read the
 * same count — but a caller racing themselves to send an 11th report is not the threat this guards
 * against, and an exact limiter would need a Durable Object for no practical gain.
 */
async function overRateLimit(env: Env, ip: string): Promise<boolean> {
  const key = `rl:${await callerHash(ip, env.RATE_LIMIT_SALT)}`;
  const current = parseInt((await env.REPORTS.get(key)) ?? '0', 10);
  if (current >= RATE_LIMIT) return true;
  await env.REPORTS.put(key, String(current + 1), { expirationTtl: RATE_WINDOW_SECONDS });
  return false;
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    if (url.pathname !== '/report') return reply(404, 'not found');
    if (request.method !== 'POST') return reply(405, 'method not allowed');

    const contentType = request.headers.get('content-type') ?? '';
    if (!contentType.includes('application/json')) return reply(415, 'expected application/json');

    // Rate limit BEFORE touching the body. An abusive caller should be turned away without us
    // doing any stream work, and a flood of *malformed* requests has to count against the limit
    // too — checking after parsing would let junk traffic through the limiter for free.
    const ip = request.headers.get('cf-connecting-ip') ?? '';
    if (ip !== '' && (await overRateLimit(env, ip))) return reply(429, 'too many reports');

    // Content-Length is only a cheap early reject — it can lie or be absent under chunked encoding.
    // readBoundedBody is what actually enforces the cap, counting bytes as it reads and cancelling
    // the stream on overflow rather than buffering the whole payload first.
    const declared = parseInt(request.headers.get('content-length') ?? '0', 10);
    if (Number.isFinite(declared) && declared > MAX_BODY_BYTES) return reply(413, 'report too large');

    const body = await readBoundedBody(request.body, MAX_BODY_BYTES);
    if (body === null) return reply(413, 'report too large');

    let parsed: unknown;
    try {
      parsed = JSON.parse(body);
    } catch {
      return reply(400, 'malformed report');
    }

    const report = parseReport(parsed);
    if (report === null) return reply(400, 'malformed report');

    // Key by time + random so concurrent submissions cannot collide, and so listing is
    // chronological. crypto.randomUUID is available in the Workers runtime.
    const receivedAt = new Date().toISOString();
    await env.REPORTS.put(
      `report:${receivedAt}:${crypto.randomUUID()}`,
      JSON.stringify({ ...report, receivedAt }),
      { expirationTtl: RETENTION_SECONDS },
    );

    return reply(202, 'report received');
  },
} satisfies ExportedHandler<Env>;
