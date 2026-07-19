# PR4 — Pixel-10 / Tensor-G5 default-model UX (deferred "Part 3")

> Branch: `feat/relais-pixel10-default-model`. No issue (deferred from the G5 crash work, PR #15).
> Depends on **PR1**. **The decision logic is overnight-shippable + unit-tested; the model choice's
> pinned metadata and the on-device serve are DEFERRED GATES** — see below. Do not fabricate
> commit hashes or sizes.

## Summary
Today a **fresh Pixel 10** (Tensor G5) boots with the global `DEFAULT_MODEL_ID`
(`litert-community/gemma-4-E4B-it-litert-lm`), which the G5 pre-flight gate refuses — the operator
sees "Init failed: … select a G5-compatible model" and must pick one manually. Nicer: on a fresh
Pixel 10, default to a **G5-proven** model so it just works. Implement this as a **pure decision
function** wired into `RelaisModelProvisioner.resolveModel`.

## Context (verified)
- `isPixel10()` — `common/Utils.kt:415-417`: `Build.MODEL` (lowercased) contains `"pixel 10"`.
- `RelaisConfig.DEFAULT_MODEL_ID = "litert-community/gemma-4-E4B-it-litert-lm"` (the G5-bad one).
- Fresh install: `RelaisConfig.modelRef(context) == null` AND `modelId(context) == DEFAULT_MODEL_ID`.
- The G5 gate (`RelaisEngine.isG5Incompatible` + the `check(...)` in `ensureInitialized`) fires
  AFTER provisioning, only for the E4B id/file. Substituting the default BEFORE provisioning means
  a fresh Pixel 10 never reaches the gate with E4B.
- A self-contained `RelaisModelRef(modelId, modelFile, commitHash, sizeInBytes, displayName,
  source)` provisions any model offline via `modelFromRef` (no allowlist membership needed).

## G5-proven models (from on-device findings, memory `relais-ondevice-verification`)
- **`gemma-4-E2B-it`** — multimodal, **serves on G5** (http 200, ~2.4s). Repo
  `litert-community/gemma-4-E2B-it-litert-lm`, file `gemma-4-E2B-it.litertlm` (~2.59 GB). **Preferred
  default** (same family as E4B, multimodal).
- **`Qwen3-0.6B`** — text-only, serves on G5 (~3.5 tok/s). Safest/smallest fallback.

## Design — pure decision function + one seam
```kotlin
// In RelaisModelProvisioner (or a small companion), pure + unit-testable (no Context):
internal fun deviceDefaultRef(
  isPixel10: Boolean,
  hasPersistedRef: Boolean,
  currentModelId: String,
): RelaisModelRef? =
  if (isPixel10 && !hasPersistedRef && currentModelId == RelaisConfig.DEFAULT_MODEL_ID)
    G5_DEFAULT_REF
  else null

// Pinned, self-contained ref for a G5-proven model. RESOLVE the real commitHash + sizeInBytes
// via the existing HF path (RelaisHuggingFace.resolve("litert-community/gemma-4-E2B-it-litert-lm"))
// and pin them here. DO NOT invent these values.
internal val G5_DEFAULT_REF = RelaisModelRef(
  modelId = "litert-community/gemma-4-E2B-it-litert-lm",
  modelFile = "gemma-4-E2B-it.litertlm",
  commitHash = "<RESOLVE-VIA-HF-AND-PIN>",   // e.g. starts 361a4010… per prior staging; confirm full sha
  sizeInBytes = /* RESOLVE-VIA-HF */ -1L,    // pin the real LFS size; -1 still downloads but loses progress %
  displayName = "Gemma 4 E2B-it (Tensor G5)",
  source = RelaisModelRef.SOURCE_HUGGINGFACE,
)
```
Wire into `resolveModel` **before** the existing ref-fast-path / allowlist logic:
```kotlin
fun resolveModel(context: Context): Model {
  deviceDefaultRef(isPixel10(), RelaisConfig.modelRef(context) != null,
                   RelaisConfig.modelId(context))?.let { ref ->
    Log.i(TAG, "Fresh Pixel 10: defaulting to G5-compatible ${ref.modelId}")
    RelaisConfig.setModelRef(context, ref)            // persist once so the UI reflects it & next boot is normal
    return modelFromRef(ref).also { it.preProcess() }
  }
  // ... existing ref-fast-path + allowlist resolution unchanged ...
}
```
> Persisting via `setModelRef` is recommended (the operator sees the real model and can change it;
> subsequent boots take the normal ref path). If you'd rather not mutate config from the init
> thread, resolve to the ref each boot without persisting — idempotent and equivalent for serving.
> Either way the **decision function stays pure and is what you unit-test.**

## How to pin the real metadata (the loop CAN do this; don't guess)
The repo already has `RelaisHuggingFace.resolve(modelId, token)`. Run a one-off resolution
(throwaway, network) to read the canonical `.litertlm` file's real `commitHash` and LFS
`sizeInBytes`, then hardcode them into `G5_DEFAULT_REF`. If you cannot resolve them confidently,
**stop and leave a clearly-marked TODO with the E2B id** rather than committing a fabricated
commit/size — a wrong commit makes the default fail to download.

## TDD plan (pure JVM, no Robolectric — `src/test`)
Test `deviceDefaultRef` directly:
1. `(isPixel10=true, hasPersistedRef=false, currentModelId=DEFAULT_MODEL_ID)` → returns
   `G5_DEFAULT_REF` (non-null; assert its `modelId`/`modelFile`). **RED before the fn exists.**
2. `(isPixel10=false, …)` → null (non-Pixel-10 unaffected — preserves existing behavior).
3. `(isPixel10=true, hasPersistedRef=true, …)` → null (operator already chose a model — don't override).
4. `(isPixel10=true, hasPersistedRef=false, currentModelId="something/else")` → null (only overrides
   the untouched default, never an explicit non-default id).
Also assert `G5_DEFAULT_REF` round-trips through `RelaisModelRef.toJson()/fromJson()` (it must be a
valid ref — non-blank modelId/modelFile/commitHash/displayName).

## Acceptance criteria
- `deviceDefaultRef` exists, is `internal`/pure, with the 4 unit tests above + the round-trip test.
- Wired into `resolveModel` before the existing resolution; non-Pixel-10 behavior byte-identical.
- `G5_DEFAULT_REF` has REAL pinned `commitHash`+`sizeInBytes` (resolved via HF) — or a marked TODO
  if not resolvable (then the PR notes the default is logic-only pending real metadata).
- `./gradlew testDebugUnitTest` + `assembleRelease` green; CI green.
- Independent review APPROVE on the final diff.

## Deferred on-device gate (document in PR, do NOT fake)
On a **fresh** Pixel 10 (`rango`): clear app data, start the node, confirm it provisions + serves
the E2B default (http 200) instead of hitting the E4B gate. Confirms the pinned ref actually
downloads and serves on G5. Owner runs later.

## Guardrails / PR
Branch `feat/relais-pixel10-default-model`; PR to `main`; review the diff; merge on green CI.
If a UI label for the chosen default is added, it MUST follow `DESIGN.md` (amber/near-black,
monospace) — but prefer keeping UI changes out of this PR (a log line is enough). Don't touch
AICore. Don't change the G5 gate or `DEFAULT_MODEL_ID` (other devices still default to E4B).
