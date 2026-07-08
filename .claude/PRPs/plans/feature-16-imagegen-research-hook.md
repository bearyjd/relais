# #16 Image-gen — Research Hook (revisit triggers for a better path)

**Status (2026-06-21):** on-device text-to-image is being shipped via **stable-diffusion.cpp (ggml/Vulkan)
through the `io.github.aatricks:llmedge` AAR**, in a **one-runtime-per-image** design (a fresh SD context
per request, closed after). This is the "capability now > perfect later" call — it WORKS on the Tensor G5
but is **slow and constrained**. This doc tracks what "better" looks like so a future session (or a
periodic research pass) can supersede it.

## Why this shape (the constraints we proved on-device, rango Pixel 10 / G5)
- **MediaPipe Image Generator is dead** — its Java API needs full `protobuf-java`; the app is hard-locked
  to `protobuf-javalite` (6 protos + 7 DataStore serializers). Mutually exclusive on one classpath.
- **sd.cpp is the only viable engine** (protobuf-clear, Vulkan works on G5, coexists with the LLM, no OOM).
- **~90 s/image** (SD-Turbo, 4 steps) — dominated by a **~50–70 s fixed overhead** (model→GPU load + ggml-
  vulkan shader compile + VAE decode), NOT the diffusion steps. Few-step models don't rescue it.
- **The 2nd `txt2img` on a reused SD context deterministically SIGSEGVs** (native, `nativeTxt2ImgArgb`) in
  this llmedge build's sd.cpp/ggml-vulkan — independent of llmedge's cache config. → forced into
  one-runtime-per-image (every image pays the full cold overhead; no warm reuse).

## What "better" would unlock (and the trigger to act)
| Better path | What it buys | Trigger to re-evaluate |
|---|---|---|
| **ggml/sd.cpp reuse fixed** (the 2nd-`txt2img` crash) | warm reuse → drop the ~50–70 s overhead on images 2..N → multi-image batches | a newer sd.cpp/ggml-vulkan where reuse on one ctx is stable (test the repro: 2 generates, same ctx) |
| **A maintained sd.cpp Android wrapper** (llmedge is single-dev, unstable) | stability + a supported API surface | a Google/community-backed sd.cpp AAR appears, OR we build our own thin JNI (own the `sd_ctx` lifecycle) |
| **Direct sd.cpp JNI (build ourselves)** | full lifecycle control (fixes reuse + crash), slimmer APK (drop llmedge's onnx/mlkit/whisper/bark baggage), GMS-free for degoogled | when warm reuse + a clean degoogled build matter enough to spend the NDK/JNI effort |
| **Faster per-step / fewer steps** | lower wall-clock | LCM/Turbo distilled SD that's also fast per-step; or f16 vs q-quant GPU kernels measured |
| **NPU acceleration** | big speedup vs the Tensor GPU | a GMS-free NPU diffusion path for Tensor (today: QNN = Snapdragon-only; AICore = GMS) |
| **Reduce the fixed overhead** | cheaper cold start | ggml-vulkan SPIR-V pipeline disk cache; mmap model load; persistent `:imagegen` process keeping a warm ctx (if reuse is ever fixed) |
| **A protobuf-compatible Google path** | first-party, maintained | MediaPipe/LiteRT ships a diffusion task that doesn't force full protobuf-java, OR the app migrates off javalite |

## Cheap re-test recipe (when revisiting)
The throwaway `StableDiffusionProbe` (`src/androidTest`, uncommitted) already has: `vulkanBackendAvailability`,
`sd15GenerateWhileLlmResident` (`-e model -e steps -e cfg`), `perfSweep`, `stabilityReuse`, `stabilityFresh`.
To check if a new engine/wrapper fixes reuse: point it at the new lib + run `stabilityReuse` (2+ generates on
one ctx). Models live at `/var/home/user/relais-sd15-convert/` (SD-1.5 q4_0/f16 GGUF, SD-Turbo GGUF) + on
rango `/data/local/tmp/relais/imagegen/`.

## Suggested cadence
Re-scan quarterly (or on a litertlm/MediaPipe/sd.cpp release) for: a maintained sd.cpp Android binding, a
ggml-vulkan reuse fix, an NPU diffusion path, or a protobuf-clear first-party engine. Any hit → re-run the
probe; if it clears the reuse crash or halves the wall-clock, plan the swap behind the existing
`RelaisImageGenerator` interface (the seam is already shipped, so swapping engines is contained).
