# `/v1/images/generations` — on-device text-to-image

OpenAI-compatible image generation over the LAN. LiteRT-LM is **text-out only**, so images come from a
**separate on-device runtime**: Google's **MediaPipe Image Generator** (Stable Diffusion v1.5). The
endpoint returns base64 PNGs.

```
POST /v1/images/generations          Authorization: Bearer <node key>
```

> **Status: endpoint scaffold ships honest 501; no backend wired.** The route, validation, and response
> envelope exist, but no generator is registered → every request returns `501`. **The MediaPipe backend
> below is a DEAD END** (proven incompatible on-device — see *On-device engine evaluation* at the bottom);
> the real path is **stable-diffusion.cpp behind a process-isolated runtime**. Read the evaluation section
> before building. The MediaPipe-specific request/provisioning notes below are retained only for history.

## Request

| field             | type                | notes                                                                 |
| ----------------- | ------------------- | --------------------------------------------------------------------- |
| `prompt`          | string              | **required**, non-blank.                                              |
| `n`               | integer             | optional; default `1`. Range `[1, 4]` — out of range → `400` (each image is ~15 s, so the batch is capped to bound time/heat). |
| `size`            | string              | optional; default `"512x512"`. **Only `512x512`** is supported — SD 1.5 in MediaPipe is fixed-resolution, so any other value → `400` (no upscaling). |
| `response_format` | `"b64_json"`        | optional; default `"b64_json"`, the only supported value. A LAN node serves no hosted URLs, so `"url"` → `400`. |
| `x_relais_steps`  | integer             | optional; default `20`. Diffusion iteration count (quality/time knob), **clamped** to `[1, 50]`. |
| `seed`            | integer             | optional; reproducible output. Absent → the backend picks one.        |

```json
POST /v1/images/generations
{ "prompt": "a red bicycle on a beach", "n": 1, "size": "512x512",
  "response_format": "b64_json", "x_relais_steps": 20, "seed": 42 }
```

## Response

```json
{
  "created": 1701339600,
  "data": [ { "b64_json": "<base64 PNG>" } ]
}
```

`data` carries one `{ "b64_json": … }` entry per generated image, in order. Base64 is unwrapped (no line
breaks). Decode and write the bytes straight to a `.png`.

## Provisioning & status codes

The SD-1.5 bundle is **too large to ship in the APK** (hundreds of MB–~1.6 GB) and is provisioned on
demand, like the embedding model — but SD 1.5 is **open-weight**, so (unlike EmbeddingGemma) **no
HuggingFace token is required**. The raw checkpoint must first be converted to MediaPipe's format
(`convert.py`) and hosted at a URL the node fetches once.

| status | meaning |
| ------ | ------- |
| `200`  | image(s) returned as base64 PNG. |
| `400`  | invalid `prompt`/`n`/`size`/`response_format`/`x_relais_steps`/`seed`. |
| `429` + `Retry-After` | the admission queue is full (image gen is single-flight — it never runs concurrently with LLM decode). |
| `501`  | **current state** — no image-generation backend registered (and, once the provisioner lands, no model + can't fetch one). |
| `503` + `Retry-After` | thermal backpressure (image gen is the heaviest decode); **or** (once the provisioner lands) the model is downloading/loading in the background — retry shortly. |

## Caveats (read before relying on this)

- **The MediaPipe Image Generator is officially "no longer actively maintained."** SD 1.5 is the
  on-device ceiling — SDXL/Flux are not mobile-practical and MobileDiffusion was never released. The
  backend is isolated behind the `RelaisImageGenerator` interface so it can be swapped if a maintained
  diffusion runtime appears.
- **Memory coexistence is the headline risk.** The node already holds a multi-GB resident LLM; loading
  SD 1.5 (UNet + VAE + text encoder) alongside it can OOM. Generation is therefore single-flight behind
  the admission gate, and the backend should release the generator on idle. This must be validated on
  real hardware before the backend ships.
- **Performance.** ~15 s/image on a high-end GPU; an `n`-image request is roughly `n ×` that. The batch
  is capped at 4.
- **Dependency footprint / de-Google.** `com.google.mediapipe:tasks-vision-image-generator` is
  **transitively GMS-free** (verified at dependency-resolution time — no `play-services`), so it ships
  in **both** the `full` and `degoogled` flavors with no stub. It does pull `com.google.android.datatransport`
  (Cloud Client Telemetry plumbing — passes the degoogled dex gate; present ≠ used) and re-introduces
  Guava to the classpath.

---

## On-device engine evaluation (2026-06-21) — read before building the backend

We prototyped the actual backend on real hardware (Pixel 10 Pro Fold, Tensor G5). **Verdict: MediaPipe
is dead; stable-diffusion.cpp is the only viable engine, but it's slow and its convenient wrapper is
multiply-unstable, so the production design must be process isolation.** The `MediaPipeImageGenerator`
class + dep currently on `main` are a **dead end** — they will never run on this stack (see below).

### Engine matrix (what was tried)
| Engine | protobuf-clash? | GPU on Tensor G5 | Verdict |
| --- | --- | --- | --- |
| **MediaPipe Image Generator** | **YES — fatal** | n/a | `ImageGenerator.createFromOptions` throws `NoSuchMethodError: Any$Builder.build()Lcom/google/protobuf/Any;`. MediaPipe's Java API needs **full `protobuf-java`**; the app is hard-locked to **`protobuf-javalite`** (6 protos + 7 DataStore serializers, plugin `option("lite")`). Both define `com.google.protobuf.*` → can't coexist; process isolation doesn't help (build-time dex clash). **DEAD.** |
| **stable-diffusion.cpp (ggml/Vulkan)** via `io.github.aatricks:llmedge` AAR | No (pure C++/ggml) | **Vulkan works** (OpenCL is Adreno-only → N/A on Tensor) | Generates valid 512×512 images, coexists with the resident E2B LLM **without OOM** (`lowMemory=false`; E2B ~5.3 GB PSS, peak ~6.7 GB, ~3.4 GB free). **VIABLE but slow + wrapper-unstable.** |
| raw `.tflite` via bundled LiteRT | n/a | n/a | DEAD — TFLite FlatBuffer 2 GB hard limit; SD-1.5 UNet is ~3.4 GB. |
| ONNX Runtime | likely safe (verify) | NNAPI-only, SD ops fall back to CPU = slow | down-ranked |
| MNN | unverified | OpenCL+Vulkan | down-ranked (no Maven AAR, build-from-source) |

### Performance (sd.cpp, all cold runs, LLM resident, Vulkan)
- SD-1.5 q4_0, 20 steps → **184 s**; SD-Turbo, 4 steps → **90 s**.
- `timePerStep` *rose* 9.2 s→22.4 s as steps dropped 20→4 ⇒ a **large fixed per-generate overhead
  (~50–70 s: model→GPU load + ggml-vulkan shader compile + VAE)** dominates. Few steps do **not** rescue
  it. Realistic floor ~**60–90 s/image**. (Operator deemed ~90 s acceptable; stability is the blocker.)

### Stability — the decisive finding (llmedge, on G5)
Three independent failure modes, all reproduced:
1. **Reuse** (same `ImageClient`, 2nd generate) → **SIGSEGV** (null-deref in `libsdcpp.so`
   `nativeTxt2ImgArgb`, identical offset every time; not fixed by `RuntimeCacheConfig` keep-resident).
2. **Fresh client per image** (close + recreate in one process) → **deadlock** on teardown/recreate.
3. **`LLMEdge` facade path** → **hangs on the *first* generate** (>450 s; not thermal — SoC was only
   `mStatus=1` LIGHT).
The **one proven-stable primitive: a direct `ImageClient.create(ctx, scope)` doing exactly ONE generate**
(~90 s, succeeded ~5×). Everything past the first in-process operation breaks.

### Recommended path forward — process isolation
Run image-gen in a **separate process** (`android:process=":imagegen"`): the process loads the model,
does **one** `ImageClient.generate`, writes the PNG, and **exits (is killed)**. Never reuse a context,
never close+recreate in-process, never use the facade. This is stable *by construction* (every image is a
fresh process's first-and-only generate) and contains any native crash/hang to a disposable process — the
node never dies. Wire it behind the already-shipped `RelaisImageGenerator` interface (the seam from #63).
- **Packaging (operator decision):** image-gen rides the **`full` flagship** via `fullImplementation(llmedge)`
  (its onnxruntime/mlkit/play-services transitives are fine there); the **`degoogled`** flavor gets a
  best-effort stub → 501. Never compromise `full` for `degoogled`.
- **Cleanup owed:** revert the dead MediaPipe dep + `MediaPipeImageGenerator.kt` (ships ~tens of MB of
  native libs that can never run). Keep the `RelaisImageGenerator` interface/provider + this endpoint
  (honest 501).
- **Open infra:** host the converted/GGUF model (operator-set URL → tarball, HF default, SHA-pinned),
  mirroring the embedder. SD-1.5 q4_0 / SD-Turbo GGUFs verified loadable (`second-state/…`,
  `Green-Sky/SD-Turbo-GGUF`).
- A throwaway on-device probe harness (Tier-1 Vulkan check, perf sweep, the three stability tests) was
  used to establish all of the above; recreate it against `llmedge` `LLMEdge.isVulkanAvailable()` /
  `ImageClient.create(...).generate(ImageGenerationRequest(...))` if re-validating.
