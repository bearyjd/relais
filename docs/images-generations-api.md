# `/v1/images/generations` — on-device text-to-image

OpenAI-compatible image generation over the LAN. LiteRT-LM is **text-out only**, so images come from a
**separate on-device runtime**: **stable-diffusion.cpp** (ggml/Vulkan, via the `io.github.aatricks:llmedge`
AAR) running in a **process-isolated `:imagegen` OS process** for crash containment. The endpoint returns
base64 PNGs.

```
POST /v1/images/generations          Authorization: Bearer <node key>
```

> **Status: endpoint ships an honest `501` — no generator registered yet.** The route, validation, and
> response envelope exist (#63), and the process-isolated `:imagegen` service + the `llmedge` dep landed
> in **#16 PR-A** (#67), and the model provisioner + registry in **#16 PR-B**. The endpoint flips to
> `503`/`200` only once **PR-C** registers a `RelaisImageGenerator` and wires the provisioner. Until then
> every request returns `501`. (MediaPipe was evaluated and **reverted as a dead end** — see the
> on-device evaluation at the bottom.)

## Request

| field             | type                | notes                                                                 |
| ----------------- | ------------------- | --------------------------------------------------------------------- |
| `prompt`          | string              | **required**, non-blank.                                              |
| `n`               | integer             | optional; default `1`. Range `[1, 2]` — each image is a fresh ~90 s process, so the batch is capped to bound time/heat. |
| `size`            | string              | optional; default `"512x512"`. **Only `512x512`** is supported (SD 1.5/Turbo at 512²) — any other value → `400`. |
| `response_format` | `"b64_json"`        | optional; default `"b64_json"`, the only supported value. A LAN node serves no hosted URLs, so `"url"` → `400`. |
| `x_relais_steps`  | integer             | optional; defaults to the **selected model's** step count (turbo `4`, sd15 `20`), **clamped** to `[1, 50]`. |
| `seed`            | integer             | optional; reproducible output. Absent → the backend picks one.        |

```json
POST /v1/images/generations
{ "prompt": "a red bicycle on a beach", "n": 1, "size": "512x512",
  "response_format": "b64_json", "x_relais_steps": 4, "seed": 42 }
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

## Models & provisioning (#16 PR-B)

The gguf models are **too large to ship in the APK** (~1.5–2 GB) and are provisioned on demand by
`ImageModelProvisioner` (a near-copy of the embedder's provisioner: stream → SHA-256-verify →
reuse-if-complete → atomic finalize). The weights are **open and SHA-pinned, fetched from public HF
repos — no token required** (the provisioner only sends one if configured, so a private mirror also
works). The file lands in the app's external files dir at `…/files/relais/imagegen/<fileName>`.

A **swappable, SHA-pinned registry** (`ImageModelSelector`) backs an operator config flip
(`RelaisConfig.imageModelId`, default `turbo`) — upgrading quality is config, not a rebuild:

| id (`image_model_id`) | model | source (public) | size | steps/cfg | license |
| --- | --- | --- | --- | --- | --- |
| `turbo` *(default)* | SD-Turbo | `Green-Sky/SD-Turbo-GGUF` / `sd_turbo-f16-q8_0.gguf` | 1.9 GB | 4 / 1 | Stability non-commercial research |
| `sd15` | Stable Diffusion 1.5 (q4_0) | `second-state/stable-diffusion-v1-5-GGUF` / `…-Q4_0.gguf` | 1.5 GB | 20 / 7 | CreativeML OpenRAIL-M |
| `custom` | operator-hosted gguf | `image_model_url` (+ optional `image_model_sha` to pin) | — | turbo defaults | (operator's) |

Each built-in entry pins the artifact's exact `sha256` + `sizeBytes`; a download that mismatches either
is rejected and re-fetched. A `custom` URL must be `http(s)://`; if it isn't pinned with a SHA, only the
server `Content-Length` is checked and the node logs that the artifact is unverified.

| status | meaning |
| ------ | ------- |
| `200`  | image(s) returned as base64 PNG. |
| `400`  | invalid `prompt`/`n`/`size`/`response_format`/`x_relais_steps`/`seed`. |
| `429` + `Retry-After` | the admission queue is full (image gen is single-flight — it never runs concurrently with LLM decode). |
| `501`  | **current state** — no image-generation backend registered; on `degoogled`, the permanent state (a stub → 501). |
| `503` + `Retry-After` | (once PR-C lands) the model is downloading/loading in the background, **or** thermal backpressure (image gen is the heaviest decode) — retry shortly. |

## Caveats (read before relying on this)

- **Process isolation is mandatory.** `llmedge`/sd.cpp is multiply-unstable in-process (reuse → SIGSEGV;
  close+recreate → deadlock; facade → first-generate hang). The only stable primitive is a `:imagegen`
  process that loads the model, does **one** generate, writes the PNG, and is killed. Every image is a
  fresh process's first-and-only generate; a native crash/hang is contained — the node never dies.
- **Per-device support.** Needs **Vulkan** (`LLMEdge.isVulkanAvailable()`). Confirmed working on **Tensor
  G3** (Pixel 8 Pro, ~5 min cold); **deadlocks on Tensor G5** at the first ggml-vulkan dispatch
  (issue #69 — PowerVR/DXT) → on G5 the endpoint stays an honest 501. `degoogled` ships a stub → 501.
- **Performance.** ~60–90 s/image floor on a Vulkan GPU, dominated by a ~50–70 s fixed per-generate
  overhead (model→GPU load + shader compile + VAE), so fewer steps don't rescue it. An `n`-image request
  is roughly `n ×` that (sequential fresh processes); `n` is capped at 2.
- **Memory coexistence.** The node already holds a multi-GB resident LLM; sd.cpp via Vulkan coexisted on
  G5 without OOM in evaluation (`lowMemory=false`). Generation is single-flight behind the admission gate.
- **License.** SD-Turbo (default) is Stability **non-commercial research** — fine for a self-hosted
  research node; switch the default to `sd15` (OpenRAIL-M) if Relais ever ships commercially.
- **Flavor / de-Google.** `llmedge` is `fullImplementation`-only (it transitively pulls mlkit →
  play-services); the **`degoogled`** classpath has **0** llmedge/mlkit/play-services (CI-enforced). The
  provisioner + registry themselves are pure `java.net`/`java.security` in `src/main`, so they compile in
  both flavors with no GMS.

---

## On-device engine evaluation (2026-06-21) — read before building the backend

We prototyped the actual backend on real hardware (Pixel 10 Pro Fold, Tensor G5). **Verdict: MediaPipe
is dead; stable-diffusion.cpp is the only viable engine, but it's slow and its convenient wrapper is
multiply-unstable, so the production design is process isolation** (now built: #16 PR-A). The MediaPipe
backend was reverted (#66).

### Engine matrix (what was tried)
| Engine | protobuf-clash? | GPU on Tensor G5 | Verdict |
| --- | --- | --- | --- |
| **MediaPipe Image Generator** | **YES — fatal** | n/a | `ImageGenerator.createFromOptions` throws `NoSuchMethodError: Any$Builder.build()Lcom/google/protobuf/Any;`. MediaPipe's Java API needs **full `protobuf-java`**; the app is hard-locked to **`protobuf-javalite`** (6 protos + 7 DataStore serializers, plugin `option("lite")`). Both define `com.google.protobuf.*` → can't coexist; process isolation doesn't help (build-time dex clash). **DEAD (reverted #66).** |
| **stable-diffusion.cpp (ggml/Vulkan)** via `io.github.aatricks:llmedge` AAR | No (pure C++/ggml) | **Vulkan works** (OpenCL is Adreno-only → N/A on Tensor) | Generates valid 512×512 images, coexists with the resident E2B LLM **without OOM**. **VIABLE but slow + wrapper-unstable → process isolation.** |
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
3. **`LLMEdge` facade path** → **hangs on the *first* generate** (>450 s; not thermal).
The **one proven-stable primitive: a direct `ImageClient.create(ctx, scope)` doing exactly ONE generate**
(~90 s, succeeded ~5×). Everything past the first in-process operation breaks → the `:imagegen` design.

### Model sourcing (PR-B, done)
SD-Turbo / SD-1.5 q4_0 GGUFs are served from **public** sd.cpp repos — `Green-Sky/SD-Turbo-GGUF`
(`sd_turbo-f16-q8_0.gguf`) and `second-state/stable-diffusion-v1-5-GGUF` (`…-Q4_0.gguf`) — SHA-pinned in
`ImageModelSelector`, fetched tokenless. On-device load compatibility is confirmed by the PR-D gate (a
throwaway probe against `LLMEdge.isVulkanAvailable()` / `ImageClient.create(...).generate(...)`).
