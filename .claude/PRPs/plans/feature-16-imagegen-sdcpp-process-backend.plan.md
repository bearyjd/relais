# Feature #16 — sd.cpp image-gen backend (process-isolated `:imagegen`)

**Date:** 2026-06-21 · **Status:** BLUEPRINT (not started) · **Builds on:** `docs/images-generations-api.md` (the verdict), the #63 honest-501 scaffold (merged), #64 MediaPipe revert (#66, merged).

Turns `POST /v1/images/generations` from an honest 501 into a working endpoint by building the
**only proven-stable** on-device image-gen design: a disposable `:imagegen` OS process that loads the
model, does exactly ONE generate, writes the PNG, and dies. Multi-PR effort.

---

## Objective & the verdict it rests on

LiteRT-LM is text-out only; MediaPipe is a dead end (protobuf-javalite wall, reverted in #66). The
only engine that ran on-device is **stable-diffusion.cpp** via the `io.github.aatricks:llmedge:0.3.9`
AAR (its `libsdcpp.so` ships with Vulkan). On the G5 it produced a valid 512×512 image, coexisted
with the resident E2B LLM (no OOM), but **every in-process operation past the first generate broke**:
- reuse (2nd generate, same client) → **SIGSEGV** in `libsdcpp.so`
- fresh client per image in one process → **deadlock**
- the `LLMEdge` facade path → **hangs on the first generate**

The ONLY stable primitive: a direct `ImageClient.create(ctx, scope)` doing **exactly one** generate
(~90 s, succeeded ~5×). So the design is **stable by construction**: every image = a fresh process's
first-and-only generate, and any native crash/hang is contained to a disposable process the node can
kill — the node never dies.

## Non-negotiables (design invariants)

1. **One generate per process, ever.** The `:imagegen` process loads → generates ONCE → is killed.
   Never reuse a context, never close+recreate in-process, never touch the `LLMEdge` facade — use
   `ImageClient.create(...)` directly.
2. **A crash/hang in `:imagegen` must never affect the node.** The node (main process) treats the
   `:imagegen` process as disposable and owns a hard watchdog that kills it.
3. **Behind the shipped `RelaisImageGenerator` interface (#63).** No new public surface; the endpoint
   already gates on `RelaisImageGeneratorProvider`.
4. **Full flavor only.** llmedge pulls onnxruntime/mlkit/play-services transitives → it would break
   the degoogled `GMS=0` dex gate. `fullImplementation(llmedge)`; degoogled keeps a stub → 501.
   **Never compromise `full` for `degoogled` or vice-versa.**
5. **Honest states preserved:** 501 (no backend / Vulkan unavailable), 503 (provisioning), 200 (PNG).

---

## Architecture

```
main process (node)                          :imagegen process (android:process=":imagegen")
─────────────────────                        ───────────────────────────────────────────────
POST /v1/images/generations
  → RelaisImageGeneratorProvider.get()
  → SdcppImageGenerator.generate()  ──bind──▶ ImageGenService (Service, separate process)
       (the #63 interface impl, full)            → ImageClient.create(ctx, scope)
       sends {prompt,w,h,steps,seed,cfg}          → generate(ImageGenerationRequest(...,             
       starts a hard watchdog timer                   model=ModelSpec.localFile(gguf)))  : Bitmap
                                                   → Bitmap → PNG bytes → write to cache file
  ◀──── returns PNG file path (binder) ────────  ← reply {pngPath} via Messenger/callback
  reads PNG, base64s into the OpenAI response
  → kills the :imagegen process (Process.killProcess)   [process dies — fresh one next request]
```

- **Why a separate `android:process`, not a separate app:** OS-process isolation (crash containment)
  is exactly what `android:process=":imagegen"` gives — same APK/UID/files dir, shared model storage +
  config, simple in-package binder. A separate app would add cross-app IPC + duplicated state for zero
  extra isolation. (Decision recorded; see the chat rationale.)
- **PNG handoff = file, not binder payload.** A 512×512 PNG can exceed the ~1 MB binder transaction
  limit. The service writes the PNG to `cacheDir/imagegen/<uuid>.png` and returns the path; the node
  reads + deletes it. Only small control data crosses the binder.
- **Kill-after-generate (bulletproof):** the node, on reply OR on watchdog timeout (e.g. 180 s),
  calls `android.os.Process.killProcess(pid)` for the `:imagegen` pid (obtained from
  `RunningAppProcessInfo` by processName, or returned in the bind handshake). Belt-and-suspenders: the
  service also self-kills after returning. The watchdog guarantees a hung generate can't wedge the node.

---

## Decisions (resolved 2026-06-21)

| # | Decision | Resolution |
|---|----------|------------|
| D1 | Default model | **SD-Turbo** (cfg=1, steps=4, ~90 s) for v1. Operator WILL want a quality upgrade → build the provisioner + config as a **swappable named-model set** (turbo default / sd15 quality / custom URL), each SHA-pinned, with per-model step/cfg defaults — so upgrading is a `RelaisConfig` flip, not a rebuild. |
| D2 | Model hosting | **Operator's own HuggingFace repo, SHA-pinned**, fetched via the embedder's `resolve/main` path (free CDN; `streamTo` already handles HF's 302→signed-CDN redirect — `ImageModelProvisioner` is a near-copy of `EmbeddingModelProvisioner`). The converted gguf isn't drop-in public (had to be built via convert.py), so self-host it; drop the SD-1.5 gguf in the same repo at upgrade time. Operator-set URL override for self-hosting. ⚠️ **License:** SD-Turbo = Stability **non-commercial research** license; SD-1.5 = OpenRAIL-M (more redistribution-friendly) — the quality upgrade is also the cleaner-licensed one. Fine for a self-hosted research tool; matters if Relais ever ships commercially. |
| D3 | `n>1` batch cap | **n ≤ 2** — sequential fresh processes (~2–3 min worst case; document the wall-clock). |
| D4 | Latency | **Sync** for v1 with a documented long read-timeout; async job pattern only if clients time out. |

---

## PR breakdown

### PR-0 — dependency audit ✅ DONE 2026-06-21 (findings below)
Added `io.github.aatricks:llmedge:0.3.9` (jitpack repo) as `fullImplementation`; resolved both
runtime classpaths. **Results:**
- **Isolation CONFIRMED, full-only REQUIRED.** `fullImplementation(llmedge)` → degoogled classpath has
  **0** llmedge / mlkit / play-services / gms. And full-only is necessary: llmedge transitively pulls
  `com.google.mlkit:text-recognition` + `com.google.mlkit:image-labeling` → `com.google.android.gms:
  play-services-base/basement/tasks` — these WOULD break the degoogled GMS=0 gate. (The verdict doc's
  "ships in both flavors" line is wrong for llmedge.)
- **Both flavors build green** with the dep (`testFullDebugUnitTest` + `testDegoogledDebugUnitTest`).
- **ktor straggler (minor, excludable — CORRECTED):** Relais is ALREADY on ktor **3.4.3**
  (`libs.versions.toml`), so there is NO bump to Relais's own code — the MCP client / webhooks are
  fine. llmedge's declared ktor 2.3.12 got upgraded to 3.4.3 by conflict resolution, EXCEPT
  `ktor-client-content-negotiation:2.3.12` (llmedge's HF-download transitive, which Relais doesn't use)
  stays at 2.x — inert today. Exclude llmedge's `io.ktor` in PR-A to remove it. (My earlier "forces a
  2→3 bump" was wrong — verified `ktor = "3.4.3"`.)
- **Bloat from unused llmedge features:** the mlkit (image-labeling), `onnxruntime` (via
  `io.gitlab.shubham0204:sentence-embeddings`), and ktor (HF-download) transitives come from llmedge
  features image-gen DOESN'T use (we provision via `ModelSpec.localFile`, have our own embedder).
- **Verdict:** do NOT merge the raw dep — fold the wiring into PR-A WITH excludes (see PR-A). Manifest
  `uses-native-library libvulkan.so` (full) also lands in PR-A.

### PR-A — dep wiring (with excludes) + the `:imagegen` service + IPC + one-generate lifecycle (full flavor, NOT yet wired to endpoint)
**Status: BUILT 2026-06-21 on branch `feat/relais-imagegen-service` (JVM/compile-green + reviewed; ON-DEVICE gate pending).** Files: `settings.gradle.kts` (scoped JitPack repo), `libs.versions.toml` (`llmedge = 0.3.9`), `app/build.gradle.kts` (`fullImplementation(libs.llmedge)` + excludes `sentence-embeddings`/`io.ktor`), `src/full/AndroidManifest.xml` (`<service :imagegen exported=false>` + `uses-native-library libvulkan.so`), `src/full/.../imagegen/ImageGenIpc.kt` (Messenger contract), `src/full/.../imagegen/ImageGenService.kt` (the service), `src/androidTestFull/.../imagegen/ImageGenServiceProbe.kt` (3 tests). **Verified here:** full compile + androidTest compile vs. the real llmedge API (`ImageClient.create(ctx,scope)` + `ImageGenerationRequest(...)` named-args + `ModelSpec.localFile(File)` — javap-confirmed from the cached AAR); full + degoogled JVM unit suites green; **degoogled runtime classpath GMS-leak scan EMPTY** (0 llmedge/mlkit/play-services/onnxruntime). **code-reviewer: COMMENT (0 CRIT/0 HIGH at high-confidence).** Folded in: orphan-PNG cleanup on reply failure, a wall-clock hang-guard self-kill (native loads aren't Kotlin-cancellable, so `withTimeout` won't help), deliberate no-`close()`/no-facade comments, prompt elided from logs, accurate excludes comment, + error-path & second-generate-rejection probe tests (these two need NO model/Vulkan). **Deferred to PR-C (documented in code):** model-path allow-list root (probe needs `/data/local/tmp`; root is PR-B's provisioner) + request-bounds validation (endpoint `IMAGE_GEN_LIMITS` covers it). **STILL OWED = the on-device gate:** run `ImageGenServiceProbe` on a Vulkan device with a pushed GGUF → bind → one 512×512 PNG → process exits (the two control tests run on any device). NOT pushed / no PR opened yet.

Original scope:
- **Dep wiring (carried from PR-0, now WITH excludes):** `fullImplementation(libs.llmedge)` + the
  jitpack repo + manifest `uses-native-library libvulkan.so` (full). Add excludes for llmedge features
  image-gen doesn't use: `exclude(group="io.gitlab.shubham0204", module="sentence-embeddings")` (drops
  onnxruntime). **Clean the ktor straggler:** exclude llmedge's `io.ktor` (removes the inert
  `content-negotiation:2.3.12` + llmedge's unused HF-download path; Relais's own ktor is already 3.4.3,
  no migration). **Verify image-gen still generates on-device with the excludes
  applied** before trusting them (a wrong exclude → NoClassDefFoundError in the `:imagegen` process).
- `src/full/.../imagegen/ImageGenService.kt`: `Service` declared `android:process=":imagegen"`,
  exported=false. Bound (Messenger or AIDL). Handles exactly one `Generate(prompt,w,h,steps,seed,cfg)`
  → `ImageClient.create(ctx, scope)` → `generate(ImageGenerationRequest(...))` → Bitmap → PNG →
  `cacheDir/imagegen/<uuid>.png` → reply `{pngPath}` → self-kill.
- The binder contract (Parcelable request + reply, or AIDL). Keep it tiny (control only; PNG via file).
- Manifest `<service android:name=".imagegen.ImageGenService" android:process=":imagegen" android:exported="false"/>`.
- **Gate: a full-flavor instrumentation test can bind the service, get a PNG file back for a pushed gguf, and the process exits.** Not yet reachable from the endpoint.

### PR-B — model provisioning + hosting
- `imagegen/ImageModelProvisioner.kt` mirroring `EmbeddingModelProvisioner` (`ensure`/`isProvisioned`/
  `streamTo`, SHA-pin, reuse-if-complete, atomic finalize). No HF token needed (open weights; HF only
  as a CDN). Default = SD-Turbo gguf on the operator's HF repo (D2).
- **Swappable named-model set** (D1): an `ImageModel` registry — `turbo` (steps=4, cfg=1, default),
  `sd15` (steps=20, cfg=7, quality), `custom` (operator URL) — each with `{url, sha256, steps, cfg}`.
  Selected via a `RelaisConfig` key (mirrors the LLM model-id setting), so a quality upgrade is a
  config flip. The request's `x_relais_steps` still clamps within the chosen model's range.
- **Gate: provisions the gguf to disk on a device; isProvisioned true after.**

### PR-C — the full-flavor `RelaisImageGenerator` impl + registration + endpoint flip
- `src/full/.../imagegen/SdcppImageGenerator.kt : RelaisImageGenerator`: `isAvailable` = Vulkan
  available (`LLMEdge.isVulkanAvailable()`) AND model provisioned. `generate()` binds `ImageGenService`,
  dispatches one generate, waits with `shouldCancel` (thermal) + a hard timeout, reads+deletes the PNG,
  kills the `:imagegen` process, returns PNG bytes.
- `src/degoogled/.../imagegen/SdcppImageGenerator.kt`: stub → `isAvailable=false` (→ 501).
- Register the full impl into `RelaisImageGeneratorProvider` at node init (mirror the embedder's
  `register()` in `RelaisNodeService.onCreate`, full flavor only).
- Endpoint: add the **503-while-provisioning** branch (mirror embeddings: not-ready + can-provision →
  `ensureProvisioningStarted` + 503 Retry-After; not-ready + can't → 501; ready → 200 b64 PNG). Tighten
  `IMAGE_GEN_LIMITS` per D3. Map thermal-cancel → 503 (the `TODO(#16 route follow-up)` from #63).
- **Gate: full flavor — endpoint 501→503→200 on-device; degoogled stays 501. Both flavor unit suites green; degoogled dex GMS=0.**

### PR-D — on-device validation + stability proof
- On the G5 (rango) + ideally G3 (husky) / G4: provision → `POST /v1/images/generations` → valid
  512×512 PNG. **Stability proof:** 5+ sequential images, EACH in a fresh `:imagegen` process, node
  survives all (no SIGSEGV/deadlock/hang reaching the node). Force a mid-generate crash/hang → confirm
  the watchdog kills `:imagegen` and the node + LLM stay up. Measure latency + peak RAM (coexistence
  with the resident LLM). Confirm Vulkan-unavailable → clean 501.
- Throwaway probe (recreate from the llmedge API notes in the handoff); not committed.

---

## Risks & mitigations
- **Kill-after-generate must be airtight** — a leaked `:imagegen` process = wasted RAM / a second
  generate path. Watchdog + self-kill + verify the pid is gone. (Highest-risk invariant.)
- **Latency (~60–90 s)** vs OpenAI client read-timeouts — document; consider async later (D4).
- **llmedge transitives in `full`** (onnxruntime/mlkit/play-services) — accepted in full; PR-0 proves
  they never reach degoogled. Footprint note: re-introduces guava etc. Excludable (PR-A) since they
  come from unused llmedge features.
- **ktor straggler (PR-0 finding, minor)** — Relais is already on ktor 3.4.3, so no migration of its
  code; llmedge only adds an inert `content-negotiation:2.3.12` (its unused HF-download transitive).
  Exclude llmedge's `io.ktor` in PR-A.
- **Model hosting/bandwidth** — gguf is 1.5–1.9 GB; operator-set URL + SHA pin; reuse-if-complete.
- **Vulkan availability** — `isAvailable` gates on `LLMEdge.isVulkanAvailable()`; non-Vulkan devices
  get a clean 501, never a crash.
- **Shader-compile/VAE fixed overhead (~50–70 s)** dominates — few-step models don't rescue latency;
  set expectations, don't chase step-count tuning for speed.

## llmedge API reference (javap'd, from the verdict investigation)
`ImageClient.create(ctx, scope, config?)` (the stable direct path; the `.image`/`LLMEdge` facade
hangs). `imageClient.generate(ImageGenerationRequest(prompt, width, height, steps, seed: Long,
flashAttention, cfgScale, model = ModelSpec.localFile(File)), cont): Bitmap` (suspend).
`ModelSpec.localFile(File)`. `LLMEdgeConfig().copy(image = ImageRuntimeConfig(RuntimeCacheConfig(
maxEntries, maxMemoryMb), preferPerformanceMode))`. Statics: `LLMEdge.isVulkanAvailable()`,
`getVulkanDeviceInfo()`, `getImageBackendAvailability()`. SD-Turbo: cfg=1, steps=4.

## Out of scope (v1)
Multi-size output (fixed 512×512), async job API, SoC-NPU acceleration, the degoogled real backend,
SD-1.5 quality mode as default.
