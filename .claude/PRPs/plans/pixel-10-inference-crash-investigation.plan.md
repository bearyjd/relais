# Plan: Investigate why the Pixel 10 can't run inference

> **STATUS 2026-06-18 — RESOLVED BY WORKAROUND (upstream bug filed).** Root cause is upstream: gemma-4-**E4B**
> null-deref SIGSEGVs on the first inference on Tensor G5, reproduced on **both 0.11.0 and the latest 0.13.1**
> (backend-agnostic, no constrained decoding) — filed as **google-ai-edge/LiteRT-LM#2566** (OPEN, no maintainer
> response as of 2026-06-18). **E2B serves fine on G5**, so Relais pins the G5 default to E2B (PR #19,
> `G5_DEFAULT_REF`) and serves normally. No version-bump fix exists (0.13.1 still crashes). Remaining open thread
> is upstream-only (a maintainer reply / a G5-capable E4B build / an init-time capability error). The diagnostic
> tasks below are retained as the evidence trail; they do not need to be re-run unless a newer runtime ships.

## Summary
On the **Pixel 10 Pro Fold** (`rango`, Tensor **G5**) the resident LiteRT-LM engine **initializes successfully** (`ready:true`) but the **first inference deterministically SIGSEGVs** the native `liblitertlm_jni.so` — on **both GPU and CPU** backends. The **byte-identical model** (`gemma-4-E4B-it.litertlm`, sha256 `0b2a8980…bd52e0`) **serves correctly on the Pixel 9** (`comet`, Tensor G4) — `/v1/chat/completions` → `{"content":"ping"}`, http=200, 1.1 s. This is a **diagnostic investigation** to root-cause the G5 crash and identify a fix or workaround. It is NOT a feature build; most tasks are on-device probes with branching outcomes.

## User Story
As a **Relais node operator with a Pixel 10**, I want **the node to actually serve inference on my device**, so that **a Pixel 10 (the current-gen, more powerful phone) is a usable node, not just a Pixel 9**.

## Problem → Solution
**Current:** LiteRT-LM `0.11.0` engine inits on the Pixel 10 (G5) but every `Conversation.sendMessageAsync` / `generate` crashes natively (null-pointer SIGSEGV in `liblitertlm_jni.so`), backend-agnostic. The same model + same app serves on the Pixel 9 (G4). The Pixel 10 was **never serving-verified** before this session — all spike/Phase-A baselines were on G4.
**Desired:** A determined root cause (most-likely: LiteRT-LM `0.11.0` lacks Tensor-G5 inference support) and a concrete remediation — a runtime version bump, a Pixel-10-specific backend route (AICore/NPU), a graceful-failure guard, and/or an upstream bug report.

## Metadata
- **Complexity**: **Medium** (investigation; outcome may be a 1-line version bump, a backend-routing change, or an upstream report — branching)
- **Source PRD**: N/A (free-form via `/prp-plan`)
- **PRD Phase**: N/A — standalone investigation, follows the merged Phase B model-selector work (`main` @ `09dcec5`)
- **Estimated Files**: ~0–3 changed depending on outcome (likely `gradle/libs.versions.toml` and/or `RelaisEngine.kt`/`BackendSelector`), plus throwaway diagnostic test harnesses

---

## The crash — exact evidence (captured this session, 2026-06-11)

> This is the investigation's primary "mandatory reading." Reproduced on `rango` (`57211FDCG0023C`).

```
signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0000000000000000 (read)
Cause: null pointer dereference
esr: 0x92000006 (Data Abort Exception 0x24)
thread name: engine/<tid>   >>> cc.grepon.relais <<<
11 total frames, all in liblitertlm_jni.so (BuildId c2c27170ba409dbd0bc01820fa738580, load offset 0x6544000):
  #00 pc 0x00a45c08   #01 pc 0x0070b30c   #02 pc 0x007101c8   #03 pc 0x00705b20
  #04 pc 0x00705438   #05 pc 0x00701da4   #06 pc 0x00704d28   #07 pc 0x00732aa8
  #08 pc 0x00733398   #09 libc __pthread_start   #10 libc __start_thread
Tombstones: /data/tombstones/tombstone_00, tombstone_01 (+ .pb)
```

**Key characteristics (each narrows the search):**
- Crash is at **inference (`generate`/`sendMessageAsync`)**, NOT engine `initialize()` — `Resident engine ready: true` is logged, `/health` returns `{"ready":true}`, THEN the first `/v1/chat/completions` crashes at ~2.6–4 s, `http=000`.
- **Backend-agnostic**: identical SIGSEGV + identical backtrace offsets on `Backend.GPU()` AND `Backend.CPU()` (verified by a temporary `RelaisEngine` backend flip, since reverted). → NOT a GPU/OpenCL-only fault.
- **Model is not the cause**: `gemma-4-E4B-it.litertlm` is byte-identical (sha256) to the file the Pixel 9 serves, and identical across HF commits `f7ad3343`/`28299f30`. The model has `audio_adapter` + vision signatures (multimodal).
- **Device-specific**: Pixel 9 (G4) serves the same bytes fine; Pixel 10 (G5) crashes. Only one Pixel 10 unit available (`rango`) — can't yet rule in/out a single-unit hardware fault vs. all-Pixel-10.
- Engine + native libs are entirely from `litertlm-android:0.11.0` (no repo-local `.so`). Native libs loaded: `libOpenCL.so`, `libedgetpu_litert.so`, `libOpenCL-pixel.so` (per the `nativeloader` log).

---

## UX Design
Internal/infra investigation — no user-facing UX transformation. Outcome may change which devices can serve, and (if AICore routing is added) which backend a Pixel 10 uses, but the operator-facing API is unchanged.

---

## Mandatory Reading

| Priority | File | Lines | Why |
|---|---|---|---|
| P0 | `Android/src/app/src/main/java/cc/grepon/relais/RelaisEngine.kt` | 117–267 | `ensureInitialized` (the exact `EngineConfig`: `backend=GPU, visionBackend=GPU, audioBackend=CPU, maxNumTokens=1024`) + `generate` (the `createConversation`→`sendMessageAsync`→`MessageCallback` path that crashes). The init/inference seam to instrument. |
| P0 | `Android/src/gradle/libs.versions.toml` | 23, 69 | `litertlm = "0.11.0"` → `com.google.ai.edge.litertlm:litertlm-android`. **The version to bisect.** Newer published: `0.12.0`, `0.13.0`, `0.13.1`. |
| P0 | `SPIKE-FINDINGS.md` | 1–120 | Settled facts. **All baselines are Tensor G4 / Pixel 9.** Q1: the runtime exposes **no resolved-backend getter** (can't read what backend the native layer actually used). Top-level `benchmark(modelPath, backend, prefillTokens, decodeTokens, cacheDir)` exists for throughput probes. `EngineConfig` recipe documented. |
| P1 | `Android/src/app/src/main/java/cc/grepon/relais/RelaisAicore.kt` | all | The **Pixel-10-only NPU/AICore path** (ML Kit GenAI / Gemini Nano), image+text only, no audio. `available()` memoized capability probe via `checkStatus()`. If LiteRT-LM is unusable on G5, AICore may be the viable image/text route. |
| P1 | `Android/src/app/src/main/java/cc/grepon/relais/common/Utils.kt` | 357–363, 390–394 | `isPixel10()` = `Build.MODEL.lowercase().contains("pixel 10")`; `isPixelDevice()`; `isAICoreSupported(allowedDeviceModels)`. The device-gating primitives. |
| P1 | `Android/src/app/src/main/java/cc/grepon/relais/data/ModelAllowlist.kt` | 115–157 | `if (isPixel10()) accelerators.remove(Accelerator.GPU)` — the codebase already KNOWS the Pixel 10 GPU is problematic for the *Gallery chat path*. (Note: `RelaisEngine` hardcodes GPU and does NOT consult this — a relevant asymmetry, even though the CPU experiment shows the crash isn't GPU-only.) |
| P2 | `Android/src/app/src/androidTest/java/cc/grepon/relais/RelaisBackendBenchmarkTest.kt` | all | The instrumented benchmark harness pattern — reuse to drive `benchmark()`/inference probes on-device without the HTTP layer. |
| P2 | `Android/src/app/src/androidTest/java/cc/grepon/relais/RelaisNodeTest.kt` | all | On-device serve test idiom (resident engine + request). |

## External Documentation

| Topic | Source | Key Takeaway |
|---|---|---|
| LiteRT-LM runtime + releases | https://github.com/google-ai-edge/LiteRT-LM (releases / CHANGELOG) | Check release notes `0.12.0`/`0.13.0`/`0.13.1` for "Tensor G5" / "Pixel 10" / SIGSEGV / inference fixes. The bundled `0.11.0` predates these. |
| LiteRT-LM Android artifact | `https://dl.google.com/android/maven2/com/google/ai/edge/litertlm/litertlm-android/maven-metadata.xml` | Available versions (confirmed this session): … `0.11.0`, `0.12.0`, `0.13.0`, **`0.13.1`** (latest). |
| LiteRT / LiteRT-LM issues | https://github.com/google-ai-edge/LiteRT-LM/issues (and google-ai-edge/litert) | Search "Pixel 10", "Tensor G5", "G5", "SIGSEGV", "null", "crash inference". |
| ML Kit GenAI (AICore/Nano) | https://developers.google.com/ml-kit/genai/prompt-api/android | The AICore path RelaisAicore uses; Pixel 10 qualifies for AICore. Image+text only, no audio. |
| Android native crash debugging | `ndk-stack`, `llvm-addr2line`, `simpleperf`/tombstone symbolication | Symbolize the `liblitertlm_jni.so` offsets against the AAR's `.so` (likely stripped → partial). BuildId `c2c27170…` identifies the exact binary. |

> RESEARCH NOTE: Maven version list already fetched this session — `0.13.1` is the newest. The single highest-value external check is the **LiteRT-LM 0.12/0.13 release notes for Pixel 10 / Tensor G5 support**, which would directly confirm or refute hypothesis H5 before any device work.

---

## Hypotheses (ranked) — what the probes must discriminate

| # | Hypothesis | Evidence FOR | Evidence AGAINST | Discriminating probe |
|---|---|---|---|---|
| **H5** | **LiteRT-LM `0.11.0` lacks Tensor-G5 inference support; a newer version (`0.13.1`) fixes it.** | Strongest a-priori: `0.11.0` predates G5/Pixel-10 GA; 3 newer versions exist; init works but inference (newer codegen/kernels) crashes; backend-agnostic fits a runtime-version issue. | None yet. | **Task 4** (bump to `0.13.1`, retest on `rango`). |
| **H1** | **`0.11.0` native engine has a G5 inference bug independent of model/backend.** | Backend-agnostic crash; init OK / inference crash; same backtrace CPU+GPU. | — | **Task 3c** (a *different*, simpler text-only `.litertlm` model on G5 — if it also crashes, it's the engine, not this model). |
| **H2** | **The multimodal model (`audio_adapter`/vision) hits a G5-specific op/codepath at prefill.** | Model loads audio_adapter + vision; crash at first prefill. | Backend-agnostic; CPU path also crashes (vision/audio default to GPU/CPU resp.). | **Task 3c** (text-only model) + **Task 3a** (single prefill token). |
| **H3** | **A native lib (`libOpenCL-pixel.so`/`edgetpu`) is G4-tuned and misbehaves on G5.** | `libOpenCL-pixel.so` is Pixel-specific. | CPU backend (no OpenCL) crashes identically → weakens a pure-OpenCL theory. | **Task 5** (compare native-lib load logs G4 vs G5; look for load fallback/errors). |
| **H6** | **The specific `rango` unit is faulty** (not all Pixel 10s). | Can't rule out with one unit. | A clean OS, model loads + caches build fine. | **Task 8** (second Pixel 10, or upstream report comparison) — likely deferred (one unit). |
| **H4** | **ABI/SoC-feature mismatch** (SIMD/PAC/MTE/page-size). | Tombstone shows PAC keys, tagged_addr, 16KB-page era hardware. | G4 also has PAC/tagged-addr; same arm64-v8a ABI. | Falls out of symbolication (**Task 2**) + H5 result. |

> Working prior: **H5 then H1** are most likely. The backend-agnostic crash + init-OK/inference-crash + an old pinned runtime + a current-gen SoC is the classic "runtime predates the hardware" signature. Test H5 first — it may be a one-line fix.

---

## Patterns to Mirror

### ENGINE_INIT_AND_INFERENCE (the seam under test)
```kotlin
// SOURCE: RelaisEngine.kt:136-147 (init) and :190-238 (inference)
val e = Engine(EngineConfig(
  modelPath = modelPath,
  backend = Backend.GPU(), visionBackend = Backend.GPU(), audioBackend = Backend.CPU(),
  maxNumTokens = 1024, cacheDir = cacheDir,
))
e.initialize()                                  // SUCCEEDS on G5 (ready:true)
val conversation = e.createConversation(
  ConversationConfig(samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = 1.0)))
conversation.sendMessageAsync(Contents.of(contents), object : MessageCallback { ... }, emptyMap())
// ^ CRASHES natively on G5, both backends. No throwable reaches onError — it's a hard SIGSEGV.
```
> GOTCHA: the crash is a **native SIGSEGV**, not a Kotlin/JVM exception — `try/catch`, `onError`, and `latch.await` never see it; the process dies and the watchdog restarts it into a crash-loop. Any "graceful failure" must come from NOT calling the crashing path on G5 (pre-flight gate), not from catching it.

### BENCHMARK_PROBE (drive inference without the HTTP/conversation streaming layer)
```kotlin
// SOURCE: SPIKE-FINDINGS.md Q1 — top-level throughput probe
// benchmark(modelPath, backend, prefillTokens = 256, decodeTokens = 256, cacheDir = null)
// Use prefillTokens=1, decodeTokens=1 to isolate the minimal crashing call.
```

### DEVICE_GATE (how the codebase branches on Pixel 10)
```kotlin
// SOURCE: common/Utils.kt:357-359
fun isPixel10(): Boolean = Build.MODEL != null && Build.MODEL.lowercase().contains("pixel 10")
// SOURCE: ModelAllowlist.kt:141-143  — existing precedent that Pixel 10 GPU is special-cased
if (isPixel10()) accelerators.remove(Accelerator.GPU)
```

### ON_DEVICE_TEST_HARNESS (non-destructive instrumented probe)
```kotlin
// SOURCE: RelaisBackendBenchmarkTest.kt / RelaisNodeTest.kt
// @RunWith(AndroidJUnit4::class); InstrumentationRegistry.getInstrumentation().targetContext
// Drive RelaisEngine.generate(...) / benchmark(...) directly; run via:
//   adb -s <serial> shell am instrument -w -e class <Test> cc.grepon.relais.test/androidx.test.runner.AndroidJUnitRunner
// am instrument does NOT uninstall — non-destructive. Filter to the one probe class.
```

---

## Files to Change

| File | Action | Justification |
|---|---|---|
| `Android/src/gradle/libs.versions.toml` | UPDATE (conditional) | Bump `litertlm = "0.11.0"` → `"0.13.1"` IF Task 4 shows it fixes G5 (the likely outcome). |
| `Android/src/app/src/main/java/cc/grepon/relais/RelaisEngine.kt` | UPDATE (conditional) | IF version bump doesn't fix it: a Pixel-10 pre-flight gate (route to AICore for image/text, or fail gracefully with a clear error instead of a native crash-loop). |
| `Android/src/app/src/main/java/cc/grepon/relais/RelaisEngine.kt` (`BackendSelector`) | UPDATE (conditional) | IF AICore is the G5 path: enable `aicoreAvailable()` on Pixel 10 and route image/text → NPU (audio has no fallback → must surface a clear "unsupported on this device" error). |
| `Android/src/app/src/androidTest/java/cc/grepon/relais/RelaisG5InferenceProbeTest.kt` | CREATE (throwaway) | A minimal instrumented probe that calls `generate`/`benchmark` with 1 token on `rango` — used across Tasks 3–4; **not committed** (device/version-specific). |
| `docs/` or `SPIKE-FINDINGS.md` | UPDATE | Record the G5 finding + remediation (so the next operator/Pixel-10 user isn't surprised). |

## NOT Building
- **No fix to the LiteRT-LM native engine itself** — it's a closed-source Google AAR; the most we do is change versions, route around it, or file an upstream issue.
- **No multi-device fleet support / per-SoC model variants** — out of scope; this is a single-crash root-cause.
- **No change to Phase A/B model-selector code** — that work is merged and validated; this is downstream of it.
- **No GPU driver / kernel work** — we can't modify the device's drivers.
- **No audio fallback on AICore** — AICore (Nano) cannot do audio; if LiteRT-LM is unusable on G5, audio inference on a Pixel 10 stays unsupported (surface a clear error, don't pretend).
- **No attempt to symbolize beyond what the stripped AAR allows** — partial symbolication only, unless Google ships symbols.

---

## Step-by-Step Tasks (diagnostic probes — run in order; branch on results)

### Task 1: Capture the full crash artifact
- **ACTION**: Reproduce on `rango` (`57211FDCG0023C`) and pull the complete tombstone + native crash.
- **METHOD**:
  - Re-provision (the `f7ad3343` file is still on `rango` at `…/files/litert_community_gemma_4_E4B_it_litert_lm/f7ad3343…/gemma-4-E4B-it.litertlm`; key `7c031901d2b447dca8ea5377803b972e` from this session — re-read via a throwaway test if changed). Start the node, `curl /v1/chat/completions`, let it crash.
  - `adb -s 57211FDCG0023C shell run-as cc.grepon.relais` is NOT needed; tombstones are in `/data/tombstones/` (root-readable; pull via `adb pull` may need `su` — if unavailable, parse `logcat -b crash` which already carries the full backtrace + registers + memory map).
  - Save the full `DEBUG` block (all frames, registers x0–x30/sp/pc/lr, `memory near` dumps, `memory map` around `liblitertlm_jni.so`).
- **EXPECTED SIGNAL**: Full register state + the faulting instruction's neighbourhood; confirm `x0 == 0` (the null being dereferenced) and the exact `pc` within `liblitertlm_jni.so`.
- **VALIDATE**: A saved, complete tombstone/crash log committed to the investigation notes (not the repo).

### Task 2: Symbolize the backtrace (best-effort)
- **ACTION**: Map the `liblitertlm_jni.so` pc offsets to functions.
- **METHOD**: Extract `liblitertlm_jni.so` from the resolved AAR (`~/.gradle/caches/.../litertlm-android-0.11.0.aar` → `jni/arm64-v8a/`); run `llvm-addr2line -e liblitertlm_jni.so -f 0xa45c08 0x70b30c …` and/or `ndk-stack`. BuildId `c2c27170…` identifies the exact binary.
- **EXPECTED SIGNAL**: Even if stripped (likely → addresses only), confirm all frames are in one library and look for any exported symbol near the top frame (e.g., a tokenizer / prefill / sampler / kv-cache function). If Google publishes symbols for `0.11.0`, full names.
- **GOTCHA**: Release AARs are usually stripped → expect partial/no symbols. Don't block the investigation on this; H5/H1 device probes are more decisive.
- **VALIDATE**: Any function-name hint, or a documented "stripped — no symbols" conclusion.

### Task 3: Narrow the crash (minimal repro + model isolation)
- **3a — Minimal call**: Drive `benchmark(modelPath, Backend.GPU(), prefillTokens=1, decodeTokens=1, cacheDir)` (and `Backend.CPU()`) via a throwaway instrumented probe on `rango`. **SIGNAL**: still crashes → the crash is in core prefill/decode, not the conversation/streaming wrapper; isolates it from `sendMessageAsync`/`MessageCallback`.
- **3b — Decode vs prefill**: vary `prefillTokens` (1 vs 256) and `decodeTokens` (0 vs 1). **SIGNAL**: which phase faults (prefill setup vs first decode step).
- **3c — Different model (KEY discriminator for H1 vs H2)**: download a *different, simpler, text-only* `.litertlm` on `rango` (e.g. `litert-community/Gemma3-1B-IT` if it has a text-only `.litertlm`; resolve it via the now-merged HF selector). Init + infer. **SIGNAL**: also crashes → **engine/runtime broken on G5 (H1)**, model-independent. Serves → **multimodal-`gemma-4-E4B` specific on G5 (H2)**.
- **VALIDATE**: A crash/serve result for (minimal call) × (this model) × (a second model), CPU and GPU.

### Task 4: Version bisection (tests H5 — likely the fix)
- **ACTION**: Bump `litertlm` in `libs.versions.toml` `0.11.0` → `0.13.1` (latest), rebuild, reinstall on `rango`, retest inference.
- **METHOD**: `./gradlew :app:assembleDebug` (NOTE: a new litertlm version is a new AAR download — needs network, NOT `--offline`). Watch for API breakage (the `Engine`/`EngineConfig`/`Conversation` API may have changed between 0.11 and 0.13 — check `RelaisEngine.kt` compiles; SPIKE-FINDINGS Q1 decompiled 0.11.0, so re-verify the surface). Reinstall, start node, `curl`.
- **EXPECTED SIGNAL**: Inference returns 200 on `rango` → **H5 confirmed; the fix is a version bump.** Still crashes → escalate (H1; the runtime still lacks G5 support → AICore route + upstream report).
- **GOTCHA**: If `0.13.1` breaks the API, try `0.12.0` first (smaller jump). Re-run the **Pixel 9** regression (the live node) on the new version too — a bump must not break G4. **Use the `rango` spare for the G5 test; do NOT change the live Pixel 9's app to an unproven version until G4 is re-validated on the spare or a throwaway path.**
- **VALIDATE**: `assembleDebug` green; `rango` inference 200; Pixel 9 inference still 200 (no G4 regression). If green on both, this likely becomes the committed fix (PR: "chore(relais): bump litertlm 0.11.0→0.13.1 for Tensor-G5 inference").

### Task 5: Native-lib load comparison (G4 vs G5) — tests H3
- **ACTION**: Compare which native libs load (and any fallback/error) on `comet` (G4) vs `rango` (G5).
- **METHOD**: `adb -s <serial> logcat | grep -iE "nativeloader|libOpenCL|edgetpu|litert|dlopen|cannot|fallback"` during engine init on each. The G5 init log already showed `libOpenCL.so:libedgetpu_litert.so:libOpenCL-pixel.so` configured.
- **EXPECTED SIGNAL**: A G5-only load failure / silent fallback (e.g., `libOpenCL-pixel.so` not matching the G5 GPU) would support H3 — but the CPU-also-crashes fact already weakens H3; this is a cheap confirm/deny.
- **VALIDATE**: A documented diff of native-lib load behaviour between the two SoCs.

### Task 6: Test the AICore/NPU path on the Pixel 10 (the designed G5 route + possible workaround)
- **ACTION**: On `rango`, probe `RelaisAicore.available(context)` and run an **image+text** AICore inference.
- **METHOD**: Throwaway instrumented test calling `RelaisAicore.available()` then `RelaisAicore.generate(RelaisRequest(text="ping"))`. The Pixel 10 qualifies for AICore (ML Kit GenAI / Gemini Nano) — `checkStatus()` should return AVAILABLE/DOWNLOADABLE.
- **EXPECTED SIGNAL**: AICore available + a coherent text/image response → **a viable serving path for image/text on the Pixel 10 even if LiteRT-LM GPU is unusable** (audio still has no fallback). This is both a diagnostic data point and a candidate workaround (enable `aicoreAvailable()` + route on G5).
- **GOTCHA**: `aicoreAvailable()` is currently hard-gated to whatever `checkStatus()` returns; on the Pixel 9 it's false. On the Pixel 10 it may download a Nano model first (DOWNLOADABLE → DOWNLOADING). Audio requests cannot use AICore — they must surface a clear error, never crash.
- **VALIDATE**: AICore text/image inference result on `rango`.

### Task 7: Upstream / known-issue search
- **ACTION**: Search LiteRT-LM release notes + issues for Tensor G5 / Pixel 10 / inference SIGSEGV.
- **METHOD**: GitHub `google-ai-edge/LiteRT-LM` releases `0.12.0`–`0.13.1` CHANGELOG; issues search "Pixel 10", "G5", "SIGSEGV inference". Cross-reference the BuildId / version.
- **EXPECTED SIGNAL**: A documented G5 fix in a specific version (→ pin that exact version) or an open known issue (→ file/▲ it with this repro: backend-agnostic null-deref at first prefill on G5, byte-identical model serves on G4).
- **VALIDATE**: A cited release note or issue, or a clean "nothing public — file a new issue."

### Task 8: Decide remediation + write it up
- **ACTION**: Based on Tasks 3–7, pick the fix and apply/record it.
- **DECISION TREE**:
  - **Task 4 fixes it** → bump `litertlm` to the working version (verify no G4 regression) → PR. *(most likely)*
  - **Newer version doesn't fix it, AICore works (Task 6)** → enable AICore on Pixel 10 for image/text + a clear "audio unsupported on this device" error + a Pixel-10 pre-flight gate so LiteRT-LM's crashing path is never entered → PR. File upstream.
  - **Neither works** → add a Pixel-10 pre-flight guard that refuses LiteRT-LM serving with a clear error (no native crash-loop), document the limitation, file an upstream LiteRT-LM bug with the full repro, and treat the Pixel 10 as not-yet-supported.
  - **Task 3c shows a different model serves on G5** → it's the multimodal model on G5; consider a text-only/curated G5 variant + upstream report.
- **VALIDATE**: A committed fix (if any) with on-device proof on BOTH `comet` (no regression) and `rango` (fixed or graceful), + an updated SPIKE-FINDINGS/docs note.

---

## Testing Strategy

### Diagnostic matrix (the investigation's "tests")
| Probe | Device | Backend | Model | Expected discriminator |
|---|---|---|---|---|
| Baseline serve | `comet` (G4) | GPU | gemma-4-E4B (0b2a8980…) | 200 (known good) |
| Repro | `rango` (G5) | GPU | same | SIGSEGV (known) |
| Backend | `rango` (G5) | CPU | same | SIGSEGV (known — backend-agnostic) |
| Minimal call (3a) | `rango` (G5) | GPU/CPU | same, 1+1 tokens | crash isolates prefill/decode |
| Different model (3c) | `rango` (G5) | GPU | a 2nd text-only `.litertlm` | crash→engine(H1); serve→model(H2) |
| Version bump (4) | `rango` (G5) | GPU | same, litertlm 0.13.1 | serve→H5 fix; crash→escalate |
| Version regression (4) | `comet` (G4) | GPU | same, litertlm 0.13.1 | must stay 200 (no G4 regression) |
| AICore (6) | `rango` (G5) | NPU | Nano (text/image) | available + coherent → workaround |

### Edge Cases Checklist
- [ ] Single-token prefill/decode still crashes (rules out length/sampler).
- [ ] Text-only request (no audio/image) still crashes (already TRUE — rules out multimodal-input trigger; the audio_adapter is in the *model*, not the request).
- [ ] A second, simpler model on G5 (isolates engine vs model).
- [ ] New litertlm version does not regress the Pixel 9 (G4) live node.
- [ ] AICore audio request fails *gracefully* (clear error), never crashes.
- [ ] Pixel-10 pre-flight gate prevents the native crash-loop (if no version fix).

---

## Validation Commands

### Build / version change
```bash
cd Android/src
# NOTE: a litertlm version bump needs network (new AAR), NOT --offline:
./gradlew :app:compileDebugKotlin            # verify the 0.13.x API still compiles against RelaisEngine
./gradlew :app:assembleDebug                 # build the APK with the new runtime
```
EXPECT: BUILD SUCCESSFUL; if the `Engine`/`EngineConfig`/`Conversation` API changed, fix call sites in `RelaisEngine.kt`.

### On-device repro / fix (spare = `rango` G5; live = `comet` G4)
```bash
ADB=/home/user/Android/Sdk/platform-tools/adb; G5=57211FDCG0023C; G4=4A111FDKD0000C
# install + start + curl (per-device key). Reuse the f7ad3343 file already on G5.
$ADB -s $G5 install -r app/build/outputs/apk/debug/app-debug.apk
$ADB -s $G5 shell am start -n com.ventouxlabs.relais/cc.grepon.relais.RelaisControlActivity --es cmd start --es token <G5key>
$ADB -s $G5 forward tcp:8443 tcp:8443
curl -k -s --max-time 120 https://localhost:8443/v1/chat/completions \
  -H "Authorization: Bearer <G5key>" -H "Content-Type: application/json" \
  -d '{"model":"gemma-4-e4b-it","messages":[{"role":"user","content":"ping"}],"max_tokens":8}'
# crash signal: http=000 + tombstone in logcat -b crash. fix signal: http=200 {"content":...}.
```
EXPECT (after a successful fix): `http=200` on `rango`, AND `http=200` still on `comet` (no G4 regression).

### Crash capture
```bash
$ADB -s 57211FDCG0023C logcat -b crash -d | sed -n '/F DEBUG/,/__start_thread/p'   # full backtrace + registers
```

### Manual validation
- [ ] Repro the crash on `rango` from a clean start (Task 1).
- [ ] Run the minimal `benchmark(...)` probe — confirm crash without the HTTP/streaming layer (Task 3a).
- [ ] Bump litertlm, retest `rango` (Task 4) AND re-verify `comet` serves (no regression).
- [ ] If routing to AICore: confirm image/text serves and audio errors cleanly on `rango` (Task 6).

---

## Acceptance Criteria
- [ ] Root cause identified and **evidenced on-device** (which hypothesis, with the discriminating probe result).
- [ ] A remediation chosen per the Task 8 decision tree (version bump / AICore route / graceful-gate + upstream report).
- [ ] If a code fix: builds green, **no Pixel 9 (G4) regression**, and the Pixel 10 (G5) either serves or fails gracefully (no native crash-loop).
- [ ] Finding + remediation documented (SPIKE-FINDINGS/docs); upstream issue filed if it's a LiteRT-LM bug.

## Completion Checklist
- [ ] Crash artifact captured + (best-effort) symbolized.
- [ ] H5 (version) tested first; H1/H2 (engine vs model) discriminated via a 2nd model.
- [ ] Backend-agnostic nature re-confirmed if a fix changes it.
- [ ] No live-node (Pixel 9 / `comet`) disruption beyond brief, restorable testing; spare (`rango`) used for destructive/version work.
- [ ] Remediation has on-device proof on BOTH SoCs.
- [ ] Limitation/finding recorded for future Pixel-10 operators.

## Risks
| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Newer litertlm breaks the `Engine`/`EngineConfig` API | Med | Med | Step versions (0.12.0 before 0.13.1); fix `RelaisEngine` call sites; SPIKE-FINDINGS Q1 documents the 0.11 surface to diff against. |
| Version bump fixes G5 but regresses G4 (Pixel 9 live node) | Low | High | Always re-test `comet` after a bump; keep the live node on the proven version until both pass; test on the spare first. |
| AAR is stripped → no symbolization | High | Low | Don't block on it; device probes (H5/H1) are decisive; BuildId still identifies the binary for an upstream report. |
| Only one Pixel 10 → can't separate unit-fault (H6) from all-Pixel-10 | Med | Low | Treat as all-Pixel-10 (conservative); an upstream issue + a 2nd unit later confirms. |
| Native crash can't be caught in JVM → can't "handle" it | High | Med | Remediation must be a **pre-flight gate** (don't enter the crashing path on G5), not a try/catch. |
| litertlm download needs network (offline-build norm) | High | Low | The version-bump build is the one exception to `--offline`; do it with network, then return to offline builds. |

## Notes
- **This is the single most likely fix**: the project pins `litertlm 0.11.0`; `0.13.1` is current and `0.11.0` predates the Pixel 10 / Tensor G5 GA. Init-works/inference-crashes + backend-agnostic is the textbook "runtime predates the silicon." **Do Task 4 first** — it may close the whole investigation in one line.
- **Why CPU also crashing matters**: it rules out a pure GPU/OpenCL/`libOpenCL-pixel.so` fault and points at the core LiteRT-LM inference path (tokenizer/prefill/sampler/kv-cache) on G5 — consistent with a runtime-version gap.
- **The model is exonerated**: sha256 `0b2a8980…bd52e0` is byte-identical to the Pixel-9-serving model and identical across HF commits. Do not chase "bad build" theories — checksum already settled it (the lesson from this session: checksum bytes before concluding incompatibility).
- **Device facts**: live node = Pixel 9 `comet` `4A111FDKD0000C` (G4, serves; key `33b3e6d166e34695a5fee96a37e78cd4`); spare = Pixel 10 `rango` `57211FDCG0023C` (G5, crashes; session key `7c031901d2b447dca8ea5377803b972e`; already holds the `f7ad3343` model file). Always pass `-s <serial>`; `am instrument` is non-destructive; do version/destructive work on the spare.
- Related context: `[[relais-ondevice-verification]]` (memory) has the full crash + checksum trail; merged Phase B is `main` @ `09dcec5`.
