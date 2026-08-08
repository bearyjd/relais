/*
 * Copyright (C) 2026 Entrevoix / grepon.cc — AGPL-3.0-or-later.
 *
 * The client and this Worker each hold their own copy of the report vocabulary — a Kotlin enum and
 * a TypeScript Set, in different languages, maintained by hand. `parseReport` rejects anything
 * outside its allowlist with a flat 400 that never echoes input, so a reason added on one side and
 * not the other means every report of that kind is silently refused at the endpoint.
 *
 * Nothing would notice today: the client has no send path yet, so the drift would sit dormant until
 * delivery ships and then look like a server bug. These tests read the Kotlin source directly rather
 * than restating the values, because a hand-copied third list would be one more thing to drift.
 *
 * `.github/workflows/report-worker.yml` triggers on the Kotlin file as well as this directory, so
 * editing either side runs this check.
 */

import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { parseReport } from './index';
import { MAX_EXCERPT, MAX_IDENT, MAX_NOTE } from './limits';

const SHAPING_KT = new URL(
  '../../Android/src/app/src/main/java/cc/grepon/relais/chat/ContentReportShaping.kt',
  import.meta.url,
);
const ENTITIES_KT = new URL(
  '../../Android/src/app/src/main/java/cc/grepon/relais/data/ReportEntities.kt',
  import.meta.url,
);

/** Pulls capture group 1 from every match, dropping any that somehow did not capture. */
function captures(haystack: string, re: RegExp): string[] {
  return [...haystack.matchAll(re)].flatMap((m) => (m[1] === undefined ? [] : [m[1]]));
}

/** `HARMFUL("harmful", "HARMFUL / DANGEROUS"),` -> `harmful` */
function clientReasonIds(): string[] {
  const src = readFileSync(SHAPING_KT, 'utf8');
  const body = src.match(/enum class ReportReason\([^)]*\)\s*\{([\s\S]*?)\n\}/)?.[1];
  if (body === undefined) throw new Error('could not find the ReportReason enum body');
  return captures(body, /^\s*[A-Z_]+\("([a-z_]+)"/gm);
}

/** `const val CHAT = "chat"` inside `object ReportSurface` -> `chat` */
function clientSurfaces(): string[] {
  const src = readFileSync(ENTITIES_KT, 'utf8');
  const body = src.match(/object ReportSurface\s*\{([\s\S]*?)\n\}/)?.[1];
  if (body === undefined) throw new Error('could not find the ReportSurface object body');
  return captures(body, /const val [A-Z_]+ = "([a-z_]+)"/g);
}

/** `const val MAX_REPORT_NOTE_CHARS = 500` -> 500 */
function clientCap(name: string): number {
  const src = readFileSync(SHAPING_KT, 'utf8');
  const value = src.match(new RegExp(`const val ${name} = (\\d+)`))?.[1];
  if (value === undefined) throw new Error(`could not find ${name}`);
  return Number(value);
}

/**
 * The Worker's own allowlists, read from source for the same reason the client's are: asserting
 * against a list restated here would only prove this file agrees with itself.
 *
 * `const REASONS = new Set(['harmful', ...]);` -> the members.
 */
function workerSet(name: 'REASONS' | 'SURFACES'): string[] {
  const src = readFileSync(new URL('./index.ts', import.meta.url), 'utf8');
  const body = src.match(new RegExp(`const ${name} = new Set\\(\\[([^\\]]*)\\]`))?.[1];
  if (body === undefined) throw new Error(`could not find the ${name} set`);
  return captures(body, /'([a-z_]+)'/g);
}

describe('client/worker schema parity', () => {
  // Guards the extractors themselves: a rename that made these return [] would otherwise let the
  // parity assertions below pass vacuously, which is the failure mode this whole file exists to stop.
  it('actually extracts the client vocabulary', () => {
    expect(clientReasonIds().length).toBeGreaterThan(0);
    expect(clientSurfaces().length).toBeGreaterThan(0);
  });

  it('accepts every reason the client can produce', () => {
    for (const reasonId of clientReasonIds()) {
      const parsed = parseReport({ reasonId, surface: 'chat', excerpt: 'x' });
      expect(parsed, `client reason "${reasonId}" is rejected by the Worker`).not.toBeNull();
    }
  });

  it('accepts every surface the client can attribute a report to', () => {
    for (const surface of clientSurfaces()) {
      const parsed = parseReport({ reasonId: 'other', surface, excerpt: 'x' });
      expect(parsed, `client surface "${surface}" is rejected by the Worker`).not.toBeNull();
    }
  });

  // The reverse direction. An earlier version iterated a hard-coded list of the six reasons, which
  // could only ever catch a reason the CLIENT gained — a reason added to the Worker alone was
  // invisible to it, and surfaces had no reverse check at all. Compare the two sets directly.
  it('the reason vocabularies are equal, not merely overlapping', () => {
    expect(workerSet('REASONS').slice().sort()).toEqual(clientReasonIds().slice().sort());
  });

  it('the surface vocabularies are equal, not merely overlapping', () => {
    expect(workerSet('SURFACES').slice().sort()).toEqual(clientSurfaces().slice().sort());
  });

  // Guards the Worker-side extractor, mirroring the client-side guard above: a rename that made
  // workerSet() return [] would make both equality assertions pass against an empty client list.
  it('actually extracts the Worker vocabulary', () => {
    expect(workerSet('REASONS').length).toBeGreaterThan(0);
    expect(workerSet('SURFACES').length).toBeGreaterThan(0);
  });

  // The Worker's caps are documented as mirroring the client's so a well-behaved app can never trip
  // them. If the client's grew past the Worker's, a report the UI accepted would 400 on submit.
  it('caps are at least as large as the client permits', () => {
    expect(MAX_EXCERPT).toBeGreaterThanOrEqual(clientCap('MAX_REPORT_EXCERPT_CHARS'));
    expect(MAX_NOTE).toBeGreaterThanOrEqual(clientCap('MAX_REPORT_NOTE_CHARS'));
    // The client normalizes modelId/backend to null past its own cap, so a client cap ABOVE this
    // one would emit identifiers the Worker rejects with a 400.
    expect(MAX_IDENT).toBeGreaterThanOrEqual(clientCap('MAX_REPORT_IDENT_CHARS'));
  });

  // Blank is rejected too — `isBoundedString` requires length > 0 — which is why the client
  // normalizes empty identifiers to null rather than passing them through.
  it('rejects a blank identifier, so the client must never send one', () => {
    expect(parseReport({ reasonId: 'other', surface: 'chat', excerpt: 'x', modelId: '' })).toBeNull();
  });

  it('accepts a null identifier, which is what the client sends when it has none', () => {
    const parsed = parseReport({
      reasonId: 'other',
      surface: 'chat',
      excerpt: 'x',
      modelId: null,
      backend: null,
    });
    expect(parsed).not.toBeNull();
  });
});
