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
import { MAX_EXCERPT, MAX_NOTE } from './limits';

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

  // The reverse direction: a reason the Worker accepts but no client sends is dead vocabulary. Not
  // an outage, but it means the two lists have diverged and the next edit is likelier to break.
  it('accepts nothing the client cannot produce', () => {
    const client = new Set(clientReasonIds());
    for (const candidate of ['harmful', 'sexual', 'hate', 'violent', 'misinformation', 'other']) {
      if (client.has(candidate)) continue;
      const parsed = parseReport({ reasonId: candidate, surface: 'chat', excerpt: 'x' });
      expect(parsed, `Worker accepts "${candidate}", which no client reason produces`).toBeNull();
    }
  });

  // The Worker's caps are documented as mirroring the client's so a well-behaved app can never trip
  // them. If the client's grew past the Worker's, a report the UI accepted would 400 on submit.
  it('caps are at least as large as the client permits', () => {
    expect(MAX_EXCERPT).toBeGreaterThanOrEqual(clientCap('MAX_REPORT_EXCERPT_CHARS'));
    expect(MAX_NOTE).toBeGreaterThanOrEqual(clientCap('MAX_REPORT_NOTE_CHARS'));
  });
});
