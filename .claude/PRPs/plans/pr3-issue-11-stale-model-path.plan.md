# PR3 — #11 ensureModel must not persist a stale model path on mid-provision id drift

> Branch: `fix/relais-stale-model-path`. GitHub issue: **#11** (read it — root cause, repro, and a
> proposed diff). Depends on **PR1**.

## Summary
`RelaisModelProvisioner.ensureModel` resolves the model for the id read at entry, then (for an
absent model) runs a multi-minute `download()`, then persists the path via `remember()`. If the
operator changes the model **mid-download** (`adb … --es modelId <B>`, which calls
`RelaisConfig.setModelId(B)` and clears `KEY_MODEL_PATH`), the still-running `ensureModel` writes
`setModelPath(oldPathA)` **after** the clear — resurrecting a stale path. Next boot's offline
fast-path serves the **old** model under the **new** id, and stays stuck. Refuse to persist a path
whose id drifted.

## Root cause (verified, current code)
- `ensureModel` (`RelaisModelProvisioner.kt:145-194`): fast-paths + `resolveModel` → `download` →
  `return remember(context, path)`.
- `remember` (`:196-201`) is **`private`** and writes unconditionally:
  ```kotlin
  private fun remember(context: Context, path: String): String {
    cachedPath = path
    RelaisConfig.setModelPath(context, path)
    return path
  }
  ```
- `RelaisConfig.setModelId` (`:136-152`) and `setModelRef` clear `KEY_MODEL_PATH` on a change — but
  nothing stops a long-running `ensureModel` from re-writing it afterward.

## Fix (source-level — covers both UI and `--es modelId` paths)
Capture the id at entry; refuse to persist a path whose current id no longer matches. Keep serving
the already-downloaded model for **this** boot (it's what was actually provisioned, consistent with
the "Restart to apply" contract) but don't poison `KEY_MODEL_PATH`, so the **next** boot re-resolves.

Extract the decision as a **pure function** (so it's unit-testable with no `Context`):
```kotlin
/** True iff a freshly provisioned path may be persisted: no drift, or drift unknown. */
internal fun shouldPersistPath(provisionedForId: String?, currentId: String): Boolean =
  provisionedForId == null || provisionedForId == currentId
```
Then thread it through `ensureModel`/`remember`:
```kotlin
fun ensureModel(context: Context, onProgress: (Int) -> Unit = {}): String {
  val idAtStart = RelaisConfig.modelId(context)
  // ... existing fast paths: call remember(context, path, persistForId = idAtStart) ...
  // ... terminal return: return remember(context, path, persistForId = idAtStart)
}

internal fun remember(context: Context, path: String, persistForId: String? = null): String {
  cachedPath = path   // in-memory cache is fine — it's this boot
  if (shouldPersistPath(persistForId, RelaisConfig.modelId(context))) {
    RelaisConfig.setModelPath(context, path)
  } else {
    Log.w(TAG, "Model id changed mid-provision (now ${RelaisConfig.modelId(context)}, " +
      "provisioned $persistForId); not persisting stale path")
  }
  return path
}
```
Apply `persistForId = idAtStart` at **every** terminal `remember(...)` call in `ensureModel`
(the staged-adoption fast path, the already-present path, and the post-download return). The
offline fast-path-1 (persisted path still on disk) returns early **without** `remember`, so it's
unaffected.

> Note: the staged-adoption fast path is gated to `DEFAULT_MODEL_ID`, so its `idAtStart` is the
> default — drift there is still correctly handled by the same guard.

## TDD plan (pure JVM, no Robolectric — `src/test`)
The core is `shouldPersistPath` — test it directly (no Android needed):
1. `shouldPersistPath(null, "a/b")` → `true` (no drift info → persist; preserves current behavior
   for callers that don't pass an id).
2. `shouldPersistPath("a/b", "a/b")` → `true` (no drift → persist).
3. `shouldPersistPath("a/b", "c/d")` → `false` (drift → do NOT persist). **This is the bug guard;
   it must be RED before the function exists.**

Write the test first against the not-yet-existing `shouldPersistPath` (RED = won't compile /
fails), then implement. If you want to also cover `remember`'s wiring without a device, keep the
prefs write behind `shouldPersistPath` so the pure test fully covers the decision; the
`RelaisConfig` write itself is exercised by the deferred on-device repro and (optionally) PR5's
Robolectric provisioning test.

## Acceptance criteria
- `shouldPersistPath` exists, is `internal`, and has the 3 unit tests above (the drift case failed
  before, passes after).
- `ensureModel` passes `persistForId = idAtStart` at all terminal `remember` calls; `remember` is
  `internal` and guards the persist.
- `./gradlew testDebugUnitTest` + `assembleRelease` green; CI green.
- Behavior unchanged when no drift; stale path NOT persisted on drift.
- Independent review APPROVE on the final diff.

## Deferred on-device gate (document in PR, do NOT fake)
The issue's repro: start the node downloading model A, mid-download
`am start -n com.ventouxlabs.relais/cc.grepon.relais.RelaisControlActivity --es cmd start --es token <key> --es modelId <B>`,
let A finish, restart, confirm `logcat -s RelaisModelProvisioner` shows **B** resolving (not the
stale A path). Owner runs on `rango` later.

## Guardrails / PR
Branch `fix/relais-stale-model-path`; PR to `main`; review the diff; merge on green CI. Minimal,
additive change — don't refactor `ensureModel`'s fast-path structure. Don't touch AICore.
