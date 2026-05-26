# Plan: Relais node self-provisioning model download (Google + HuggingFace)

## Summary
Give the headless Relais node the ability to download its own LLM model from Google hosting
(`dl.google.com`) and/or HuggingFace, instead of requiring a manually pre-placed file. Reuse the
Gallery app's existing download stack (`DownloadWorker`, `DownloadRepository`, `AllowedModel.toModel()`,
allowlist parsing, HF token injection) behind a small headless provisioner, and point
`RelaisEngine` at the resolved on-disk path.

## User Story
As an operator standing up a Relais node on a phone, I want the node to fetch its model from
Google/HuggingFace by id on first run, so that I don't have to side-load a multi-GB file by hand.

## Problem → Solution
Today `RelaisEngine.defaultModelPath()` is a hardcoded `{externalFilesDir}/relais/gemma-4-E4B-it.litertlm`
that must be populated manually (we copied it via adb during the spike). → The node resolves the model
from the allowlist by `modelId`, downloads it via the existing `DownloadWorker` if absent, and loads it
from `Model.getPath()`.

## Metadata
- **Complexity**: Medium
- **Source PRD**: N/A (free-form)
- **PRD Phase**: N/A
- **Estimated Files**: ~4 (1 new provisioner, RelaisEngine, RelaisConfig, RelaisNodeService) + 1 test

---

## UX Design

### Before
```
operator: adb push gemma-4-E4B-it.litertlm  →  cp into app files dir  →  start node
```
### After
```
operator: start node (optionally set modelId / HF token)
node: fetch allowlist → resolve model → download (progress in notification) → engine ready
```

### Interaction Changes
| Touchpoint | Before | After | Notes |
|---|---|---|---|
| Model presence | manual side-load required | auto-download if missing | open models need no auth |
| Control activity | shows endpoints/key | also shows download progress + "provisioning…" state | reuse existing poll loop |
| HF-gated models | n/a | optional pre-set token in `RelaisConfig` | headless can't do interactive OAuth |

---

## Mandatory Reading

| Priority | File | Lines | Why |
|---|---|---|---|
| P0 | `app/.../data/DownloadRepository.kt` | 81-272 | `downloadModel()` enqueue + `observerWorkerProgress()`; the API to reuse |
| P0 | `app/.../worker/DownloadWorker.kt` | 67-321 | actual HTTP download, resume (`.gallerytmp`), Bearer token, output dir layout |
| P0 | `app/.../data/ModelAllowlist.kt` | 37-92, 253-257 | URL construction (HF vs Google `url`), SOC variants, `toModel()` |
| P0 | `app/.../data/Model.kt` | 67-178, 337-376, 414-421 | download fields, `normalizedName`, `getPath()`, `ModelDownloadStatusType` |
| P1 | `app/.../ui/modelmanager/ModelManagerViewModel.kt` | 83-86, 889-1031, 704-734 | allowlist URL + fetch/parse, HF token read (`getTokenStatusAndData`) |
| P1 | `app/.../data/Consts.kt` | 23-38 | WorkManager Data keys, `TMP_FILE_EXT` |
| P0 | `app/.../relais/RelaisEngine.kt` | 98-133 | current hardcoded path + `ensureInitialized` to modify |
| P2 | `app/.../common/Utils.kt` | `getJsonResponse` | how the allowlist JSON is fetched/parsed |
| P2 | `app/src/androidTest/.../RelaisNodeTest.kt` | 56-224 | test/validation harness to extend |

## External Documentation
| Topic | Source | Key Takeaway |
|---|---|---|
| HF resolve URL | huggingface.co docs | `https://huggingface.co/{repo}/resolve/{rev}/{file}?download=true`; gated repos need `Authorization: Bearer <token>` |
| Google AI Edge hosting | allowlist `url` fields | open `https://dl.google.com/...` direct links (e.g. gemma-3n) need no auth |
| WorkManager await | developer.android.com | `WorkManager.getWorkInfoByIdFlow(id)` to await terminal state headlessly (vs LiveData) |

KEY_INSIGHT: The litert-community Gemma-4-E4B-it model downloaded in the spike with **no auth** — open repo. APPLIES_TO: default node model. GOTCHA: official `google/gemma-*` repos are license-gated → need a token.

---

## Patterns to Mirror

### NAMING_CONVENTION
// SOURCE: app/.../relais/*.kt
Package `com.google.ai.edge.gallery.relais`; `object` singletons (`RelaisEngine`, `RelaisConfig`,
`RelaisDiscovery`); `TAG` const per file; functions camelCase.

### URL_CONSTRUCTION
// SOURCE: data/ModelAllowlist.kt:79-95
```kotlin
var downloadUrl = url ?: "https://huggingface.co/$modelId/resolve/$commitHash/$modelFile?download=true"
if (socToModelFiles?.isNotEmpty() == true) socToModelFiles[SOC]?.let { info ->
  downloadUrl = info.url ?: "https://huggingface.co/$modelId/resolve/${info.commitHash}/${info.modelFile}?download=true"
}
```

### DOWNLOAD_ENQUEUE
// SOURCE: data/DownloadRepository.kt:96-149
```kotlin
val downloadRepository = DefaultDownloadRepository(context, /* lifecycleProvider */)
downloadRepository.downloadModel(model, onStatusUpdated = { m, status -> /* progress */ })
```

### PATH_RESOLVE
// SOURCE: data/Model.kt:349-376
```kotlin
val path = model.getPath(context) // {externalFilesDir}/{normalizedName}/{version}/{downloadFileName}
```

### HF_TOKEN
// SOURCE: DownloadWorker.kt:134-136 + DownloadRepository.kt:123-125
```kotlin
if (accessToken != null) connection.setRequestProperty("Authorization", "Bearer $accessToken")
```

### TEST_STRUCTURE
// SOURCE: app/src/androidTest/.../RelaisNodeTest.kt:68-81
Instrumented; start service via binder; poll `binder.isReady`; assert; log under tag `RelaisBench`;
run via `adb shell am instrument`.

---

## Files to Change

| File | Action | Justification |
|---|---|---|
| `app/.../relais/RelaisModelProvisioner.kt` | CREATE | Headless: fetch allowlist, select model by id, download-if-missing, return path |
| `app/.../relais/RelaisEngine.kt` | UPDATE | Resolve model path via provisioner/`Model.getPath()` instead of hardcoded `relais/...` |
| `app/.../relais/RelaisConfig.kt` | UPDATE | Add `modelId` (default `litert-community/gemma-4-E4B-it-litert-lm`) + optional `hfToken` |
| `app/.../relais/RelaisNodeService.kt` | UPDATE | Provision (download) before/within engine init; surface "provisioning…" in notification |
| `app/src/androidTest/.../RelaisNodeTest.kt` | UPDATE | Add `g_provisionDownloadsModel` (skips if model already present) |

## NOT Building
- Interactive HuggingFace OAuth in the headless node (no UI). HF-gated models require a **pre-set token** in `RelaisConfig`; obtaining it is out of scope.
- A model picker / multi-model manager UI (Gallery already has one).
- New download transport — reuse `DownloadWorker` (HttpURLConnection) as-is; do **not** add OkHttp.
- Changing Gallery's own download/allowlist code paths (additive only).
- Auto-update / re-download on new allowlist version (future).

---

## Step-by-Step Tasks

### Task 1: Config — model id + optional HF token
- **ACTION**: Add to `RelaisConfig`: `modelId(ctx)` default `"litert-community/gemma-4-E4B-it-litert-lm"`, and `hfToken(ctx)` nullable.
- **IMPLEMENT**: SharedPreferences getters/setters mirroring existing `apiKey`/`autoStart`.
- **MIRROR**: NAMING_CONVENTION; existing `RelaisConfig` getters.
- **IMPORTS**: none new.
- **GOTCHA**: keep defaults to the **open** litert-community repo so first run needs no token.
- **VALIDATE**: unit-free; referenced by provisioner.

### Task 2: RelaisModelProvisioner — fetch allowlist + select model
- **ACTION**: Create `object RelaisModelProvisioner`. Fetch the allowlist JSON (reuse `getJsonResponse<ModelAllowlist>` from Utils.kt) from `ALLOWLIST_BASE_URL/{appVersion}.json`; find the `AllowedModel` whose `modelId` == configured id; call `.toModel()`.
- **IMPLEMENT**: `suspend fun resolveModel(context): Model`.
- **MIRROR**: ModelManagerViewModel.kt:889-930 fetch logic (extract the minimal path; do NOT depend on the ViewModel/Hilt).
- **IMPORTS**: `com.google.ai.edge.gallery.data.{ModelAllowlist, Model}`, `com.google.ai.edge.gallery.common.getJsonResponse`.
- **GOTCHA**: allowlist URL is versioned (`{1_0_x}.json`); reuse the same version source Gallery uses. Network call → must be off main thread.
- **VALIDATE**: log resolved `model.name`, `model.url`, `model.getPath(context)`.

### Task 3: RelaisModelProvisioner — download if missing
- **ACTION**: `suspend fun ensureModel(context): String` — if `File(model.getPath(context)).exists()` return it; else set `model.accessToken = RelaisConfig.hfToken(context)`, enqueue via `DefaultDownloadRepository.downloadModel(model, …)`, await terminal state, return path.
- **IMPLEMENT**: await with `WorkManager.getInstance(context).getWorkInfoByIdFlow(id).first { it.state.isFinished }`; surface progress via the `onStatusUpdated` callback (received bytes / total).
- **MIRROR**: DOWNLOAD_ENQUEUE; DownloadWorker output layout (`{normalizedName}/{version}/`).
- **IMPORTS**: `com.google.ai.edge.gallery.data.DefaultDownloadRepository`, `androidx.work.WorkManager`, `kotlinx.coroutines.flow.first`.
- **GOTCHA**: `DownloadRepository.downloadModel` takes a lifecycle/coroutine provider and uses `observerWorkerProgress` (LiveData) — for headless, await via the WorkManager **Flow** instead, or run the observer on a thread with a Looper. Prefer the Flow path. The worker already runs its own foreground notification.
- **VALIDATE**: on a device with the model absent, confirm bytes grow under `{normalizedName}/{version}/…gallerytmp` then finalize.

### Task 4: RelaisEngine — load resolved path
- **ACTION**: Replace `defaultModelPath()` hardcoding: have `ensureInitialized` accept a resolved path; keep the old `relais/…` path as a fallback override.
- **IMPLEMENT**: `RelaisEngine.ensureInitialized(context, modelPath = RelaisModelProvisioner.cachedPathOrDefault(context))`.
- **MIRROR**: PATH_RESOLVE.
- **GOTCHA**: provisioning is suspend/slow; call it in the service init thread BEFORE `ensureInitialized`, not inside the `synchronized` block.
- **VALIDATE**: engine inits from the downloaded path; G1 still passes.

### Task 5: RelaisNodeService — provision then init
- **ACTION**: In the init thread, `RelaisModelProvisioner.ensureModel(applicationContext)` (download if needed, update notification "Downloading model NN%…"), then `RelaisEngine.ensureInitialized(ctx, path)`, then start servers + discovery.
- **MIRROR**: existing `onCreate` init thread + `updateNotification`.
- **GOTCHA**: download can take minutes (3.6 GB); keep the partial wake lock (already held); foreground type `dataSync` already declared.
- **VALIDATE**: from a clean install (no model), starting the node downloads then serves.

### Task 6: Test — provisioning path
- **ACTION**: Add `g_provisionDownloadsModel` to `RelaisNodeTest`: if model already present, `assumeTrue(false)` (skip); else call `RelaisModelProvisioner.ensureModel` and assert the file exists + engine becomes ready.
- **MIRROR**: TEST_STRUCTURE.
- **GOTCHA**: don't delete an existing 3.6 GB model in CI; gate on absence.
- **VALIDATE**: `adb shell am instrument … -e class …RelaisNodeTest#g_provisionDownloadsModel`.

---

## Testing Strategy

### Unit / Integration Tests
| Test | Input | Expected | Edge Case? |
|---|---|---|---|
| resolveModel | configured modelId present in allowlist | returns Model with non-empty url + path | model id missing → clear error |
| ensureModel (present) | model file on disk | returns path, no download | yes |
| ensureModel (absent) | open litert-community model | downloads, file exists, returns path | network drop mid-download (resume) |
| HF-gated w/o token | google/gemma-* id, no token | 401 surfaced as clear error | yes |
| engine init from downloaded path | provisioned path | `isReady==true`, G1 multimodal passes | — |

### Edge Cases Checklist
- [ ] Model already present → skip download
- [ ] Network failure mid-download → `.gallerytmp` resume via Range header
- [ ] HF-gated model without token → 401 surfaced, not a crash
- [ ] Allowlist fetch fails (offline) → fall back to cached allowlist on disk
- [ ] Disk full → download fails with clear error
- [ ] Concurrent start requests → single enqueue (`ExistingWorkPolicy.REPLACE`)

---

## Validation Commands

### Static / Build
```bash
cd Android/src && ANDROID_HOME=/home/user/Android/Sdk ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
```
EXPECT: BUILD SUCCESSFUL

### On-device (provisioning)
```bash
# clean state: remove model dir, then start node and watch it download
adb shell run-as com.google.aiedge.gallery ls files/   # or am instrument the new test
adb shell am instrument -w -e class com.google.ai.edge.gallery.RelaisNodeTest#g_provisionDownloadsModel \
  com.google.aiedge.gallery.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -s RelaisBench RelaisModelProvisioner DownloadWorker
```
EXPECT: model downloaded to `{normalizedName}/{version}/…`, engine ready, G1 passes.

### Manual
- [ ] Fresh install, no model → start node via control activity → notification shows download % → endpoint serves.
- [ ] With model present → start node → no re-download, ready fast.

---

## Acceptance Criteria
- [ ] Node downloads the default (open) model with no token when absent
- [ ] HF-gated model works when a token is set in `RelaisConfig`
- [ ] Engine loads from the resolved/downloaded path; G1 multimodal still passes
- [ ] No changes to Gallery's existing download/allowlist behavior
- [ ] Reuses `DownloadWorker`/`DownloadRepository` (no new transport)

## Completion Checklist
- [ ] Follows `relais/` object + RelaisConfig patterns
- [ ] Error handling: clear messages for 401/offline/disk-full
- [ ] Logging under `RelaisModelProvisioner` tag + reuse DownloadWorker logs
- [ ] Test added and gated on model absence
- [ ] No hardcoded model path remains (fallback only)
- [ ] SPIKE-FINDINGS / docs note self-provisioning

## Risks
| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| `DownloadRepository` coupled to LiveData/ViewModel lifecycle | Med | Med | Await via WorkManager Flow; pass a minimal lifecycle/coroutine provider |
| HF gating blocks default model | Low | High | Default to **open** litert-community repo (validated in spike, no auth) |
| 3.6 GB download under Doze | Med | Med | Worker foreground + node wake lock; resume via `.gallerytmp` |
| Allowlist URL/version drift | Low | Med | Reuse Gallery's version source; cache last-good allowlist |

## Notes
- The spike confirmed `litert-community/gemma-4-E4B-it-litert-lm` downloads **without auth** and lands at
  `{externalFilesDir}/Gemma_4_E4B_it/{commitHash}/gemma-4-E4B-it.litertlm` — exactly the `Model.getPath()`
  layout, so switching `RelaisEngine` to `getPath()` aligns the node with Gallery's storage and removes the
  manual copy step.
- Google-hosted models use the allowlist `url` field (open `dl.google.com`); HF-hosted use the constructed
  `resolve?download=true` URL. `AllowedModel.toModel()` already handles both — no branching needed in node code.
- Reuse, don't rebuild: `DownloadWorker`, `DefaultDownloadRepository.downloadModel()`, `AllowedModel.toModel()`,
  `Model.getPath()`, allowlist Gson parsing.
