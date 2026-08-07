/*
 * Copyright (C) 2026 Entrevoix / grepon.cc — AGPL-3.0-or-later.
 *
 * Tests for the report validator (#258). This is the function that faces hostile input: the
 * endpoint is effectively unauthenticated (Relais is AGPL, so any shipped credential is public), so
 * everything that keeps junk out of KV is here.
 */

import { describe, expect, it } from 'vitest';
import worker, {
  type Env,
  MAX_BODY_BYTES,
  MAX_EXCERPT,
  MAX_IDENT,
  MAX_NOTE,
  parseReport,
  readBoundedBody,
} from './index';

/** Builds a stream that delivers `parts` as separate chunks, like a real chunked request. */
function streamOf(...parts: Uint8Array[]): ReadableStream<Uint8Array> {
  return new ReadableStream<Uint8Array>({
    start(controller) {
      for (const p of parts) controller.enqueue(p);
      controller.close();
    },
  });
}

const utf8 = (s: string): Uint8Array => new TextEncoder().encode(s);

const valid = {
  reasonId: 'harmful',
  surface: 'chat',
  excerpt: 'some model output',
  note: 'why it was wrong',
  modelId: 'litert-community/gemma-4-E2B-it-litert-lm',
  backend: 'GPU_LITERTLM',
};

describe('parseReport', () => {
  it('accepts a well-formed report', () => {
    expect(parseReport(valid)).toEqual(valid);
  });

  it('rejects a reason id outside the allowlist', () => {
    expect(parseReport({ ...valid, reasonId: 'spam' })).toBeNull();
  });

  it('rejects a surface outside the allowlist', () => {
    expect(parseReport({ ...valid, surface: 'somewhere_else' })).toBeNull();
  });

  it('rejects a blank excerpt — there is nothing to report', () => {
    expect(parseReport({ ...valid, excerpt: '' })).toBeNull();
  });

  it('rejects an excerpt past the cap', () => {
    expect(parseReport({ ...valid, excerpt: 'x'.repeat(2001) })).toBeNull();
  });

  it('accepts an excerpt at exactly the cap', () => {
    const excerpt = 'x'.repeat(2000);
    expect(parseReport({ ...valid, excerpt })?.excerpt).toBe(excerpt);
  });

  it('rejects a note past the cap', () => {
    expect(parseReport({ ...valid, note: 'x'.repeat(501) })).toBeNull();
  });

  it('normalizes a missing note to null, matching the client', () => {
    const { note, ...withoutNote } = valid;
    expect(parseReport(withoutNote)?.note).toBeNull();
  });

  it('accepts an explicit null note', () => {
    expect(parseReport({ ...valid, note: null })?.note).toBeNull();
  });

  it('drops unknown fields rather than storing them', () => {
    const parsed = parseReport({ ...valid, smuggled: 'x'.repeat(100), __proto__: {} });
    expect(parsed).not.toBeNull();
    expect(Object.keys(parsed as object).sort()).toEqual(
      ['backend', 'excerpt', 'modelId', 'note', 'reasonId', 'surface'].sort(),
    );
  });

  it('rejects a non-object body', () => {
    expect(parseReport(null)).toBeNull();
    expect(parseReport('a string')).toBeNull();
    expect(parseReport(42)).toBeNull();
    expect(parseReport([])).toBeNull();
  });

  it('rejects wrong-typed fields rather than coercing them', () => {
    expect(parseReport({ ...valid, excerpt: 12345 })).toBeNull();
    expect(parseReport({ ...valid, note: { nested: true } })).toBeNull();
    expect(parseReport({ ...valid, modelId: ['array'] })).toBeNull();
  });

  it('rejects an over-long model id — provenance is bounded too', () => {
    expect(parseReport({ ...valid, modelId: 'm'.repeat(201) })).toBeNull();
  });
});

describe('readBoundedBody', () => {
  it('returns the decoded body when under the cap', async () => {
    expect(await readBoundedBody(streamOf(utf8('hello')), 1024)).toBe('hello');
  });

  it('reassembles a body split across chunks, as a chunked request arrives', async () => {
    const stream = streamOf(utf8('{"a":'), utf8('1'), utf8('}'));
    expect(await readBoundedBody(stream, 1024)).toBe('{"a":1}');
  });

  it('accepts a body at exactly the cap', async () => {
    expect(await readBoundedBody(streamOf(utf8('x'.repeat(64))), 64)).toBe('x'.repeat(64));
  });

  it('rejects a body one byte over the cap', async () => {
    expect(await readBoundedBody(streamOf(utf8('x'.repeat(65))), 64)).toBeNull();
  });

  it('counts BYTES, not UTF-16 units — the multi-byte payload that defeated the old check', async () => {
    // 40 emoji: String.length is 80 UTF-16 units, but 160 bytes in UTF-8. A cap of 100 applied to
    // String.length would let this through; a byte cap must not.
    const emoji = '🚀'.repeat(40);
    expect(emoji.length).toBeLessThan(100);
    expect(utf8(emoji).byteLength).toBeGreaterThan(100);
    expect(await readBoundedBody(streamOf(utf8(emoji)), 100)).toBeNull();
  });

  it('stops reading once the cap is passed rather than draining the whole stream', async () => {
    let pulled = 0;
    const stream = new ReadableStream<Uint8Array>({
      pull(controller) {
        pulled += 1;
        if (pulled > 50) {
          controller.close();
          return;
        }
        controller.enqueue(utf8('x'.repeat(32)));
      },
    });
    expect(await readBoundedBody(stream, 64)).toBeNull();
    // 64-byte cap over 32-byte chunks: overflow is detected on the third chunk, so the reader must
    // not have pulled all 50. This is the difference between cancelling and buffering everything.
    expect(pulled).toBeLessThan(10);
  });

  it('returns null when there is no body at all', async () => {
    expect(await readBoundedBody(null, 1024)).toBeNull();
  });
});

/**
 * The transport cap counts BYTES; the field caps count UTF-16 units. If the transport cap is the
 * smaller of the two, the endpoint 413s reports the schema calls valid — which is what an 8 KiB cap
 * did to any CJK or emoji report, silently breaking the operators this feature exists for.
 */
/**
 * The KV block ships commented out in wrangler.toml, so a deploy that skipped README step 1b has no
 * binding. Without a guard every request would die on an unhandled TypeError; a misconfigured deploy
 * should be diagnosable from one curl.
 */
describe('missing KV binding', () => {
  const jsonPost = () =>
    new Request('https://example.invalid/report', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(valid),
    });

  it('answers 503 rather than throwing when REPORTS is unbound', async () => {
    const res = await worker.fetch(jsonPost(), {} as unknown as Env);
    expect(res.status).toBe(503);
    expect(await res.json()).toEqual({ message: 'storage not configured' });
  });

  it('still rejects a bad method before reaching the storage check', async () => {
    const res = await worker.fetch(
      new Request('https://example.invalid/report'),
      {} as unknown as Env,
    );
    expect(res.status).toBe(405);
  });
});

describe('transport cap vs schema caps', () => {
  it('accepts a maximum-size CJK report — every field at its limit, all multi-byte', () => {
    const maximal = {
      reasonId: 'misinformation',
      surface: 'gallery_chat',
      excerpt: '字'.repeat(MAX_EXCERPT),
      note: '字'.repeat(MAX_NOTE),
      modelId: '字'.repeat(MAX_IDENT),
      backend: '字'.repeat(MAX_IDENT),
    };
    // Schema-valid by construction: every field is exactly at its UTF-16 limit.
    expect(parseReport(maximal)).not.toBeNull();

    const bytes = utf8(JSON.stringify(maximal)).byteLength;
    expect(bytes).toBeGreaterThan(8 * 1024); // the old cap would have refused this
    expect(bytes).toBeLessThan(MAX_BODY_BYTES);
  });

  it('covers the worst case: a serializer that escapes every character as \\uXXXX', () => {
    // 6 bytes per UTF-16 unit is the ceiling for JSON string escaping. If the transport cap ever
    // drops below this, some valid report becomes unsendable depending on the client's serializer.
    const maxUnits = MAX_EXCERPT + MAX_NOTE + MAX_IDENT * 2 + 64 + 64;
    const worstCaseBytes = maxUnits * 6 + 200; // + structural overhead
    expect(worstCaseBytes).toBeLessThan(MAX_BODY_BYTES);
  });
});
