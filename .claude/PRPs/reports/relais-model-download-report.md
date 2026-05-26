# Implementation Report: Relais node self-provisioning model download

## Summary
The headless Relais node now downloads its own LLM model from Google/HuggingFace on first run
instead of requiring a manually side-loaded file. A new `RelaisModelProvisioner` resolves the
configured model id from Gallery's allowlist, reuses the existing `DownloadWorker` transport to
fetch it if absent, and points `RelaisEngine` at `Model.getPath()`. Default model is the **open**
`litert-community/gemma-4-E4B-it-litert-lm` repo (no auth needed); gated repos use an optional
pre-set HF token in `RelaisConfig`.

## Assessment vs Reality

| Metric | Predicted (Plan) | Actual |
|---|---|---|
| Complexity | Medium | Medium |
| Files Changed | ~4 + 1 test | 4 changed + 1 created + 1 test |
| Confidence | n/a | High (build green; on-device test deferred to hardware) |

## Tasks Completed

| # | Task | Status | Notes |
|---|---|---|---|
| 1 | RelaisConfig: modelId + hfToken | ✅ Complete | Mirrors existing getter/setter pattern; default = open repo |
| 2 | Provisioner: fetch allowlist + select | ✅ Complete | Reuses `getJsonResponse<ModelAllowlist>` + `toModel()` |
| 3 | Provisioner: download if missing | ✅ Complete | **Deviated** — enqueue `DownloadWorker` directly, await via Future (see below) |
| 4 | RelaisEngine: load resolved path | ✅ Complete | `ensureInitialized` default now `RelaisModelProvisioner.cachedPathOrDefault` |
| 5 | RelaisNodeService: provision then init | ✅ Complete | `ensureModel(...)` in init thread; progress in notification |
| 6 | Test: provisioning path | ✅ Complete | `g_provisionDownloadsModel`, gated on model absence |

## Validation Results

| Level | Status | Notes |
|---|---|---|
| Static Analysis / Type Check | ✅ Pass | `compileDebugKotlin` + `compileDebugAndroidTestKotlin` green |
| Build | ✅ Pass | `:app:assembleDebug :app:assembleDebugAndroidTest` — BUILD SUCCESSFUL |
| Instrumented Test | ⚠️ Compiled, not run | On-device androidTest; needs phone + network + model download |
| Integration / Edge cases | ⚠️ N/A here | All on-device (download, Doze, disk) — require hardware |

## Files Changed

| File | Action | Notes |
|---|---|---|
| `relais/RelaisModelProvisioner.kt` | CREATED | Headless allowlist fetch + download-if-missing |
| `relais/RelaisConfig.kt` | UPDATED | + `modelId`/`setModelId`, `hfToken`/`setHfToken`, `DEFAULT_MODEL_ID` |
| `relais/RelaisEngine.kt` | UPDATED | `ensureInitialized` default path → provisioner; legacy path now fallback |
| `relais/RelaisNodeService.kt` | UPDATED | Provision (download + progress notification) before engine init |
| `androidTest/.../RelaisNodeTest.kt` | UPDATED | + `g_provisionDownloadsModel` (skips if model present) |

## Deviations from Plan

1. **Provisioner functions are blocking, not `suspend`** (Tasks 2/3).
   - WHAT: `resolveModel`/`ensureModel` are plain blocking functions; download awaited via
     `WorkManager.getWorkInfoById(id).get()` polling rather than `getWorkInfoByIdFlow(...).first{}`.
   - WHY: `common.getJsonResponse` is itself a blocking `inline fun` (not suspend), there is no
     direct `kotlinx-coroutines` dependency in `build.gradle.kts` (only transitive), and the only
     callers are the service's plain `thread {}` and an instrumented test. Blocking is simpler and
     avoids a `runBlocking` + transitive-coroutine gamble.

2. **Enqueue `DownloadWorker` directly instead of `DefaultDownloadRepository.downloadModel`** (Task 3).
   - WHAT: The provisioner builds the same `Data` input contract and `enqueueUniqueWork(model.name,
     REPLACE, …)` as `downloadModel`, but enqueues the worker itself.
   - WHY: `downloadModel` unconditionally calls `observerWorkerProgress`, which does
     `getWorkInfoByIdLiveData(id).observeForever{}` — `observeForever` throws off the main thread,
     and the node init runs on a background thread. The plan's Task 3 GOTCHA anticipated this
     ("await via the WorkManager Flow instead"). Real transport (`DownloadWorker`) is still reused;
     no new HTTP code, no change to Gallery's own paths.

## Issues Encountered
None blocking. `toModel()` does not populate `totalBytes` (stays 0); resolved by calling
`model.preProcess()` after `toModel()` so download-progress % can be computed.

## Not Implemented (scope / future)
- Offline fallback to a disk-cached allowlist (plan edge-case checklist item). Gallery's
  `readModelAllowlistFromDisk` lives on the ViewModel; reusing it would drag in Hilt. Left out to
  keep the node decoupled. `resolveModel` surfaces a clear error when offline.

## Next Steps
- [ ] On-device run: `adb ... RelaisNodeTest#g_provisionDownloadsModel` on a phone with the model absent
- [ ] Confirm G1 multimodal still passes loading from the provisioned path
- [ ] Code review via `/code-review`; PR via `/prp-pr`
