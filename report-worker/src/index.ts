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
 * window.
 *
 * **Do not over-read that.** An earlier version of this comment claimed it let the privacy policy say
 * the only thing collected is the report itself. It does not: Play counts a stable identifier
 * retained off-device as collection regardless of reversibility, so the hash must be DECLARED
 * (Device or other IDs, optional, fraud-prevention purpose) — see docs/store-submission.md gate 1.
 * The hash is still worth having; it just buys a better posture, not an absent one.
 *
 * EXPORTS. The Workers runtime treats every named export of this module as a service entrypoint and
 * requires each to be a function or an `ExportedHandler`. Exporting a plain value here — a size
 * constant, a Set, a config object — makes workerd refuse to start the Worker at all, which no unit
 * test and no `--dry-run` will catch. Values live in `limits.ts`; keep this module's exports to
 * functions and the default handler.
 */

import { MAX_BODY_BYTES, MAX_EXCERPT, MAX_IDENT, MAX_NOTE } from './limits';

export interface Env {
  /** KV namespace holding submitted reports and rate-limit counters. */
  REPORTS: KVNamespace;
  /** Secret salt for rate-limit IP hashing. Rotating it just resets in-flight windows. */
  RATE_LIMIT_SALT: string;
}

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

/**
 * True unless every scheme marker on the request says https.
 *
 * Keyed off the proxy headers the edge sets on every forwarded request (Cloudflare documents that
 * a client-supplied `x-forwarded-proto` is overwritten at the proxy), NOT `url.protocol` — local
 * workerd (vitest, `wrangler dev`, the CI boot check) serves plain http with none of these headers
 * and must not lock itself out. Every marker that is present must say https: if two are present
 * and disagree, someone is lying and the request is refused. A malformed `cf-visitor` counts as
 * plaintext — only the edge writes that header. And when NO scheme marker is present, `cf-ray`
 * decides: its presence means the request came through the edge, where a missing scheme marker is
 * a header-forwarding regression this guard must not silently allow; its absence means no edge in
 * front (local workerd) and no TLS statement to enforce.
 */
export function isPlaintextRequest(request: Request): boolean {
  const proto = request.headers.get('x-forwarded-proto');
  if (proto !== null && proto.toLowerCase() !== 'https') return true;

  const visitor = request.headers.get('cf-visitor');
  if (visitor !== null) {
    try {
      const scheme = (JSON.parse(visitor) as { scheme?: unknown }).scheme;
      if (typeof scheme !== 'string' || scheme.toLowerCase() !== 'https') return true;
    } catch {
      return true;
    }
  }

  if (proto === null && visitor === null) return request.headers.get('cf-ray') !== null;

  return false;
}

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
 * Rate limit: a per-caller counter on a **renewing** TTL, not a fixed window. `put()` re-sets
 * `expirationTtl` on every request this counts, so the window only lapses after an hour with no
 * counted request — a caller must go idle to get their budget back. Requests already over the limit
 * return before `put()` and so do not extend it.
 *
 * That renewal is also why the identifier's retention is not "one hour" on the Data Safety form; see
 * `docs/store-submission.md` gate 1, which got this wrong twice.
 *
 * Not exact under concurrency — two simultaneous requests can both read the same count — but a
 * caller racing themselves to send an 11th report is not the threat this guards against, and an
 * exact limiter would need a Durable Object for no practical gain.
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
    // Transport before routing: a report body can carry a name typed into a note, and the zone's
    // Always Use HTTPS toggle is dashboard state nothing in this repo can pin — so the Worker
    // refuses plaintext itself. A caller speaking http learns nothing about paths or methods and
    // costs no KV work.
    if (isPlaintextRequest(request)) return reply(403, 'https required');

    const url = new URL(request.url);
    if (url.pathname !== '/report') return reply(404, 'not found');
    if (request.method !== 'POST') return reply(405, 'method not allowed');

    const contentType = request.headers.get('content-type') ?? '';
    if (!contentType.includes('application/json')) return reply(415, 'expected application/json');

    // The `[[kv_namespaces]]` block in wrangler.toml ships COMMENTED OUT (see the comment there —
    // an empty binding id breaks every wrangler command, including the one that creates the id).
    // That makes it possible to deploy having skipped README step 1b, in which case this binding is
    // undefined and every request would die on an unhandled TypeError. Say so instead: a
    // misconfigured deploy should be obvious from one curl, not from a stack trace.
    if (env.REPORTS === undefined) return reply(503, 'storage not configured');

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
