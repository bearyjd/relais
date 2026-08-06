/*
 * Copyright (C) 2026 Entrevoix / grepon.cc — AGPL-3.0-or-later.
 *
 * Tests for the report validator (#258). This is the function that faces hostile input: the
 * endpoint is effectively unauthenticated (Relais is AGPL, so any shipped credential is public), so
 * everything that keeps junk out of KV is here.
 */

import { describe, expect, it } from 'vitest';
import { parseReport } from './index';

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
