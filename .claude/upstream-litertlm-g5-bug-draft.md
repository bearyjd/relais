# Upstream bug for google-ai-edge/LiteRT-LM — FILED

> ✅ Filed 2026-06-12 as **https://github.com/google-ai-edge/LiteRT-LM/issues/2566** (by `bearyjd`). Text below is the as-filed report.
>
> **Status check 2026-06-18:** issue still **OPEN, no maintainer response** (last updated 2026-06-13). Latest
> published `litertlm-android` is still **0.13.1** — no newer release since filing, and the report already shows the
> crash on 0.13.1, so there is no version-bump fix available yet. Relais ships around it (G5 default pinned to E2B,
> which serves). The investigation plan (`PRPs/plans/pixel-10-inference-crash-investigation.plan.md`) is therefore
> **resolved-by-workaround**: E4B-on-G5 remains broken upstream; E2B-on-G5 is the serving path.
>
> **Update 2026-06-21 — third-generation control added:** ran the byte-identical `gemma-4-E4B-it.litertlm` on a **Pixel 8 Pro (Tensor G3, Android 16)** on the current bundled build (litertlm 0.11.0). First `/v1/chat/completions` → **HTTP 200, ~1.6 s, coherent reply, no crash, no OOM** (process survived, thermal cool). The crash is now confirmed **G5-specific across three Tensor generations: G3 ✅ / G4 ✅ / G5 ❌** — strengthens the "G5 regression" framing for the nudge.
>
> ## Follow-up nudge — POSTED 2026-06-21 → https://github.com/google-ai-edge/LiteRT-LM/issues/2566#issuecomment-4763331682 (with the G3 datapoint). Text below as posted.
>
> ```
> Friendly ping — this still reproduces on the latest release (litertlm-android 0.13.1; no newer version has
> shipped since I filed). gemma-4-E4B-it null-deref SIGSEGVs on the first inference on Tensor G5 / Pixel 10,
> while gemma-4-E2B and Qwen3-0.6B serve fine on the same G5 device, and E4B serves fine on both Tensor G3 (Pixel 8 Pro) and G4 — backend-agnostic
> (GPU and CPU), no tools, no constrained decoding.
>
> One concrete ask to unblock servers: is gemma-4-E4B expected to run on Tensor G5 at all right now? If it isn't
> yet supported, a capability check that fails at Engine.initialize() with a clear error — instead of a native
> SIGSEGV on the first decode — would let a resident-engine server gate the device gracefully rather than
> crash-loop. Happy to provide full tombstones and the E2B/Qwen working traces for comparison.
> ```

---

**Title:** `gemma-4-E4B` SIGSEGVs (null-deref) on the first inference on Tensor G5 / Pixel 10 — while `gemma-4-E2B` and Qwen3 serve fine on the *same* G5 device, and E4B serves fine on Tensor G4

**Environment**
- Device (fails): Pixel 10 Pro Fold (`rango`), **Tensor G5**, Android 16 (`google/rango/rango:16/BP4A.260205.001`), arm64-v8a.
- Control devices (work): Pixel 8 Pro (**Tensor G3**, Android 16, 12GB) and Pixel 9 Pro Fold (**Tensor G4**, Android 16).
- LiteRT-LM: reproduced on **`litertlm-android:0.11.0` AND `0.13.1`** (latest).
- Model: `litert-community/gemma-4-E4B-it-litert-lm` → `gemma-4-E4B-it.litertlm` (sha256 `0b2a8980…bd52e0`).
- **No tools, no constrained decoding** — a plain text prompt, `ConversationConfig` with only a `SamplerConfig`. (This distinguishes it from #2149, whose E4B decode segfault is pinned to the constrained-decoding / `libGemmaModelConstraintProvider.so` path.)

**Summary**
`Engine.initialize()` succeeds (`isInitialized()==true`), but the **first** `Conversation.sendMessageAsync` of a plain text prompt crashes the process with a native SIGSEGV (null-deref) inside `liblitertlm_jni.so`. The crash is:
- **Backend-agnostic** — same crash with `Backend.GPU()` and `Backend.CPU()` (0.11.0); reproduced with a text-only `EngineConfig` (no vision/audio backend) on 0.13.1.
- **Version-agnostic** — 0.11.0 and 0.13.1, different native BuildIds (`c2c2717…` / `cae57ec…`), identical crash.
- **Isolated to gemma-4-E4B on G5** by three controls on the *same* hardware/runtime:

| Model | Tensor G3 (Pixel 8 Pro) | Tensor G4 (Pixel 9) | Tensor G5 (Pixel 10) |
|---|---|---|---|
| **gemma-4-E4B-it.litertlm** (multimodal) | ✅ **serves** (HTTP 200, ~1.6s 1st inference) | ✅ serves | ❌ **SIGSEGV (1st inference)** |
| **gemma-4-E2B-it.litertlm** (multimodal, same config/GPU path) | — | ✅ serves | ✅ **serves** (HTTP 200, coherent) |
| **Qwen3-0.6B.litertlm** (text-only) | — | — | ✅ serves (~3.5 tok/s) |

→ Not the SoC (E2B + Qwen3 run on G5), not the Gemma-4 family (E2B runs on G5 with the *same* multimodal config), not the model file (E4B serves on **Tensor G3 AND G4** with byte-identical bytes), not the multimodal backends (crashes text-only too). It is specific to **gemma-4-E4B's graph on Tensor G5** — now confirmed across three Tensor generations (G3 ✅, G4 ✅, G5 ❌).

**Crash (0.13.1, representative)**
```
signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0 (read)
Cause: null pointer dereference   (x0 = 0)
thread: execution_thread
backtrace (all frames in liblitertlm_jni.so, BuildId cae57ec67cfe0989898a8e078e71c36d):
  #00 pc 0x006e8fa4 … #07 0x00705d14   #08 libc __pthread_start   #09 libc __start_thread
```
`/health` reports `ready:true`; the first `/v1/chat/completions` then crashes at ~2.6–4s.

**Minimal repro**
```kotlin
val e = Engine(EngineConfig(modelPath = ".../gemma-4-E4B-it.litertlm", backend = Backend.GPU(),
                            maxNumTokens = 1024, cacheDir = cacheDir))   // text-only config
e.initialize()                                                          // OK on G5
val c = e.createConversation(ConversationConfig(SamplerConfig(topK=64, topP=0.95, temperature=1.0)))
c.sendMessageAsync(Contents.of(listOf(Content.Text("Say hello in one word."))), cb, emptyMap())
// ^ native SIGSEGV on G5; returns text on G4. gemma-4-E2B AND Qwen3-0.6B in the same harness return text on G5.
```

**Expected:** `gemma-4-E4B` inference on Tensor G5 returns tokens, as `gemma-4-E2B` does on G5 and as `gemma-4-E4B` does on G4.
**Actual:** native null-deref SIGSEGV in `liblitertlm_jni.so` on the first inference (uncatchable from JVM → a crash-loop for any resident-engine server).

**Likely related (cross-references)**
- **#1681** — Tensor G5 / Pixel 10 GPU is misidentified (PowerVR branch) in `gpu_model_builder.cc`; corroborates that the G5 path is fragile (but our crash is also on CPU, so it's not solely that).
- **#2149** — E4B segfaults / E2B hangs on `RunDecodeAsync` (Linux CPU). Same model family, but that report's cause is the **constrained-decoding** artifact; ours uses **no** constrained decoding, so it's a distinct trigger (or a second null-deref in the plain decode path). E4B being the *segfault* case there matches E4B being the only failing model here.
- **#2056** — E4B SIGSEGV in the CPU *vision* path (second image turn). Different surface, same model implicated.
- No published `gemma-4-E4B-it_Google_Tensor_G5.litertlm` exists (whereas E2B ships one), so there is no G5-specific E4B build to fall back to.

**Notes / asks**
- Is `gemma-4-E4B` expected to run on Tensor G5 via LiteRT-LM? If not yet, a capability check / clear error at `initialize()` (instead of a native crash at first decode) would let servers gate gracefully.
- Single G5 unit available — but E2B + Qwen3 serving on it rules out a gross single-unit hardware fault.
- Happy to provide full tombstones, the E2B/Qwen working traces, and per-config logs.
