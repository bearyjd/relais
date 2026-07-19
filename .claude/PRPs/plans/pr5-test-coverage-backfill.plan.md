# PR5 — Test-coverage backfill (Robolectric port of provisioning/prefs tests)

> Branch: `test/relais-coverage-backfill`. No issue. Depends on **PR1** (harness + Robolectric dep).
> **Lowest priority / loop filler** — only after PR2–PR4 are merged. Low-risk, additive (tests only).

## Summary
PR1 ported the 3 pure tests. This PR ports the remaining **hermetic-but-Context-touching** tests to
`src/test` using Robolectric, so the provisioning/prefs/watchdog logic is covered headless and in
CI. Pure test-only PR — no production code changes (other than `internal` visibility bumps if a
test needs them, which PR3 already did for `remember`/`shouldPersistPath`).

## Port these (need Robolectric for `Context`/SharedPreferences/PendingIntent)
From `src/app/src/androidTest/java/cc/grepon/relais/`:
1. **`RelaisModelRefProvisionTest.kt`** — `RelaisModelRef` round-trip + `resolveModel` from a
   persisted ref (no network). Touches `RelaisConfig` prefs via `Context`.
2. **`RelaisProvisionerTest.kt`** — pre-staged-model adoption fast path. Touches `Context` +
   `RelaisConfig` prefs + `File` I/O.
3. **`RelaisTaskRemovedTest.kt`** — watchdog `PendingIntent` state before/after task removal.
   Touches `PendingIntent`/`Intent`/`AlarmManager` (Robolectric shadows these).

For each: add `@RunWith(RobolectricTestRunner::class)` and `@Config(sdk = [33])` (or the project's
minSdk-compatible level), replace `InstrumentationRegistry.getInstrumentation().targetContext` with
`RuntimeEnvironment.getApplication()` (Robolectric's application `Context`), and keep the
save/restore-prefs `finally` blocks (Robolectric gives each test a clean app sandbox, but keep them
for parity). Verify each passes under `./gradlew testDebugUnitTest`.

> **Delete the originals from `androidTest`** once the Robolectric versions pass, EXCEPT keep any
> assertion that genuinely needs real hardware (none of these three do — they're hermetic).

## Leave in `androidTest` (device-only — do NOT port)
- `RelaisBackendBenchmarkTest` (real LiteRT-LM GPU inference), `RelaisNodeTest` (service binding),
  `RelaisGate3Test` (real `PowerManager` thermal — has test seams but low value to port now).

## Optional: new coverage for untested pure logic
If time remains, add `src/test` cases for any pure helper lacking coverage — e.g. `normalizedName`,
`RelaisHuggingFace.pickLiteRtLm` edge cases (subdir rejection, tie-breaks), `interpretInfo` status
mappings not already covered. Keep them pure (no Robolectric) where possible.

## Acceptance criteria
- The 3 tests run green under `./gradlew testDebugUnitTest` via Robolectric (no device); originals
  removed from `androidTest`.
- `assembleRelease` green; CI green (the new unit-test job now also covers provisioning/prefs).
- No production behavior change; independent review APPROVE on the diff.

## Guardrails / PR
Branch `test/relais-coverage-backfill`; PR to `main`; review the diff; merge on green CI. Robolectric
resolution needs network the first time (already added in PR1). Don't touch AICore. If a Robolectric
shadow can't model a behavior faithfully, leave that test in `androidTest` and note why — don't
weaken the assertion to make it pass on the JVM.
