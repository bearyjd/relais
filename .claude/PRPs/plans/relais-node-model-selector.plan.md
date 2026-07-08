# Plan: Relais Node Model Selector (curated library + HuggingFace search)

## Summary
Replace the free-text **MODEL ID** field in the Relais Node control panel with a tap-to-open **model selector** that (a) lists the curated, node-runnable models from the app's allowlist and (b) lets the operator **search HuggingFace** for any LiteRT-LM model and provision it dynamically. Selecting a model persists a self-contained "model ref" (id + file + commit + size) so the headless node can download/serve it without depending on a static allowlist match.

## User Story
As a **Relais node operator**, I want to **pick a model from the curated library or search HuggingFace** instead of typing an exact repo id, so that I can **discover and switch models without memorizing ids, commit hashes, or filenames** — and without picking something that won't run on the node.

## Problem → Solution
**Current:** `MODEL ID` is a free-text `OutlinedTextField`; the operator must know the exact allowlist `modelId` string. `RelaisModelProvisioner.resolveModel` fetches the allowlist and **throws if the id isn't in it** — so only the ~9 curated allowlist models work, and there is zero discovery.
**Desired:** A selector showing curated node-runnable models **plus** a HuggingFace search; any chosen model (curated or arbitrary HF repo with a `.litertlm` file) is resolved to full download metadata and provisioned by the node.

## Metadata
- **Complexity**: **Large** (XL-leaning; ship in 2 phases — see Phasing)
- **Source PRD**: N/A (free-form via `/prp-plan`)
- **PRD Phase**: N/A
- **Estimated Files**: ~8 new + ~3 changed (+ tests)
- **Scope decisions (confirmed with user)**: HF half = **full HF search**; selector shows **only node-runnable models** (`runtimeType == LITERT_LM` AND a chat task type).

---

## Phasing (recommended order)

- **Phase A — Curated selector + model ref (Medium).** Decouple provisioning from the allowlist via a persisted `RelaisModelRef`; replace the free-text field with a bottom-sheet picker of curated LiteRT-LM chat models (fetched directly from the allowlist, no Gallery ViewModel). Ships standalone value.
- **Phase B — HuggingFace search + dynamic resolve (Large).** Add an HF API client (search → model-info → tree-size), a search section in the selector, and the dynamic ref path. Depends on Phase A's `RelaisModelRef` + provisioner change.

Each phase compiles + is on-device verifiable independently.

---

## UX Design

### Before
```
┌──────────────────────────────────────┐
│  MODEL ID                            │
│  [ litert-community/gemma-4-E4B-it… ]│   ← free text, must be exact
│  HF TOKEN (gated repos only)         │
│  [ ………………………………………… ]│
│  [ SAVE MODEL ]                      │
└──────────────────────────────────────┘
```

### After
```
┌──────────────────────────────────────┐        tap ▸ opens bottom sheet
│  MODEL    gemma-4-E4B-it        ▸    │ ─────────────────────────────┐
│  HF TOKEN (gated repos only)         │                              ▼
│  [ ………………………………………… ]│   ┌──────────────────────────────────┐
│  [ SAVE MODEL ]                      │   │ SELECT MODEL                     │
└──────────────────────────────────────┘   │ ── CURATED ──                    │
                                            │  ● gemma-4-E4B-it      ✓ 3.6GB   │
                                            │  ○ gemma-3n-E2B          2.9GB   │
                                            │  ○ qwen2.5-1.5b          1.6GB   │
                                            │ ── HUGGINGFACE ──                │
                                            │  [ search huggingface…   🔍 ]    │
                                            │  ○ litert-community/…  (.litertlm)│
                                            │  ○ unsloth/…           (.litertlm)│
                                            │ [ or paste a repo id ]           │
                                            └──────────────────────────────────┘
```

### Interaction Changes
| Touchpoint | Before | After | Notes |
|---|---|---|---|
| MODEL field | free-text `OutlinedTextField` | tappable `Readout`-style row → bottom sheet | Mirrors `ModelPickerChip` modal pattern, restyled per `DESIGN.md` |
| Choose curated | type exact id | tap a row in CURATED | writes a full `RelaisModelRef` |
| Choose HF model | not possible | type query → tap a result → resolve → select | resolves commit+file+size via HF API |
| Arbitrary repo | type id (must already be in allowlist) | "paste a repo id" → resolve | folds in "pull any HF model" |
| adb `--es modelId` | sets `KEY_MODEL_ID` | unchanged (still works; resolves via allowlist or HF on next start) | backward compatible |
| Apply | "Restart to apply" note | unchanged | provisioning happens on node start |

---

## Mandatory Reading

| Priority | File | Lines | Why |
|---|---|---|---|
| P0 | `Android/src/app/src/main/java/cc/grepon/relais/RelaisModelProvisioner.kt` | 76–134 | `resolveModel` (allowlist match) + `ensureModel` fast-paths — the function to extend for the ref path |
| P0 | `Android/src/app/src/main/java/cc/grepon/relais/data/ModelAllowlist.kt` | 46–239 | `AllowedModel` fields + `toModel()` — reuse to build a `Model` from a ref |
| P0 | `Android/src/app/src/main/java/cc/grepon/relais/RelaisConfig.kt` | 29–164 | prefs idioms (plaintext vs encrypted), `modelId`/`hfToken` get/set — add `modelRef` |
| P0 | `Android/src/app/src/main/java/cc/grepon/relais/RelaisControlActivity.kt` | 79–369 | the control-panel Compose surface, palette, `Readout`/`Divider`/`ActionLink`/`AccessKeyChip` idioms |
| P0 | `DESIGN.md` | all | amber/charcoal/monospace; StopRed reserved for Stop; 6dp radius; bottom-sheet styling must match |
| P1 | `Android/src/app/src/main/java/cc/grepon/relais/common/Utils.kt` | 74–99 | `getJsonResponse<T>` / `parseJson<T>` (HttpURLConnection + Gson) — reuse for allowlist + HF GETs |
| P1 | `Android/src/app/src/main/java/cc/grepon/relais/data/Model.kt` | 67–177, 337–376 | `Model` fields, `normalizedName`, `getPath`, `preProcess` (totalBytes) |
| P1 | `Android/src/app/src/main/java/cc/grepon/relais/data/Tasks.kt` | 136–145 | `BuiltInTaskId.LLM_CHAT = "llm_chat"` — the filter key |
| P1 | `Android/src/app/src/main/java/cc/grepon/relais/worker/DownloadWorker.kt` | 94–106, 131–136 | what the worker reads; Bearer-token header injection |
| P2 | `Android/src/app/src/main/java/cc/grepon/relais/ui/common/ModelPickerChip.kt` | 71–164 | reference for the `ModalBottomSheet` + selection-callback pattern |
| P2 | `Android/src/app/src/androidTest/java/cc/grepon/relais/RelaisProvisionerTest.kt` | all | instrumented-test idiom (AGPL header, `AndroidJUnit4`, `targetContext`, snapshot/restore prefs) |

## External Documentation

| Topic | Source | Key Takeaway |
|---|---|---|
| HF list/search models | `GET https://huggingface.co/api/models?search={q}&author={a}&filter={tag}&sort=downloads&direction=-1&limit={n}` | Returns `[{id, downloads, likes, gated, tags}]`. `full=true` adds `siblings`. |
| HF model info | `GET https://huggingface.co/api/models/{id}` | Exposes `sha` (latest commit on default branch) + `siblings:[{rfilename}]`. Pick the `.litertlm` sibling. |
| HF repo tree (sizes) | `GET https://huggingface.co/api/models/{id}/tree/{revision}?recursive=true` | Returns `[{path, size, lfs}]`. For large LFS models the byte size is `lfs.size` (fallback `size`). |
| HF resolve URL | `https://huggingface.co/{id}/resolve/{revision}/{file}?download=true` | Exactly what `AllowedModel.toModel()` already constructs (ModelAllowlist.kt:80). |
| HF gated/auth | model-info `gated` field (`false|"auto"|"manual"`); pass `Authorization: Bearer <token>` | Headless node has no OAuth — uses pre-set `RelaisConfig.hfToken`. Mirror DownloadWorker.kt:134-136. |

> Sources: [HF Hub API](https://huggingface.co/docs/hub/en/api), [HfApi reference](https://huggingface.co/docs/huggingface_hub/package_reference/hf_api), [file-size issue #1158](https://github.com/huggingface/huggingface_hub/issues/1158). The docs now redirect to an OpenAPI playground (`https://huggingface.co/.well-known/openapi.json`) — verify the exact `tree` size field against a real response during Task B2 (see GOTCHA).

---

## Patterns to Mirror

### NETWORK_JSON_FETCH (reuse for allowlist + HF GETs)
```kotlin
// SOURCE: common/Utils.kt:74-99
inline fun <reified T> getJsonResponse(url: String): JsonObjAndTextContent<T>? {
  val connection = URL(url).openConnection() as HttpURLConnection
  connection.requestMethod = "GET"
  connection.connect()
  if (connection.responseCode == HttpURLConnection.HTTP_OK) {
    val response = connection.inputStream.bufferedReader().use { it.readText() }
    return JsonObjAndTextContent(jsonObj = parseJson<T>(response), textContent = response)
  }
  return null            // silent null on non-200 — caller decides
}
```
> GOTCHA: `getJsonResponse` sets **no auth header**. For gated HF repos add a Bearer-aware variant (see Task B1). Gson, not kotlinx.serialization, is the JSON lib here.

### ALLOWLIST_RESOLVE (the function to extend)
```kotlin
// SOURCE: RelaisModelProvisioner.kt:76-89
fun resolveModel(context: Context): Model {
  val modelId = RelaisConfig.modelId(context)
  val version = BuildConfig.VERSION_NAME.replace(".", "_")
  val url = "$ALLOWLIST_BASE_URL/$version.json"
  val allowlist = getJsonResponse<ModelAllowlist>(url)?.jsonObj
      ?: error("Could not fetch model allowlist from $url (offline?)")
  val allowed = allowlist.models.firstOrNull { it.modelId == modelId }
      ?: error("Model id '$modelId' not found in allowlist $url")
  return allowed.toModel().also { it.preProcess() }
}
```

### ALLOWEDMODEL_TO_MODEL (reuse to build a Model from a ref)
```kotlin
// SOURCE: data/ModelAllowlist.kt:75-92 (URL construction)
var downloadUrl = url ?: "https://huggingface.co/$modelId/resolve/$commitHash/$modelFile?download=true"
// taskTypes drive isLlm; runtimeType drives config creation; preProcess() sets totalBytes.
```

### CONFIG_PREFS (add modelRef the same way)
```kotlin
// SOURCE: RelaisConfig.kt:130-164  (plaintext prefs for non-secrets; encrypted for secrets)
fun modelId(context: Context): String =
  prefs(context).getString(KEY_MODEL_ID, null) ?: DEFAULT_MODEL_ID
fun setModelId(context: Context, value: String) {
  val p = prefs(context)
  val changed = p.getString(KEY_MODEL_ID, null) != value
  p.edit().putString(KEY_MODEL_ID, value).apply()
  if (changed) p.edit().remove(KEY_MODEL_PATH).apply()   // invalidate cached provisioned path on change
}
```
> GOTCHA: **Switching models must `remove(KEY_MODEL_PATH)`** or the provisioner's persisted-path fast-path serves the OLD model file. The ref setter MUST mirror this.

### CONTROL_PANEL_UI (palette + row idioms to match)
```kotlin
// SOURCE: RelaisControlActivity.kt:82-88, 278-290, 297-318
private val Amber = Color(0xFFFFB000); private val Charcoal = Color(0xFF0B0B0D)
private val Panel = Color(0xFF16171A); private val Line = Color(0xFF2A2B30)
private val Paper = Color(0xFFEDEAE3); private val Muted = Color(0xFF8A8780)
@Composable private fun Readout(label: String, value: String) { /* label-left Muted, value-right Paper */ }
// ActionLink("…  ›") { } is the amber tap idiom (battery exemption, share connection).
```

### BOTTOM_SHEET_SELECT (modal + callback)
```kotlin
// SOURCE: ui/common/ModelPickerChip.kt:152-162
ModalBottomSheet(onDismissRequest = { showSheet = false }, sheetState = sheetState) {
  ModelPicker(task = task, modelManagerViewModel = vm, onModelSelected = { onModelSelected(prev, it) })
}
```
> Mirror the **shape** (chip → ModalBottomSheet → list → callback), but build a **self-contained** sheet — do NOT pull in `ModelManagerViewModel`/Hilt; the control panel is a standalone `ComponentActivity`.

### TEST_STRUCTURE (instrumented)
```kotlin
// SOURCE: androidTest/.../RelaisProvisionerTest.kt
@RunWith(AndroidJUnit4::class)
class RelaisXTest {
  @Test fun thing() {
    val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    val saved = RelaisConfig.modelId(ctx)        // targetContext == real prefs; snapshot + restore
    try { /* arrange/act/assert */ } finally { RelaisConfig.setModelId(ctx, saved) }
  }
}
```
> New files use the **AGPL-3.0 / Entrevoix** header; modified Google-origin files keep their Apache header.

---

## Files to Change

| File | Action | Justification |
|---|---|---|
| `data/RelaisModelRef.kt` | CREATE | Persisted descriptor (id, file, commitHash, sizeInBytes, displayName, source) — Gson-serializable |
| `RelaisConfig.kt` | UPDATE | `modelRef()`/`setModelRef()` (plaintext JSON, KEY_MODEL_REF); clear `KEY_MODEL_PATH` on change |
| `RelaisModelProvisioner.kt` | UPDATE | `resolveModel` builds `Model` from a ref when present; allowlist match only as fallback |
| `RelaisModelCatalog.kt` | CREATE | Curated source: fetch allowlist, filter to node-runnable LLM models, map `AllowedModel→RelaisModelRef` + display rows |
| `RelaisHuggingFace.kt` | CREATE | HF API client: `search(query)`, `resolve(modelId)`→`RelaisModelRef`; Gson DTOs; Bearer-aware GET |
| `ui/RelaisModelSelector.kt` | CREATE | The bottom-sheet selector composable (curated + HF search + paste-id), `DESIGN.md`-styled |
| `RelaisControlActivity.kt` | UPDATE | Replace MODEL ID `OutlinedTextField` with the tappable MODEL row that opens the selector |
| `androidTest/.../RelaisModelCatalogTest.kt` | CREATE | Allowlist parse + runnable filter + `AllowedModel→ref` mapping |
| `androidTest/.../RelaisHuggingFaceTest.kt` | CREATE | DTO parse + `.litertlm` sibling pick + size extraction (fixture JSON; network test `@Ignore`-able) |
| `androidTest/.../RelaisModelRefProvisionTest.kt` | CREATE | ref persisted → `resolveModel` builds the right `Model` (no allowlist needed) |

## NOT Building
- **No Gallery `ModelManagerViewModel`/Hilt dependency** in the control panel (self-contained fetch instead).
- **No in-app HF OAuth** — gated models still rely on a pre-set `RelaisConfig.hfToken` (existing behavior).
- **No model download UI / progress in the selector** — provisioning stays on node **Start** (selector only chooses; "Restart to apply" note unchanged).
- **No multi-model management / deletion / disk cleanup** UI.
- **No non-LiteRT-LM models** (AICore, classical ML) — filtered out.
- **No changes to the inference/serving path** (`RelaisEngine`, HTTP servers) — they consume the on-disk path only.
- **No allowlist JSON edits** (we read it; we don't curate it here).

---

## Step-by-Step Tasks

### Phase A

#### Task A1: `RelaisModelRef` descriptor
- **ACTION**: Create `data/RelaisModelRef.kt`.
- **IMPLEMENT**: `data class RelaisModelRef(val modelId: String, val modelFile: String, val commitHash: String, val sizeInBytes: Long, val displayName: String, val source: String)`. `source` ∈ {"allowlist","huggingface"}. Add `companion object { fun fromJson(s: String?): RelaisModelRef? = s?.let { runCatching { Gson().fromJson(it, RelaisModelRef::class.java) }.getOrNull() } }` and `fun toJson(): String = Gson().toJson(this)`.
- **MIRROR**: Gson usage from `common/Utils.kt`.
- **IMPORTS**: `com.google.gson.Gson`.
- **GOTCHA**: Keep it a plain data class (no Android types) so it's trivially unit-testable.
- **VALIDATE**: `./gradlew :app:compileDebugKotlin -x lint --offline`.

#### Task A2: `RelaisConfig.modelRef`
- **ACTION**: Add `KEY_MODEL_REF = "model_ref"` + `modelRef(context): RelaisModelRef?` + `setModelRef(context, ref)` to `RelaisConfig`.
- **IMPLEMENT**: store `ref.toJson()` in plaintext prefs. In `setModelRef`, also `setModelId(context, ref.modelId)` (keeps the legacy field coherent) and — critically — **`prefs.edit().remove(KEY_MODEL_PATH)`** so the staged-path fast-path doesn't serve the previous model. Add `clearModelRef(context)`.
- **MIRROR**: `setModelId` (RelaisConfig.kt:134-142) including its `KEY_MODEL_PATH` invalidation.
- **IMPORTS**: existing.
- **GOTCHA**: `modelId` is non-secret → plaintext prefs (NOT the encrypted store). Don't touch `KEY_HF_TOKEN` handling.
- **VALIDATE**: compile; unit-assert round-trip in A-tests.

#### Task A3: Provisioner ref path
- **ACTION**: Update `RelaisModelProvisioner.resolveModel` (and only it) to prefer a persisted ref.
- **IMPLEMENT**:
  ```kotlin
  fun resolveModel(context: Context): Model {
    RelaisConfig.modelRef(context)?.let { ref -> return modelFromRef(ref).also { it.preProcess() } }
    // …existing allowlist fetch+match fallback (default model / legacy)…
  }
  private fun modelFromRef(ref: RelaisModelRef): Model =
    AllowedModel(name = ref.displayName, modelId = ref.modelId, modelFile = ref.modelFile,
                 commitHash = ref.commitHash, sizeInBytes = ref.sizeInBytes,
                 taskTypes = listOf(BuiltInTaskId.LLM_CHAT), runtimeType = RuntimeType.LITERT_LM,
                 /* defaults for the rest */).toModel()
  ```
  Verify the exact `AllowedModel` constructor required vs optional params (ModelAllowlist.kt:46-74) and fill required ones with sane defaults (description="", defaultConfig=default).
- **MIRROR**: `resolveModel` + `AllowedModel.toModel()`.
- **IMPORTS**: `cc.grepon.relais.data.{AllowedModel, RuntimeType}`, `cc.grepon.relais.data.BuiltInTaskId`.
- **GOTCHA**: Building `AllowedModel` directly may require many fields — if the constructor is unwieldy, instead construct a minimal `Model` directly (set `name`, `downloadFileName=modelFile`, `version=commitHash`, `url=resolve template`, `sizeInBytes`, `isLlm=true`) and call `preProcess()`. Confirm `Model.getPath`/`normalizedName` produce the same `{external}/{normalizedName}/{commitHash}/{file}` layout the downloader expects. The ref path must NOT fetch the allowlist (works offline + for non-allowlist HF models).
- **VALIDATE**: `RelaisModelRefProvisionTest` (Task A6): set a ref, assert `resolveModel` returns a `Model` whose `url`/`downloadFileName`/`version` match, with no network.

#### Task A4: `RelaisModelCatalog` (curated source)
- **ACTION**: Create `RelaisModelCatalog.kt` with `fun curatedModels(context): List<RelaisModelRef>` (blocking; call off main thread).
- **IMPLEMENT**: reuse the provisioner's allowlist URL (`$ALLOWLIST_BASE_URL/{version}.json`), `getJsonResponse<ModelAllowlist>(url)?.jsonObj?.models`, filter to **node-runnable**: `runtimeType == LITERT_LM && taskTypes.contains("llm_chat") && disabled != true`, map each to `RelaisModelRef(source="allowlist", displayName = name, modelFile, commitHash, sizeInBytes, modelId)`. Return `emptyList()` on offline (caller shows "offline — type an id").
- **MIRROR**: `resolveModel`'s fetch; `BuiltInTaskId.LLM_CHAT`.
- **IMPORTS**: `data.{ModelAllowlist, RuntimeType, BuiltInTaskId}`, `common.getJsonResponse`.
- **GOTCHA**: `runtimeType` is **optional** on `AllowedModel` (may be null) — treat null as not-runnable OR infer from `modelFile.endsWith(".litertlm")`. Decide and document (recommend: `runtimeType == LITERT_LM || modelFile.endsWith(".litertlm")`).
- **VALIDATE**: `RelaisModelCatalogTest` parses a committed allowlist fixture and asserts the filter.

#### Task A5: Selector UI + control-panel wiring (Phase-A subset: curated only)
- **ACTION**: Create `ui/RelaisModelSelector.kt` (a `ModalBottomSheet`) and replace the MODEL ID `OutlinedTextField` in `RelaisControlActivity` with a tappable MODEL row.
- **IMPLEMENT**: a `Readout`-style row `MODEL  <displayName>  ▸` that sets `showSheet = true`. Sheet shows a "CURATED" header + list rows (display name + size, amber check on the selected). Tap → `RelaisConfig.setModelRef(ctx, ref)`, set `savedNote = "Saved. Restart to apply."`, dismiss. Load `curatedModels` in a `LaunchedEffect(showSheet)` on `Dispatchers.IO` into a `mutableStateOf` list (the panel already uses `LaunchedEffect`/`delay`). Keep a collapsed "enter id manually" `OutlinedTextField` (writes `setModelId` only, ref-less) for power users / arbitrary ids → folds the legacy field in as "advanced".
- **MIRROR**: `ModelPickerChip` (modal shape), `Readout`/`ActionLink`/palette from `RelaisControlActivity`, `ModalBottomSheet` import already available via M3.
- **IMPORTS**: `androidx.compose.material3.{ModalBottomSheet, rememberModalBottomSheetState}`, `kotlinx.coroutines.{Dispatchers, withContext}`, `androidx.compose.runtime.rememberCoroutineScope`.
- **GOTCHA**: **Read `DESIGN.md` first** — amber-on-charcoal, monospace, StopRed reserved for Stop, 6dp radius. The sheet surface = `Panel` (#16171A). No network on the main thread. Don't animate beyond the existing beacon pulse.
- **VALIDATE**: `./gradlew :app:assembleDebug -x lint --offline`; on-device: open panel → MODEL row → sheet lists curated models → pick → Start → `logcat` shows the chosen `modelId` resolved (ref path), node serves it.

#### Task A6: Phase-A tests
- **ACTION**: Create `RelaisModelCatalogTest` + `RelaisModelRefProvisionTest`.
- **IMPLEMENT**: catalog test parses a fixture allowlist (or the committed `model_allowlists/1_0_15.json` shape) and asserts only LiteRT-LM chat models pass; provision test sets a ref via `RelaisConfig.setModelRef`, calls `RelaisModelProvisioner.resolveModel`, asserts `Model.url == "https://huggingface.co/<id>/resolve/<commit>/<file>?download=true"` and `downloadFileName/version` — snapshot/restore `modelId`+`modelRef`+`KEY_MODEL_PATH` in `finally`.
- **MIRROR**: `RelaisProvisionerTest` snapshot/restore discipline.
- **GOTCHA**: `targetContext` = real prefs; restore everything. `resolveModel` on the ref path must not hit the network (assert by running with airplane assumptions / no allowlist dependency).
- **VALIDATE**: `./gradlew :app:assembleDebugAndroidTest -x lint --offline`; run `:app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=cc.grepon.relais.RelaisModelRefProvisionTest` (NOTE: `connectedAndroidTest` **uninstalls the app** — wipes the staged model + key; only run when you're ready to re-stage/restart).

### Phase B

#### Task B1: Bearer-aware JSON GET
- **ACTION**: Add `getJsonResponseAuthed<T>(url, bearer: String?)` (in `common/Utils.kt` or a `RelaisHttp` helper) mirroring `getJsonResponse` but setting `Authorization: Bearer …` when token present.
- **MIRROR**: `getJsonResponse` (Utils.kt:74-99) + DownloadWorker Bearer injection (DownloadWorker.kt:134-136).
- **GOTCHA**: HF returns 401/403 for gated repos without a token — surface a clear "gated; set HF token" error, don't crash.
- **VALIDATE**: compile; unit-parse a fixture.

#### Task B2: `RelaisHuggingFace` client
- **ACTION**: Create `RelaisHuggingFace.kt` with `search(query, token): List<HfHit>` and `resolve(modelId, token): RelaisModelRef?`.
- **IMPLEMENT**:
  - DTOs (Gson, `@SerializedName` as needed): `HfHit(id, downloads, likes, gated)`, `HfInfo(sha, siblings: List<HfSibling>)`, `HfSibling(rfilename)`, `HfTreeEntry(path, size, lfs: HfLfs?)`, `HfLfs(size)`.
  - `search`: `GET /api/models?search={enc(query)}&limit=25&sort=downloads&direction=-1` (default query bias toward `litert` when blank, or `author=litert-community`). Returns hits (repo ids).
  - `resolve`: `GET /api/models/{id}` → `sha` + `siblings`; pick the `.litertlm` sibling (if several, prefer one whose name contains the lowest int-quant or just the first; document choice); `GET /api/models/{id}/tree/{sha}?recursive=true` → find that path's `lfs.size ?: size`; build `RelaisModelRef(modelId=id, modelFile=file, commitHash=sha, sizeInBytes=size, displayName = id.substringAfterLast('/'), source="huggingface")`. Return `null` (with a reason) if no `.litertlm` sibling.
- **MIRROR**: `getJsonResponse` shape; URL-encode the query.
- **IMPORTS**: `java.net.URLEncoder`, Gson.
- **GOTCHA**: (1) `siblings` from `/api/models/{id}` may need `?full=true` or `?expand[]=siblings` — verify against a live response; if absent, the tree endpoint alone lists files. (2) **File size**: confirm `lfs.size` vs `size` on a real `.litertlm` (multi-GB LFS) — pick whichever is the true byte count; wrong size only breaks the download **progress %**, not correctness (DownloadWorker streams to EOF). (3) No `library=litert-lm` tag is guaranteed — rely on sibling inspection, not a tag filter.
- **VALIDATE**: `RelaisHuggingFaceTest` parses committed fixture JSONs (models-list, model-info, tree) and asserts the picked file + size + commit. Mark any live-network test `@Ignore` (CI has no network for instrumented tests anyway).

#### Task B3: HF search section in the selector
- **ACTION**: Extend `RelaisModelSelector` with a "HUGGINGFACE" section: a search `OutlinedTextField` + results list + a "paste a repo id" affordance.
- **IMPLEMENT**: debounce the query (~400ms) in a `LaunchedEffect(query)`; run `RelaisHuggingFace.search` on `Dispatchers.IO`; show hits. On tap → `resolve(id, hfToken)` on IO; on success → `setModelRef` + "Restart to apply"; on failure → inline error (no `.litertlm` / gated / offline). Pass `RelaisConfig.hfToken(ctx)` for gated.
- **MIRROR**: Phase-A sheet; the `OutlinedTextField` styling already in `RelaisControlActivity` (monospace 14sp).
- **GOTCHA**: Resolve is 2 network round-trips — show a spinner row; never block the main thread. Keep result count modest (25).
- **VALIDATE**: on-device: search "gemma" → results → pick a `litert-community` repo → Start → node downloads + serves it (watch `logcat` `AGDownloadWorker` for the HF URL, then `Node up`).

#### Task B4: Phase-B polish + docs
- **ACTION**: Update README "Operating it as an appliance" / the model section to mention the selector + HF search; note gated models need the HF token.
- **VALIDATE**: docs build (markdown only).

---

## Testing Strategy

### Unit / Instrumented Tests
| Test | Input | Expected Output | Edge Case? |
|---|---|---|---|
| `RelaisModelRef` round-trip | ref → toJson → fromJson | equal ref | malformed JSON → null |
| Catalog filter | allowlist fixture | only `LITERT_LM`+`llm_chat`, `disabled!=true` | null `runtimeType`; empty allowlist |
| Catalog offline | unreachable URL | `emptyList()` (no crash) | ✓ |
| Ref provision | ref set in prefs | `resolveModel` builds matching `Model`, **no network** | ref + default coexist |
| HF parse | fixture models-list/info/tree | hits; picked `.litertlm`; correct size+commit | repo with no `.litertlm` → null; gated flag |
| HF resolve auth | gated repo, no token | clear error, no crash | 401/403 |
| Provisioner switch | change ref | `KEY_MODEL_PATH` cleared | old model not re-served |

### Edge Cases Checklist
- [ ] Empty HF query (default to curated-org bias, don't spam the API)
- [ ] HF repo with **multiple** `.litertlm` files (deterministic pick + documented)
- [ ] HF repo with **no** `.litertlm` (graceful "incompatible")
- [ ] Gated repo without token (clear message → set HF token)
- [ ] Offline (allowlist + HF both unreachable → keep last selection + manual-id fallback)
- [ ] Model switch clears the staged-path fast-path (no stale model)
- [ ] adb `--es modelId` still works (legacy path, no ref)
- [ ] Selector renders correctly per `DESIGN.md` (amber/charcoal, both folded states)

---

## Validation Commands

### Static / compile (the local lever — see project memory)
```bash
cd Android/src && ./gradlew :app:compileDebugKotlin -x lint --offline
```
EXPECT: BUILD SUCCESSFUL, zero errors (warnings re: context-receivers/LocalClipboardManager are pre-existing).

### Full debug build (manifest merge)
```bash
cd Android/src && ./gradlew :app:assembleDebug -x lint --offline
```
EXPECT: BUILD SUCCESSFUL.

### Instrumented test compile
```bash
cd Android/src && ./gradlew :app:assembleDebugAndroidTest -x lint --offline
```
EXPECT: BUILD SUCCESSFUL.

### On-device tests (Pixel 9 Pro Fold; UTP artifact cached)
```bash
cd Android/src && ./gradlew :app:connectedDebugAndroidTest -x lint --offline \
  -Pandroid.testInstrumentationRunnerArguments.class=cc.grepon.relais.RelaisModelRefProvisionTest
```
EXPECT: all pass. ⚠️ This UNINSTALLS the app (wipes staged model + API key) — re-stage from `/data/local/tmp/relais/…` + `chmod 0644`/`0755`, recover the new key (temp-log method), restart.

### Manual on-device validation
- [ ] Panel → MODEL row → sheet → CURATED list populated from the live allowlist
- [ ] Pick a curated model → Start → `logcat -s RelaisModelProvisioner` shows it resolving via the ref (no "not found in allowlist")
- [ ] HF search "gemma" → results → pick → Start → downloads from the HF resolve URL → `Node up` → `curl -k https://localhost:8443/v1/chat/completions` returns
- [ ] Switch back to default → no stale-model serve (`KEY_MODEL_PATH` cleared)

---

## Acceptance Criteria
- [ ] Free-text MODEL ID replaced by a tap-to-open selector (curated + HF search), with a manual-id fallback
- [ ] Curated list shows only node-runnable (`LITERT_LM` + `llm_chat`) models from the allowlist
- [ ] HF search resolves an arbitrary `.litertlm` repo to full download metadata and the node provisions it
- [ ] Selecting a model persists a `RelaisModelRef` and clears the staged-path cache
- [ ] All validation commands pass; new tests written and passing on-device
- [ ] UI matches `DESIGN.md`
- [ ] adb `--es modelId` automation unaffected

## Completion Checklist
- [ ] Follows discovered patterns (getJsonResponse, AllowedModel.toModel, prefs idioms, Readout/ActionLink)
- [ ] Errors handled (offline, gated, no-.litertlm) with user-facing messages
- [ ] Logging via existing `Log.i/w(TAG, …)` (no secret/token leakage)
- [ ] Tests follow the instrumented snapshot/restore idiom + AGPL header on new files
- [ ] No hardcoded model ids beyond `DEFAULT_MODEL_ID`
- [ ] README updated (Phase B)
- [ ] No scope creep (see NOT Building)
- [ ] Self-contained — no codebase search needed during implementation

## Risks
| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| HF `tree` size field (`size` vs `lfs.size`) wrong | Med | Low (only progress %) | Verify against a real `.litertlm` response in B2; default to `lfs.size ?: size` |
| `AllowedModel` constructor too heavy to build from a ref | Med | Med | Fallback: construct a minimal `Model` directly (Task A3 GOTCHA) |
| Control panel gains network/coroutines it didn't have | Med | Med | All I/O on `Dispatchers.IO`; mirror the existing `LaunchedEffect` loop; no main-thread network |
| `connectedAndroidTest` wipes device node state | High | Med | Run only when ready to re-stage/restart; documented recovery in project memory |
| HF search surfaces incompatible repos | Med | Low | Resolve-on-tap validates `.litertlm`; clear "incompatible" message |
| Gated model selected without token | Med | Low | Detect `gated`; message → set HF token (existing field) |
| DESIGN.md drift in the new sheet | Med | Med | Read DESIGN.md first; reuse palette constants + Readout/ActionLink |

## Notes
- **Why a ref, not just an id:** `resolveModel` currently *requires* an allowlist match. A persisted `RelaisModelRef` lets the node provision **any** resolved model (curated or HF) offline and without the allowlist — the minimal architectural change that unlocks HF.
- **Decoupling from the Gallery VM** keeps the headless control panel light and avoids dragging Hilt/DataStore into `RelaisControlActivity`.
- **Confidence: 7/10** for single-pass — Phase A is high-confidence (well-trodden patterns); Phase B's HF API field details (`siblings` expansion, `tree` size) carry the residual risk and should be fixture-verified early.
