# Plan: On-device image generation — `POST /v1/images/generations` (#16)

## Summary
Add on-device text-to-image generation to Relais via Google's **MediaPipe Image Generator**
(Stable Diffusion v1.5), exposed as an OpenAI-compatible `POST /v1/images/generations` endpoint that
returns base64 PNGs. This is a **separate runtime** from the LiteRT-LM text engine — it mirrors the #6
embeddings architecture exactly (separate model interface + provider singleton + demand-driven
501/503/200 lifecycle + on-disk provisioning + a flavor seam). It is **not** a model you load into the
existing node.

## User Story
As a LAN client of a Relais node, I want to `POST /v1/images/generations` with a prompt and get an
image back, so that my on-device node can create images offline like an OpenAI-compatible image API.

## Problem → Solution
Relais is text-out only (LiteRT-LM) → add a parallel diffusion runtime + image endpoint, behind the
same opt-in/provisioning posture as embeddings, without touching the LLM engine.

## Metadata
- **Complexity**: **Large** (new subsystem, new dependency, model provisioning, flavor seam, new endpoint)
- **Source PRD**: deep-research report (this session) + #6 embeddings as the architectural precedent
- **PRD Phase**: N/A
- **Estimated Files**: ~10 (imagegen/ package ×3–4, full/degoogled stubs, HttpServer, Metrics, manifest, gradle, docs)

---

## ⚠️ Reality check (from the deep research — read before building)
- **Only viable on-device path is MediaPipe Image Generator (SD v1.5).** It "supports any models that
  **exactly match the Stable Diffusion v1.5 architecture**" and runs **~15 s/image on high-end
  devices**, GPU/OpenCL. Dependency: `com.google.mediapipe:tasks-vision-image-generator`.
- **It is officially "no longer actively maintained."** Accept this dependency risk up front.
- **SDXL/Flux are not mobile-practical**; MobileDiffusion (0.5 s) **was never released**. SD 1.5 is the ceiling.
- **The model is too large to bundle** — host the MediaPipe-converted SD-1.5 bundle and provision it on
  first use (like the embedding model, but SD 1.5 is **open** → likely **no HF token needed**).
- **The Tensor G5 can run it**, but the heavy open question is **memory**: the node already holds a
  multi-GB resident LLM; loading SD 1.5 (UNet + VAE + text encoder) alongside it risks OOM. See Risks.

---

## UX / API design

### Request (OpenAI-compatible)
```
POST /v1/images/generations
Authorization: Bearer <api key>
{ "prompt": "a red bicycle on a beach", "n": 1, "size": "512x512",
  "response_format": "b64_json", "x_relais_steps": 20, "seed": 42 }
```
- `size`: SD 1.5 is fixed 512×512 in MediaPipe — accept `512x512` (and `"256x256"` if supported);
  reject others with `400`.
- `n`: loop the generator (each iteration ~15 s); cap at a small `MAX_IMAGES` (e.g. 4) to bound time/heat.
- `x_relais_steps`: iteration count (default ~20; clamp). `seed` optional.
- `response_format`: only `b64_json` supported (no hosted URLs on a LAN node); default to it.

### Response
```json
{ "created": 1701339600, "data": [ { "b64_json": "<PNG base64>" } ] }
```

### Status codes (mirror /v1/embeddings exactly)
- **501** — image-gen model not provisioned and can't provision (e.g. degoogled stub, or no network).
- **503 + Retry-After** — model is provisioning in the background; retry shortly.
- **200** — generated.
- **400** — bad prompt/size/n. **503** — thermal shed (`shedIfHot`).

---

## Mandatory Reading

| Priority | File | Lines | Why |
|---|---|---|---|
| P0 | `embed/RelaisEmbedder.kt` | 22–127 | THE template: interface + `RelaisEmbedderProvider` singleton + demand-driven `canProvision`/`ensureProvisioningStarted`/`warmIfProvisioned`/background-thread load |
| P0 | `embed/EmbeddingModelProvisioner.kt` | 36–95 | Download-to-`getExternalFilesDir/relais/...`, reuse-if-complete, progress, token gate (image-gen drops the token, SD 1.5 is open) |
| P0 | `RelaisHttpServer.kt` | 272–286, 395–451, 1480–1500 | Auth/body-cap gate; the `/v1/embeddings` 501/503/200 handler to clone; the response-envelope builder |
| P0 | `Android/src/app/build.gradle.kts` | 65–87, 184–188 | `productFlavors { full / degoogled }` + `"fullImplementation"(...)` for GMS deps |
| P1 | `src/full/.../share/ImageTextRecognizer.kt` + `src/degoogled/.../share/ImageTextRecognizer.kt` | all | The flavor-seam pattern (real impl vs no-op stub) to mirror for `imagegen/` IF MediaPipe pulls Play-Services |
| P1 | `RelaisMetrics.kt` | 241–257, 113–124, 198–207 | `endpointLabel` whitelist (add the path), `recordRequest`, `recordEndpointLatency` |
| P1 | `AndroidManifest.xml` | 349–352 | `<uses-native-library libOpenCL.so required=false>` is **already declared** — MediaPipe's GPU need is likely covered |
| P2 | `docs/embeddings-api.md` | all | The API-doc shape to mirror in `docs/images-generations-api.md` |

## External Documentation
| Topic | Source | Takeaway |
|---|---|---|
| MediaPipe Image Generator (Android) | developers.google.com/edge/mediapipe/solutions/vision/image_generator/android | SD v1.5 only; `convert.py` to convert; OpenCL native libs; dependency `com.google.mediapipe:tasks-vision-image-generator`; "no longer actively maintained" |
| SD 1.5 base | huggingface.co/stable-diffusion-v1-5/stable-diffusion-v1-5 (EMA-only) | the exact foundation the converter expects |
| Perf | dev.to / Google blog | ~15 s/image high-end; iterations configurable |

---

## Patterns to Mirror

### MODEL_INTERFACE + PROVIDER  (SOURCE: embed/RelaisEmbedder.kt:22-51)
```kotlin
interface RelaisEmbedder { val dim: Int; fun isAvailable(context: Context): Boolean; fun embed(...): ... }
object RelaisEmbedderProvider { @Volatile private var impl: RelaisEmbedder? = null
  fun register(e: RelaisEmbedder?) { impl = e }; fun get(): RelaisEmbedder? = impl }
```
→ `RelaisImageGenerator` interface + `RelaisImageGeneratorProvider` singleton, identical shape.

### DEMAND-DRIVEN LIFECYCLE  (SOURCE: embed/RelaisEmbedder.kt:54-127)
```kotlin
override fun isAvailable(context: Context) = loaded != null
fun canProvision(context: Context) = !integrityBudgetExhausted() /* && network ok; NO hf token for SD1.5 */
fun ensureProvisioningStarted(context: Context) { if (loaded != null || !canProvision(app)) return; startBackgroundLoad(app) }
private fun startBackgroundLoad(app: Context) { if (!provisioning.compareAndSet(false,true)) return; thread { try { ensureLoaded(app) } finally { provisioning.set(false) } } }
```

### ENDPOINT 501/503/200  (SOURCE: RelaisHttpServer.kt:419-434)
```kotlin
val gen = RelaisImageGeneratorProvider.get()
if (gen == null || !gen.isAvailable(context)) {
  if (gen is MediaPipeImageGenerator && gen.canProvision(context)) {
    gen.ensureProvisioningStarted(context)
    reply(503, buildImagesError("image model is provisioning; retry shortly", "service_unavailable"), listOf("Retry-After: 30"))
  } else reply(501, buildImagesError("image model not provisioned", "not_implemented"))
} else { /* generate → 200 */ }
```

### RESPONSE ENVELOPE  (SOURCE: RelaisHttpServer.kt:1480-1500 + base64 at :1249)
```kotlin
internal fun buildImagesResponse(pngs: List<ByteArray>, createdSec: Long): JSONObject {
  val data = JSONArray()
  pngs.forEach { data.put(JSONObject().put("b64_json", android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP))) }
  return JSONObject().put("created", createdSec).put("data", data)
}
```

### FLAVOR SEAM (only if MediaPipe pulls Play-Services — see Task 0)  (SOURCE: src/{full,degoogled}/.../ImageTextRecognizer.kt)
- `src/full/.../imagegen/ImageGenBackend.kt` — real MediaPipe impl.
- `src/degoogled/.../imagegen/ImageGenBackend.kt` — stub: `isAvailable=false`, `canProvision=false` → endpoint stays 501.

---

## Files to Change
| File | Action | Why |
|---|---|---|
| `imagegen/RelaisImageGenerator.kt` | CREATE | interface + `RelaisImageGeneratorProvider` singleton |
| `imagegen/MediaPipeImageGenerator.kt` (or `src/full/`) | CREATE | MediaPipe `ImageGenerator` impl: load bundle, `generate(prompt, steps, seed) -> Bitmap -> PNG` |
| `imagegen/ImageModelProvisioner.kt` | CREATE | download/host the converted SD-1.5 bundle → `getExternalFilesDir/relais/imagegen/`; reuse-if-complete; progress |
| ~~`src/degoogled/.../imagegen/...`~~ | ❌ NOT NEEDED | Task 0: MediaPipe is GMS-free → no stub; `imagegen/` ships in `src/main/` for both flavors |
| `RelaisHttpServer.kt` | UPDATE | register `POST /v1/images/generations`; parse/validate; 501/503/200; `buildImagesResponse`/`buildImagesError` |
| `RelaisMetrics.kt` | UPDATE | add `/v1/images/generations` to `endpointLabel`; optional `relais_images_generated_total` counter |
| `RelaisApplication` startup (warm hook) | UPDATE | `warmIfProvisioned` on boot (mirror embedder warm) |
| `Android/src/app/build.gradle.kts` + `libs.versions.toml` | UPDATE | add `com.google.mediapipe:tasks-vision-image-generator:0.10.26.1` as **`implementation(...)`** (Task 0: GMS-free, both flavors). Add a `mediapipe-image-generator` catalog entry mirroring the `mlkit-*` style |
| `AndroidManifest.xml` | UPDATE (maybe) | OpenCL native-lib already present; add any MediaPipe-specific `uses-native-library` if required |
| `docs/images-generations-api.md` | CREATE | API + status codes + provisioning + the unmaintained-dep + memory caveats |

## NOT Building
- img2img / inpainting / outpainting / image editing (text-to-image only, v1).
- ControlNet-style plugins or LoRA (the MediaPipe extras) — v2.
- Hosted-URL `response_format` (LAN node returns base64 only).
- SDXL/Flux (not mobile-practical) or image **output from the LLM** (impossible — LiteRT-LM is text-out).
- Bundling weights in the APK (too large — provision on demand).

---

## Step-by-Step Tasks

### Task 0: Verify the GMS/flavor question FIRST (gates the architecture) — ✅ DONE 2026-06-20
- **ACTION**: Add `com.google.mediapipe:tasks-vision-image-generator` to a scratch build; inspect transitive deps + the degoogled dex gate.
- **IMPLEMENT**: `./gradlew :app:dependencies` and check for `com.google.android.gms` / `play-services`. The degoogled CI gate blocks `Lcom/google/(android/(gms|apps/aicore)|mlkit)` — note `com.google.mediapipe` is **NOT** on that blocklist, so MediaPipe itself passes; the risk is a *transitive* play-services pull.
- **VALIDATE**: If transitively GMS-free → declare `implementation(...)` and ship in **both** flavors (no stub needed). If it pulls play-services → `"fullImplementation"(...)` + the degoogled stub seam (Task 5). Decide here; the rest of the plan branches on it.
- **GOTCHA**: degoogled `assembleDegoogledRelease` dex scan must stay 0 GMS — run it after adding the dep.

> **VERDICT (2026-06-20): TRANSITIVELY GMS-FREE → `implementation(...)`, ship in BOTH flavors, NO degoogled stub. Task 5 is DROPPED.**
> Latest artifact is **`0.10.26.1`** (published 2025-07-15 — confirms the "unmaintained" warning). Verified two ways:
> 1. **POM trace** of the full closure: `tasks-vision-image-generator → tasks-core → {flogger 0.6, flogger-system-backend 0.6, guava 27.0.1-android, protobuf-javalite 4.26.1, com.google.android.datatransport:(transport-api 3.0.0 / transport-backend-cct 3.1.0 / transport-runtime 3.1.0), com.google.firebase:firebase-encoders(-json/-proto)}}`. The firebase-encoders are terminal (only `androidx.annotation`). **No `com.google.android.gms` / `play-services` anywhere.**
> 2. **Real Gradle resolution** of `degoogledDebugRuntimeClasspath` with the dep added (`./gradlew :app:dependencies`): `grep -Ei "play-services|android\.gms|aicore|mlkit"` → **(none found)**. The only `com.google.android.*` package in the tree is `com.google.android.datatransport`, which matches **none** of the dex-gate blocklist (`gms|apps/aicore|mlkit`) → **gate stays 0**.
>
> **Dependency notes for Task 1+:**
> - **protobuf-javalite 4.26.1** = the EXACT version the project already declares (`protobufJavaLite = "4.26.1"`) → zero conflict.
> - **Reintroduces guava 27.0.1-android** to BOTH flavors. The de-Google work had specifically removed transitive guava (TinyGarden swapped Guava `BaseEncoding` → `java.util.Base64`). Guava is `com.google.guava`/`com.google.common` — **not** a GMS/dex-gate concern, but a method-count/footprint cost + an old (2018) lib. Acceptable; note it.
> - **`com.google.android.datatransport:transport-backend-cct` = Google "Cloud Client Telemetry" backend.** It passes the *mechanical* dex gate (not under `gms`), but it is telemetry plumbing that *can* phone home if MediaPipe wires up the datatransport runtime. The dep being PRESENT ≠ it being USED — but for the de-Googled posture this is worth a heads-up to JD. (Mitigation if it matters: confirm MediaPipe doesn't initialize CCT logging at runtime, or exclude the `transport-backend-cct` module if MediaPipe still functions without it.)
> - The real `assembleDegoogledRelease` dex-scan proof is deferred to Task 1–3 (an unused scratch dep would be R8-stripped in release, so scanning it now proves nothing; the gate must be re-run once real code references MediaPipe).

> **VERIFIED MediaPipe API REALITY (2026-06-20, document-specialist deep research — corrects several plan assumptions):**
> - **Model path is a DIRECTORY, not a single file.** `ImageGeneratorOptions.builder().setImageGeneratorModelDirectory(dirPath)`. The dir holds **~hundreds of float16 `.bin` files** (one per SD-1.5 state-dict tensor) + `bpe_simple_vocab_16e6.txt`, **~1.9 GB total**. → The provisioner must fetch a DIRECTORY: host a single `.tar.gz`/`.zip` of the bins and unpack+verify on-device (cleaner than N file GETs). The exact file list is NOT documented (it's whatever `convert.py` emits) → verify-by-completeness can't hardcode names; use the archive's own manifest or a sentinel + size check.
> - **No Google-hosted bundle.** Integrator must: download `v1-5-pruned-emaonly.ckpt` (4.27 GB, public HF, **NO token**), run `convert.py --ckpt_path … --output_path bins` (needs `torch`; **`.ckpt` only, NOT safetensors**), host the ~1.9 GB output. Host = **operator-configurable URL** (`RelaisConfig`), mirroring how the embedder takes an operator-set HF token → NOT a code blocker, an operator-provisioning step.
> - **Generation API:** `imageGenerator.generate(prompt: String, iteration: Int, seed: Int): ImageGeneratorResult` (BLOCKING — call off-main) → `result.generatedImage()` (`MPImage`) → `BitmapExtractor.extract(mpImage)` → `Bitmap` → `compress(PNG)`. **`seed` is `Int`** (the scaffold interface used `Long?` — narrow to Int or coerce). `close()` releases native/GPU; keep one instance for the session OR close-per-call. Plain text-to-image needs **no** plugin/ConditionOptions (single-arg `createFromOptions(context, options)`).
> - **Hard runtime gates (must check before init, else `createFromOptions` throws `MediaPipeException`/OpenCL `clCreateImage` errors):** (1) **arm64-v8a ONLY** — the JNI `.so` has no x86_64/armeabi → x86_64 emulator CANNOT run it (the app ships arm64-v8a + x86_64; image-gen is arm64-only); (2) **OpenCL GPU mandatory** — manifest already has `libOpenCL.so required=false`; ADD `libOpenCL-pixel.so` (Pixel targets) + `libOpenCL-car.so`; (3) **API ≥ 31** (minSdk is 31 ✓); (4) **≥8 GB RAM recommended; OOMs at 512×512 on 6 GB** — and that's WITHOUT a coexisting resident multi-GB LLM. **The OOM-with-LLM coexistence is the #1 device-only risk; prototype before flipping the endpoint on.**
> - **Upstream reference:** Relais is forked from `google-ai-edge/gallery`, whose `ImageGenerationModelHelper.kt` is direct reference code (the fork stripped it — none remains in-repo). Also the official sample `ImageGenerationHelper.kt`.

### Task 1: Provision the converted SD-1.5 bundle
- **ACTION**: Mirror `EmbeddingModelProvisioner`: download the MediaPipe-converted SD-1.5 bundle to `getExternalFilesDir/relais/imagegen/`, reuse-if-complete, weighted progress.
- **IMPLEMENT**: SD 1.5 is **open** → no HF token; `canProvision` drops the token check (keep an integrity/retry budget + a network check). Host the converted artifact (run `convert.py` offline once; host on a URL the node fetches), since the raw `.ckpt` must be converted to the MediaPipe format.
- **MIRROR**: `embed/EmbeddingModelProvisioner.kt:36-95`.
- **GOTCHA**: The bundle is large (hundreds of MB–~1.6 GB depending on precision/quantization) — show a clear size in the provisioning UI; resumable download.
- **VALIDATE**: `isProvisioned` true after download; corrupted → `clearModel` + re-fetch.

### Task 2: The image-gen runtime
- **ACTION**: `imagegen/RelaisImageGenerator` interface + `RelaisImageGeneratorProvider`; `MediaPipeImageGenerator` impl that loads the bundle and runs `ImageGenerator.generate(prompt, iterations, seed)`.
- **IMPLEMENT**: convert the result `Bitmap` → PNG `ByteArray` (`Bitmap.compress(PNG)`); demand-driven load on a background thread (single-flight).
- **MIRROR**: `embed/RelaisEmbedder.kt` lifecycle exactly.
- **GOTCHA**: **Memory.** Do NOT hold the SD model + the LLM decoding simultaneously — gate on the LLM not being mid-decode (reuse the admission semaphore), and consider releasing/closing the `ImageGenerator` after an idle timeout. Generation is single-flight and thermal-gated.
- **VALIDATE**: on-device a prompt yields a non-trivial PNG (not all-black); backend = GPU.

### Task 3: The endpoint
- **ACTION**: Register `POST /v1/images/generations` in the `RelaisHttpServer` `when` block; parse prompt/n/size/steps/seed; validate; 501/503/200; `buildImagesResponse`/`buildImagesError`.
- **MIRROR**: `/v1/embeddings` handler (RelaisHttpServer.kt:395-451) + envelope (1480-1500).
- **GOTCHA**: `shedIfHot` first (image gen is the heaviest decode); single-flight via the admission gate; cap `n` and total wall-clock; record metrics + per-endpoint latency.
- **VALIDATE**: `curl` returns `{"created","data":[{"b64_json"}]}`; thermal shed → 503; not-provisioned → 501/503.

### Task 4: Metrics + docs + warm hook
- **ACTION**: add `/v1/images/generations` to `RelaisMetrics.endpointLabel`; optional `relais_images_generated_total`; `warmIfProvisioned` at startup; write `docs/images-generations-api.md`.
- **VALIDATE**: metric appears; doc covers the unmaintained-dep + memory caveats.

### Task 5 (conditional): degoogled stub — ❌ DROPPED (Task 0 verdict: MediaPipe is GMS-free)
- ~~Only if Task 0 says MediaPipe pulls GMS.~~ Task 0 proved the dep is transitively GMS-free → declare `implementation(...)`, ship in BOTH flavors, **no stub / no flavor seam needed.** The `imagegen/` package lives in `src/main/` for both flavors. (If a future need arises to keep image-gen out of degoogled for *policy* reasons rather than GMS, revisit — but mechanically it's unnecessary.)

---

## Testing Strategy
### Unit (JVM, offline)
| Test | Input | Expected |
|---|---|---|
| request parse/validate | size/n/steps bounds | clamps + 400 on bad |
| response envelope | list of PNG bytes | `{"created","data":[{"b64_json"}]}`, base64 NO_WRAP |
| 501/503/200 selection | provider state matrix | correct status per availability |

### On-device (rango/comet — needs a live node + the provisioned bundle)
- generate a 512×512 image end-to-end; assert non-blank PNG + GPU backend.
- thermal shed path (G5 quirk) → 503; concurrent LLM decode + image gen does NOT OOM.

## Validation Commands
```bash
cd Android/src && ./gradlew :app:testFullDebugUnitTest -x lint --offline --max-workers=2
cd Android/src && ./gradlew :app:testDegoogledDebugUnitTest -x lint --offline --max-workers=2
# degoogled GMS gate (if shipped there):
./gradlew :app:assembleDegoogledRelease ; unzip -p <apk> 'classes*.dex' | strings | grep -Ec "Lcom/google/(android/(gms|apps/aicore)|mlkit)"  # MUST be 0
```

## Risks
| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| **OOM: SD model + resident LLM in memory** | High | High | Single-flight + admission semaphore; close ImageGenerator on idle; document min-RAM; consider evicting/pausing the LLM during gen |
| MediaPipe Image Generator **unmaintained** | Certain | Medium | Pin a version; isolate behind `RelaisImageGenerator` so it can be swapped (e.g. a future LiteRT diffusion path) |
| MediaPipe pulls Play-Services → breaks degoogled | Medium | Medium | Task 0 verifies first; full-only + stub if so |
| Large model provisioning UX (hundreds of MB–GB) | High | Medium | Resumable download + clear size/progress; reuse-if-complete |
| G5 thermal shed blocks generation | Medium | Medium | Same `shouldShed` gate as text; document; operator `shedHeadroom` tuning |
| SD 1.5 output quality is dated vs cloud | Certain | Low | Set expectations in docs; it's the on-device ceiling |

## Notes
- This is the **embeddings story repeated for a heavier model**: separate runtime, separate provisioning,
  same 501/503/200 endpoint shape. The novel risk is **memory coexistence with the resident LLM** —
  prototype that first (Task 2 gotcha) before investing in the full endpoint.
- Confidence in single-pass implementation: **6/10** — the patterns are well-understood, but the
  MediaPipe API integration, the converted-bundle provisioning, and the memory/coexistence behavior
  each carry real on-device unknowns that only a device can resolve.
