# `/v1/images/generations` — on-device text-to-image

OpenAI-compatible image generation over the LAN. LiteRT-LM is **text-out only**, so images come from a
**separate on-device runtime**: Google's **MediaPipe Image Generator** (Stable Diffusion v1.5). The
endpoint returns base64 PNGs.

```
POST /v1/images/generations          Authorization: Bearer <node key>
```

> **Status: scaffold (honest 501).** The endpoint, request validation, and response envelope are wired
> and tested, but no image-generation backend is registered yet — every request returns `501` until the
> MediaPipe backend follow-up lands (see *Provisioning & status codes*). This mirrors how `/v1/embeddings`
> shipped before its EmbeddingGemma impl.

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
