# PR Review: #251 — docs: record verified IzzyOnDroid constraints for #123

**Reviewed**: 2026-08-05
**Author**: bearyjd
**Branch**: `docs/izzy-rfp-constraints` → `main`
**Decision**: APPROVE with comments (one MEDIUM found and fixed during review)

> **Reviewer independence caveat.** This PR was authored in the same session that reviewed it. That is
> a weaker guarantee than a second pair of eyes, and it is stated here rather than buried. The
> mitigation applied was to treat every factual claim as unverified and re-measure it against source
> rather than reading the prose for plausibility — which is what surfaced the MEDIUM below.

## Summary

Docs-only change (+32/−2, one file) recording verified IzzyOnDroid inclusion constraints and correcting
three wrong assumptions. The risk surface for a doc whose entire purpose is "do not re-derive these
facts" is **factual accuracy**, not code quality — and this file had already shipped two wrong
conclusions before this review. Every claim was therefore re-verified independently. Nine checks: eight
passed exactly, one found an incomplete enumeration.

## Findings

### CRITICAL
None. No code, no secrets, no executable surface.

### HIGH
None.

### MEDIUM

**M1 — Incomplete itemisation of the TTS runtime (fixed during review).**
`docs/distribution.md` listed the sherpa-onnx runtime as three libs totalling 10.93 MiB. Measurement
against the published `app-degoogled-open-release.apk` shows **four**:

| Lib | MiB (compressed) |
|---|---|
| `libonnxruntime.so` | 7.33 |
| `libsherpa-onnx-jni.so` | 1.84 |
| `libsherpa-onnx-c-api.so` | 1.76 |
| `libsherpa-onnx-cxx-api.so` | **0.14 — omitted** |
| **actual subtotal** | **11.06** |

The omission *understated* the saving, so the headline conclusion (~22.5 MiB, under cap) is unaffected —
33.60 − 11.06 = 22.54, which the doc already rounded to "~22.5". But the defect matters more than
0.13 MiB suggests: this document's own stated lesson is *"do not treat the smallest current variant as a
floor without itemising what is inside it,"* and it then itemised incompletely. Corrected to 11.06 MiB
with the fourth lib named.

### LOW

**L1 — "short 77/80" is bytes, not characters.** `wc -c` on `short_description.txt` returns 77 including
the trailing newline; the actual string is 76 characters. Izzy's cap is on characters, so the app is
further under the limit than stated. Harmless in the safe direction; not worth a commit on its own.

## Validation Results

No typecheck / lint / test / build applies to a markdown-only change. Validation for this PR is claim
verification, which is the meaningful gate:

| Check | Claim | Result |
|---|---|---|
| C1 | `build.gradle.kts:95-98` is the compression note | **Pass** — exact |
| C2 | metadata: short 77, full 2324, icon, 3 screenshots, changelogs ≤500 | **Pass** — 474/271/378 |
| C3 | fullOpen 74.3 MiB, degoogledOpen 33.6 MiB | **Pass** — 74.27 / 33.60 |
| C4 | TTS runtime subtotal | **FAIL → fixed** — 11.06, not 10.93 (M1) |
| C5 | libsdcpp 29.51 MiB | **Pass** — exact |
| C6 | onnxruntime has no consumer but sherpa | **Pass** — zero Kotlin refs |
| C7 | 248% / 112% of cap, ~7.5 MiB headroom | **Pass** — 248% / 112% / 7.46 |
| C8 | image-gen helps `fullOpen` only | **Pass** — libsdcpp absent from degoogled |
| C9 | inclusion-policy link resolves | **Pass** — HTTP 200 |

Measurements taken with `unzip -v` column 3 (compressed) against the **published v1.0.17 release
artifacts**, downloaded fresh — not against a local build and not against column 1.

## Files Reviewed

| File | Change |
|---|---|
| `docs/distribution.md` | Modified — +32/−2, one new subsection plus a sizing-gotcha note |

## Notes for the merger

The substantive claim — that unbundling the TTS runtime puts `degoogledOpen` under Izzy's cap without an
exception — is independently reproducible in four commands: download the release APK, `unzip -v`, sum
the sherpa/onnxruntime rows, subtract. It does not rest on the prose.

What this review cannot do is second-guess the *judgement* calls: whether Relais's proprietary-blob case
("litertlm is the product") will actually satisfy Izzy, and whether `degoogledOpen` is the right variant
to put in front of F-Droid users. Both are stated as open in the doc and in #123.
