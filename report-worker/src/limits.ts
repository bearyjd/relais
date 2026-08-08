// Copyright (C) 2026 Entrevoix / grepon.cc — AGPL-3.0-or-later.

/**
 * Size limits for the report receiver, kept OUT of `index.ts` on purpose.
 *
 * `index.ts` is the Workers entry module, and the runtime treats every named export there as a
 * service entrypoint: it must be a function or an `ExportedHandler`. A `export const MAX_BODY_BYTES
 * = 32768` is neither, so exporting these from `index.ts` made workerd refuse to start the Worker at
 * all — "Incorrect type for map entry 'MAX_BODY_BYTES'". The whole Worker was undeployable.
 *
 * Nothing caught it: the JVM-style unit tests import the module into Node, where a number export is
 * just a number, and `wrangler deploy --dry-run` only bundles without booting the runtime. It
 * surfaced the first time anything actually started the Worker. Keep values here; keep `index.ts`
 * exporting only functions and the default handler.
 */

/**
 * Field caps. These mirror the client's own limits (`ContentReportShaping.kt`) so a well-behaved app
 * can never trip them — they exist to bound a hostile caller, not to validate our own UI.
 */
export const MAX_EXCERPT = 2000;
export const MAX_NOTE = 500;
export const MAX_IDENT = 200;

/**
 * Hard cap on the request body, enforced on the stream before any parsing.
 *
 * **Must stay above the largest schema-valid report, or the transport rejects reports the schema
 * accepts.** An 8 KiB cap did exactly that: the field caps above count UTF-16 code units, while
 * this counts bytes, so a CJK or emoji report well inside every field limit was refused with 413
 * and never reached the maintainer — silently breaking the operators this feature exists for.
 *
 * Sized from the schema rather than guessed. Worst case is a JSON serializer that escapes every
 * character as `\uXXXX`, 6 bytes per UTF-16 unit:
 *
 *   (2000 excerpt + 500 note + 200 modelId + 200 backend + 64 reasonId + 64 surface) * 6
 *     ≈ 18.2 KB, plus structural overhead.
 *
 * 32 KiB clears that with room to spare and is still small enough to bound abuse.
 * `maximum valid report fits under the transport cap` in the tests pins this relationship, so the
 * two limits cannot drift apart again.
 */
export const MAX_BODY_BYTES = 32 * 1024;
