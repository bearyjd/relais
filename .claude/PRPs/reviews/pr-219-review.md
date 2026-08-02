# PR Review: #219 — feat(relais): #180 full JIT model swap

**Reviewed**: 2026-07-29
**Author**: bearyjd
**Branch**: `feat/180-full-jit-model-swap` → `main` (head `33aad45`)
**Decision**: REQUEST CHANGES (1 HIGH)
**Resolution**: H1, M1, M2, M3 all fixed in `1bd7e0c` (+ drift regression tests). L1–L5 not
addressed — deliberately out of scope for that pass.

> ⚠️ **This is a self-review.** The same agent lineage authored this PR. Per `code-review.md`
> ("never self-approve in the same active context") it must not stand as the independent approval
> gate — it is a findings pass, not an approval.

## Summary

The design is sound and the safety argument holds: the registry only gains entries on a
*locally successful* provision, so a LAN client still cannot originate a download. The
boolean → `ModelRequestOutcome` widening is the right call, the rollback-on-failed-swap is a
genuine fix, and the two traps called out in the PR body (DEFAULT_MODEL substitution, the
registry never filling on the normal boot path) were real and are correctly handled.

One HIGH remains: the registry write bypasses the issue-#11 drift guard it sits next to, so it can
permanently record `modelId → some other model's file`. Under this PR that mapping is now
*load-bearing* — it makes the node serve model A while labelling it B, with no self-correction.

## Findings

### CRITICAL
None.

### HIGH

**H1 — `recordProvisioned` bypasses the drift guard and can bind an id to the wrong file.**
`RelaisModelProvisioner.kt:306-320`

`remember()` calls `recordProvisioned(context, path)` *before* — and unconditionally of — the
`shouldPersistPath(persistForId, currentId)` gate. `recordProvisioned` then pairs `path` with
`RelaisConfig.modelId(context)`, i.e. the **current** id, not the id the path was provisioned for.
That is exactly the issue-#11 race the adjacent guard exists to prevent, re-introduced one line
above it.

Failure scenario (concrete, no exotic timing — the window is a multi-GB download):
1. Operator has model **A** configured; node starts; `idAtStart = A`; download of A begins.
2. Mid-download the operator selects model **B** in the Models UI (`setModelRef` → `modelId = B`,
   clears `modelPath`).
3. Download completes → `remember(context, pathA, persistForId = A)`.
4. `recordProvisioned` reads `modelId = B` → registry gains **`B → pathA`**.
   The drift guard then correctly declines to persist `modelPath` — but the registry already lied.
5. A client requests `model: "B"` → `SwapThenRetry(B)` → `swapTargetFor(B)` → `pathA` →
   `ensureInitialized(modelPath = pathA, modelId = B)`.
6. The node now serves **A's weights stamped as B**. Every later request for B sees
   `requested == residentModelId` → `ServeResident`. It never self-corrects, and `/v1/models`
   reports `B: provisioned true`. Only deleting A's file clears it (prune is path-keyed).

This is the precise class of bug #180 exists to close — a client asks for X and silently gets Y —
and the PR makes it durable rather than transient. It is also untested: the registry tests are pure
and never exercise the id/path pairing.

**Fix** (moves the write inside the gate it belongs to, and passes the id the path actually
belongs to):

```kotlin
internal fun remember(context: Context, path: String, persistForId: String? = null): String {
  cachedPath = path
  val currentId = RelaisConfig.modelId(context)
  if (shouldPersistPath(persistForId, currentId)) {
    RelaisConfig.setModelPath(context, path)
    // Registry entry and persisted path share one gate: both bind an id to this file, so both
    // must be refused when the id drifted mid-provision.
    recordProvisioned(context, path, persistForId ?: currentId)
  } else {
    Log.w(TAG, "Model id changed mid-provision (now $currentId, provisioned for $persistForId); " +
      "not persisting stale path")
  }
  return path
}
```
with `recordProvisioned(context, path, id)` taking the id as a parameter instead of re-reading it.
Worth a regression test asserting `remember` records nothing when `persistForId != currentId`.

### MEDIUM

**M1 — `throw t` in the swap thread can kill the node process.**
`RelaisEngine.kt:452-462`

The inner handler catches `Throwable` but the outer handler catches only `Exception`. A native
model-load failure that surfaces as an `Error` (`UnsatisfiedLinkError`, `OutOfMemoryError` — both
plausible for a multi-GB engine-create, and #180 deliberately makes unloadable models reachable by
request) escapes the outer catch. An uncaught throwable on a bare `thread {}` hits Android's default
handler, which kills the **whole process** — taking the node down after the rollback had just
successfully saved it. The `finally` still clears the flags, so nothing is leaked; the rethrow buys
only a duplicate log line.

Fix: drop `throw t` (rollback already handled it and logged), or widen the outer to
`catch (t: Throwable)`.

**M2 — omitted-`model` requests now round-trip into a 404.**
`RelaisHttpServer.kt:1188` (and `:1355`)

`val model = body.optString("model", DEFAULT_MODEL)` still feeds the *response* echo, so a request
that omits `model` gets back `"model": "gemma-4-e4b-it"` — a cosmetic alias that is in no registry
and in no `/v1/models` listing. A client or proxy that echoes `response.model` into its next request
(LiteLLM-style routing/caching layers; the exact client class this repo already accepted as real in
the #192 devil's-advocate pass) now gets a hard **404** where it previously got served.

This also puts the PR at odds with the precedent #192 settled for `/v1/embeddings`: echo the
client's `model` when present, fall back to the **real** id when absent. Here the absent case falls
back to the alias.

Fix: `val model = body.optString("model", "").takeIf { it.isNotBlank() }
  ?: RelaisEngine.residentModelId ?: DEFAULT_MODEL` — truthful, and closes the loop.

**M3 — comments now describe a safety boundary the code no longer has.**

- Four dangling KDoc links to the deleted `shouldSwapModel`: `RelaisModelSwap.kt:26`,
  `RelaisHttpServer.kt:81`, `RelaisHttpServer.kt:1121`, `RelaisEngine.kt:296`.
- `RelaisHttpServer.kt:1118-1126` — the old `rejectAndSwapIfModelMismatched` KDoc ("names the
  operator's currently-configured model … narrow-scope guard") survived the rename and is now
  orphaned directly above `provisionedOnDisk`, describing behaviour that no longer exists.
- `RelaisEngine.kt:400-412` — `ensureModelSwapInBackground`'s KDoc still opens "Kicks a background
  swap to the operator's currently-configured model (#180 … first cut)", contradicting the `target`
  parameter it now takes.
- `RelaisHttpServer.kt:80-85` — the `DEFAULT_MODEL` INVARIANT block describes an "accidental"
  safety property the decision path no longer depends on (it takes the raw field now). Left as-is,
  a future reader may either trust a guard that is gone or "fix" the constant believing it is
  load-bearing.

Given this repo's review discipline, a stale comment about a *safety boundary* is worse than no
comment. Mechanical to fix, but it should be fixed in this PR — it is this PR that invalidated them.

### LOW

- **L1** `provisionedOnDisk()` is evaluated twice per swap-triggering request
  (`RelaisHttpServer.kt:1149`, `:1159`) and once per chat request, doing N `File.exists()` syscalls
  on the request thread. Hoist to a single `val` — also removes a (harmless) TOCTOU between the
  eligibility snapshot and the target lookup.
- **L2** `notFoundBody: (String) -> JSONObject = errorBody` — a default that silently yields a 404
  with a 503-shaped envelope if a future call site forgets it. Both current callers pass it; making
  it required costs nothing.
- **L3** `RelaisConfig.setProvisionedModels` is its own `edit().apply()`, separate from
  `setModelPath`'s transaction, so a crash between them leaves the two out of step. Self-healing via
  read-prune, so cosmetic — noted only against the atomic-transaction convention elsewhere in
  `RelaisConfig`.
- **L4** An authenticated client can now thrash the engine by alternating two provisioned models
  (~23 s unload/reload each), where the first cut's guard bounded this to one model. Bounded by
  auth + the 30/min rate limit; worth a sentence in the KDoc rather than code.
- **L5** A provisioned model that is not in the curated catalog is swap-eligible but absent from
  `/v1/models`, so the 404's "see GET /v1/models" is incomplete for that case.

## What holds up well

- The registry-as-safety-boundary argument is correct: `swapTargetFor` → null → the engine's
  fallback resolves via `resolveModel().getPath()` and then `File.exists()`-gates, so no path
  through the swap can originate a download.
- Rollback captures `previousPath`/`previousId` **before** `shutdown()`, and `ensureInitialized`'s
  `if (isReady) return` cannot short-circuit the restore because `shutdown()` nulls `engine`.
- The `isReady = false → ServeResident` choice is right: 404-ing during startup would refuse models
  the node actually has.
- Test quality is good — the security-boundary test asserting `!is SwapThenRetry` across
  `../../etc/passwd`, a URL, and `""` is the right shape, and the omitted-field regression guard
  pins the landmine the PR body calls out.

## Validation Results

| Check | Result |
|---|---|
| Build Android APK (CI, `33aad45`) | Pass |
| JVM unit tests, 3 flavors (CI, `33aad45`) | Pass |
| gitleaks / trufflehog / license headers | Pass |
| Local Gradle run | Skipped — CI green on this exact head; CLAUDE.md discourages local builds |
| On-device swap between two provisioned models | **Not done** (PR body is explicit; one model on disk) |

## Files Reviewed

| File | Change |
|---|---|
| `RelaisModelRegistry.kt` | Added |
| `RelaisModelRegistryTest.kt` | Added |
| `RelaisModelSwap.kt` | Modified — `shouldSwapModel` → `resolveModelRequest` |
| `RelaisModelSwapTest.kt` | Modified |
| `RelaisHttpServer.kt` | Modified — `rejectIfModelUnavailable`, `/v1/models` `provisioned` |
| `RelaisEngine.kt` | Modified — `residentModelPath`, targeted swap, rollback |
| `RelaisModelProvisioner.kt` | Modified — `recordProvisioned`, fast-path routed via `remember` |
| `RelaisConfig.kt` | Modified — `provisionedModels` persistence |
| `RelaisError.kt` | Modified — 3-arg `json(message, type, code)` |
