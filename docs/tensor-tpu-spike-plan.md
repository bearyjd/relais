# Tensor G5 TPU via the Google Tensor SDK — Spike & Verification Plan

**Status:** ✅ **T-2 PASSED on-device (2026-07-09)** — Gemma 3 1B executes on the Tensor G5 TPU
under GrapheneOS, proven by the dedicated TPU power rail (see "T-2 RESULT" below). Beta access is
**not required for Pixel 10** and a public, Google-validated runtime config exists (2026-07-09
update). T-0 sample at `spikes/tensor-tpu-t0/`. **Parent:** Epic E4 (#98). **Device:** rango —
Pixel 10 Pro Fold / Tensor G5 / GrapheneOS with sandboxed Play (serial `57211FDCG0023C`).

---

## ✅ T-2 RESULT — TPU execution proven on rango (2026-07-09)

Ran the validated stack (`litert:2.1.1` dispatcher + a Google-published `_Google_Tensor_G5`
`.litertlm`) via the official LiteRT `sample_app_tpu`, patched (blockers below). Evidence:

- **Engine log:** `Trying backend: NPU → Successfully loaded libLiteRtDispatch_GoogleTensor.so →
  Backend NPU SUCCEEDED → Initialization SUCCEEDED on backend: NPU`. No SIGSEGV, no #7787 failure.
- **Dedicated TPU power rail** (`power.rails.tpu`, sampled via `perfetto --txt` android.power):
  **0.0 mW idle → sustained ~442 mW** (343–534 mW, every 0.5 s across the full 25 s generation).
  The anti-fallback proof T-2 requires — an independent hardware meter, not a self-report.
  Sampler: `spikes/tensor-tpu-t0/collect-evidence.sh` (thermal) + a perfetto power-rail trace.
- **GPU rail negative signal:** `power.S2S_VDD_GPU` stayed at idle (~29 mW) during the NPU run.
- **App self-report:** green NPU badge, **TTFT 267 ms, 12.1 tok/s** decode (Gemma 3 1B, q8).

Model used: `Gemma3-1B-IT_q8_ekv1280_Google_Tensor_G5.litertlm` (litert-community, Gemma-gated).
A `_Google_Tensor_G5` build also exists for **Gemma 4 E2B** (ungated, Apache-2.0) — the *same model
class* as Relais's resident E2B, so the TPU is not limited to a tiny-model lane. E4B has no public
G5 build (compiling one needs the still-ACL'd beta compiler).

**Three blockers found (none a hardware limit — all shape the T-4 work in Relais):**
1. The sample **gated NPU on `com.google.android.aicore` being installed** — wrong; the TPU
   dispatcher is unrelated to AICore/Gemini Nano. GrapheneOS lacks AICore, so stock behavior refuses
   the TPU. Fix = gate on the dispatcher `.so` loading, not AICore. **Relais has the same bug** —
   `BackendSelector`/`RelaisEngine` route NPU through `RelaisBackend.NPU_AICORE`; T-4 must add a
   dispatcher-gated `Backend.NPU` lane instead.
2. The app assumed multimodal and failed `TF_LITE_VISION_ENCODER not found`; its text-only retry
   recovered. Relais already has this text-only fallback in `RelaisEngine.ensureInitialized`.
3. **Model staging on GrapheneOS:** a shell-pushed `.litertlm` under `/sdcard/Android/data/<pkg>`
   is unreadable by the app uid (FUSE ignores `chmod`); it must land in the app's own private
   storage (app-owned). T-4 staging must not rely on adb-pushed sdcard files.

T-1 (Play Feature Delivery) stays deferred — the spike used a bundled/fused dispatcher over adb,
so production packaging is the only open question there. **Next: T-3 perf table, then T-4 wiring.**

---

## ✅ T-3 RESULT — perf table, measured INSIDE Relais's stack (2026-07-10)

Harness: `RelaisBackendBenchmarkTest#throughputBenchmark` (new probe leg) — wall-clock TTFT +
decode tok/s over a streaming Conversation, run via `am instrument` in the izzy debug app on rango,
with perfetto power-rail traces per leg. 1B-class model on each backend (best-available litertlm
per backend: the G5-AOT q8 file cannot run on GPU, and no q4 TPU build exists — quant differs, so
treat the comparison as lane-vs-lane, not quant-controlled):

| leg | model | decode | TTFT | engine init | active rails (avg mW, 90 s window) |
|---|---|---|---|---|---|
| **TPU** | Gemma3-1B q8 ekv1280 G5-AOT | **8.51 tok/s** (431 tok) | **0.247 s** | **1.15 s** | tpu 177, ddr 142, cpu 45, **gpu 0** |
| **GPU** | gemma3-1b-it-int4 (q4) | 3.03 tok/s (330 tok) | 0.570 s | 7.26 s | gpu 91, ddr 60, cpu 91, **tpu 1** |

**Verdict: the TPU lane wins decisively — ~2.8× decode, ~2.3× faster TTFT, ~6× faster engine init,
at roughly comparable energy-per-token** (~75–80 mJ/tok both lanes, window-normalized). The rail
cross-controls are clean in both directions (TPU rail dark during the GPU run and vice versa).
GPU-lane numbers are the same litertlm 0.12.0 runtime; note 1B-q4-on-GPU here (3 tok/s) is a
cold-cache single-shot, not a tuned resident baseline.

**Three MORE integration blockers found (now the definitive T-4 recipe, each isolated on-device):**
1. **litertlm 0.11.0 SIGABRTs on `Backend.NPU`** at engine init (same liblitertlm_jni abort as the
   `benchmark()` path) — the TPU lane REQUIRES **litertlm 0.12.0** (bumped; compiles clean against
   all Relais code).
2. **The LiteRT v2.1.1 release-zip dispatcher (409,920 B) also SIGABRTs** with 0.12.0 — the working
   dispatcher is the **litert-samples build (330,960 B)**, now fetched SHA-256-pinned by
   `scripts/fetch-tensor-dispatcher.sh`. (Supersedes the "pin the v2.1.1 zip" guidance for litertlm
   use; the T-2 run in the sample app had been using this newer dispatcher all along.)
3. **Custom `SamplerConfig` breaks the NPU compiled-model executor** mid-decode
   (`new_step must be <= TokenCount(), got 27 vs 26`, deterministic at the same token) — the TPU
   lane must use the default conversation sampler until upstream fixes it. `maxNumTokens` was NOT
   the cause (custom sampler fails at 1024 and 1280 alike; default sampler works).
4. The library's **`benchmark()` API SIGABRTs on `Backend.NPU`** regardless — measure TPU throughput
   via streaming wall-clock (as production does), never `benchmark()`.

**T-4 consequences:** dispatcher-gated `Backend.NPU` lane in `RelaisEngine` + litertlm 0.12.0 +
default-sampler-only on the TPU path (requests carrying temperature/top_p/seed must fall to GPU or
ignore-with-warning until the executor supports samplers) + app-owned model staging.

---

## ✅ T-4 RESULT — Relais's own engine serves from the TPU (2026-07-10)

`RelaisEngine` now has the TPU lane, and it is **proven on the production path**: `TensorTpuProbe`
(new probe) drives the real `ensureInitialized` → `generate` chain and passed on rango —
`backend=TPU_LITERTLM, 8.78 tok/s, coherent text`, TPU power rail active while the GPU rail stayed
at 0. The engine log shows the full ladder working: `TPU lane selected … (maxNumTokens=1280)` →
multimodal attempt degrades correctly → `Resident text-only engine ready: true`.

Implementation (all landed):
- **`RelaisTpuLane`** (pure, JVM-tested): AOT-model detection by the litert-community filename
  convention (`…_Google_Tensor_…`), fixed-KV token budget from the `ekv` marker, custom-sampler
  detection. The lane gate = AOT filename AND `libLiteRtDispatch_GoogleTensor.so` present in
  nativeLibraryDir — **dispatcher-gated, NOT AICore-gated** (the old `NPU_AICORE` branch is
  untouched and remains dead on GrapheneOS).
- **`RelaisBackend.TPU_LITERTLM`**: requests served by a TPU-resident engine report it (metrics,
  usage, mDNS unchanged otherwise). Selector semantics preserved — the label is derived post-init
  from `RelaisEngine.residentIsTpu`.
- **Sampler gating**: both generate paths (streaming + tools) drop to the engine-default sampler on
  the TPU lane and log a warning when a request carried temperature/top_p/seed (blocker 3).
- **Ladder fix**: on the TPU lane the multimodal probe must use `visionBackend = NPU` — probing an
  AOT model with `visionBackend = GPU` fails as `Input tensor not found` (NOT classified as a
  missing encoder) and aborts init instead of degrading to text-only.
- **Gotcha for operators:** the lane keys on the FILENAME — a G5-AOT model staged under a neutral
  name (e.g. `g5-1b.litertlm`) silently gets the GPU lane and fails `Input tensor not found`. Keep
  the upstream `…_Google_Tensor_G5.litertlm` names.

**✅ E2B-G5 resident test (2026-07-10, same probe): the flagship model class runs on the TPU —
MULTIMODAL.** `TensorTpuProbe` with `gemma-4-E2B-it_Google_Tensor_G5.litertlm` (3.95 GB) → OK:
- `TPU lane selected … (maxNumTokens=4096)` — the E2B AOT build's KV cache is 4096, matching the
  engine default (no ekv marker needed).
- **`Resident multimodal engine ready: true`** — the vision/audio encoder rung initialized on the
  TPU lane (`visionBackend=NPU`); this is NOT the text-only fallback. Multimodal-on-TPU is real.
- **11.07 tok/s** decode via `RelaisEngine.generate`, `backend=TPU_LITERTLM`, init **5.8 s** for
  3.95 GB. Rails: TPU active, GPU 0.0 mW — clean cross-control.
- Context: the historical E-series GPU resident baseline is ~5.6 tok/s → **~2× the flagship
  baseline**, with TTFT/init an order faster. This makes E2B-G5 the natural default resident on
  Tensor G5 devices once the allowlist carries it. **Vision + audio through the TPU-lane encoders
  VERIFIED (2026-07-10, `TensorTpuProbe#tpuLaneServesMultimodal`): a red image → "Red", a
  sine-tone WAV → text response, both `backend=TPU_LITERTLM`.**

**Remaining to make it operator-usable (post-spike backlog):** the allowlist has no G5-AOT entries
yet (TPU models only load via probe/manual staging today — add `…_Google_Tensor_G5` variants gated
to Tensor G5 devices); production
dispatcher packaging for release builds (T-1's Play Feature Delivery question) — the dispatcher
currently ships in DEBUG builds only.

---

This plan answers exactly one question: **does the Play-delivered Tensor TPU runtime actually
execute a model on the Google Tensor G5 TPU under GrapheneOS's sandbox — or does it silently fall
back to GPU/CPU, or route through privileged AICore that GrapheneOS blocks?** Every gate below
follows the SPIKE-FINDINGS rule: *prove it on-device, never assume; a fallback that produces a
correct answer on the wrong backend is a FAIL.*

---

## Background — the learnings that got us here (2026-07-08 research thread)

Durable conclusions, so nobody re-derives them:

1. **AICore / Gemini Nano is structurally dead on GrapheneOS.** GrapheneOS runs only on Pixels →
   Pixels use Tensor → Tensor's NPU is reachable only via AICore → GrapheneOS won't ship the AICore
   system app, and sandboxed Play can't install privileged system components. Verified on-device:
   `g4b_npuAicorePathOrSkip` on rango logged `AICore/NPU available=false` (SPIKE-FINDINGS.md G4b,
   #124). This path is **not** what this plan pursues.
2. **Reverse-engineering the Tensor TPU is research-grade, not a hack.** The *driver* layer has a
   foothold (GPL Pixel kernel sources + Google's open `gasket-driver` + Coral `libedgetpu` expose
   the ioctl/mailbox ABI), but the *ISA + compiler* layer has **no published spec** (OpenTPU's own
   maintainers: "no publicly available interface or spec for TPU"). Add a likely firmware-attestation
   cap (cf. Nouveau's signed-firmware reclocking wall) and the GrapheneOS SELinux wall, and a
   working open delegate is person-decades of effort with no community flywheel. Not a path.
3. **The Google Tensor SDK is the official, reverse-engineering-free path — and it targets rango.**
   - Beta (graduated from the Experimental Access Program); sign-up:
     <https://developers.google.com/edge/tensor-sdk>.
   - Announcement lists **Pixel 10 Pro Fold** = Tensor G5 (rango's exact SoC).
   - Runs **Gemma 3 1B** on the TPU (+100 classic ML models; PyTorch/TFLite via LiteRT Torch).
     Gemma 3 1B is **already in the Relais allowlist** (`gemma3-1b-it-int4.litertlm`).
   - Built on **LiteRT** — the same runtime the resident engine uses; `Backend.NPU(nativeLibraryDir)`
     is already wired in the AAR + the benchmark screen.
   - Delivered via **Play Feature Delivery** (runtime + compiler libs) **+ AI Packs / Play for
     On-device AI** (model files) — *not* a bundled APK lib; no documented non-Play path.
   - **GrapheneOS's own features page** confirms Play Store services are "fully available
     including... Play Feature Delivery" — so the delivery mechanism works on sandboxed Play. That
     collapsed the blocker from a research project to the single testable question this plan asks.

**The unverified link (this plan):** after Play Feature Delivery hands over the runtime, does it
reach the G5 TPU under GrapheneOS's sandbox, or still require privileged Private Compute Core /
AICore? Design hints favor the *direct* path (advertises "direct access to dedicated TPU hardware,"
runs classic ML not just Nano, LiteRT AOT compiler = Qualcomm-style app→delegate→driver). Unconfirmed.

**Honest expectations (carry into every gate):**
- **Gemma 3 1B is small** — a latency/efficiency win, **not** a replacement for the E2B/E4B
  multimodal quality served on GPU. Success = a fast small-model TPU lane *alongside* the GPU lane.
- **It couples to Play-delivered components** → lives in the `full`/Play flavor, **not**
  `degoogledOpen`. Sandboxed Play (fine on GrapheneOS), not privileged GMS — but a values tradeoff
  for a fully-degoogled node.

---

## 2026-07-09 update — the beta gate collapsed (verified)

Findings that supersede parts of the background section above; each was verified today, not assumed:

1. **No beta sign-up needed for Pixel 10.** The Tensor SDK docs
   (<https://developers.google.com/edge/litert/next/tensor-sdk>) state the beta sign-up
   prerequisite **"does not apply to Pixel 10 devices."** `Tensor_G5` is the listed supported SoC.
   The BLOCKED status this plan shipped with was wrong for rango.
2. **Google-validated public config:** `com.google.ai.edge.litert:litert:2.1.1` (Maven) **+**
   `litert_npu_runtime_libraries.zip` from the GitHub **v2.1.1** release. Verified: the zip exists
   and contains `google_tensor_runtime/src/main/jni/arm64-v8a/libLiteRtDispatch_GoogleTensor.so`
   (~400 KB), packaged as a ready-made dynamic-feature Gradle module. Do **not** use litert
   2.1.2–2.1.5 — those release tags ship no dispatcher at all, and the version-mixed combo either
   silently falls back to CPU or SIGSEGVs
   ([LiteRT #7787](https://github.com/google-ai-edge/LiteRT/issues/7787)). Pin both sides to 2.1.1.
3. **Google Tensor is AOT-only in this drop.** The `_jit.zip` variant ships on-device compiler
   plugins for Qualcomm/MediaTek but **only the dispatcher** for Google Tensor — there is no
   `libLiteRtCompilerPlugin_GoogleTensor.so`. The model must be **AOT-compiled for Tensor G5**
   (LiteRT AOT toolchain, or a precompiled NPU model from `litert-community` on Hugging Face).
   A plain un-compiled `.tflite` on a strict-NPU request is *expected* to fail — that failure is
   diagnostic, not a spike result.
4. **The Play coupling is weaker than assumed.** The dispatcher module's manifest sets
   `dist:fusing include="true"` and the lib is plain `jniLibs` — a fused/universal APK carries it
   inline and installs via `adb`, so the spike does **not** need Play Feature Delivery at all
   (T-1 below is therefore a production-packaging question, not a spike prerequisite). The module
   targets device groups `Google_Tensor_G3/G4/G5`, so G3/G4 Pixels are nominally in scope too.
   If T-2 passes via the bundled-lib path, revisit the `degoogledOpen` exclusion in T-4.
5. **Counter-evidence to hold in mind:** the #7787 reporter says even the 2.1.1+2.1.1 pairing
   SIGABRTed on their Pixel 10 Pro XL AOT path (June 2026). Google says validated; the reporter
   says crash. T-2's anti-fallback proof settles it on rango.

---

## Gate T-0 — SDK in hand + a minimal TPU sample builds

**Prereq:** ~~accept the Tensor SDK Beta~~ none for Pixel 10 (2026-07-09 update, item 1). The
pinned recipe is `litert:2.1.1` + the v2.1.1 zip's `libLiteRtDispatch_GoogleTensor.so`.

**Do:** the standalone sample is scaffolded at **`spikes/tensor-tpu-t0/`** (kept *outside* the
Relais app — a clean sample removes Relais as a variable; toolchain matches Relais: AGP 8.8.2 /
Kotlin 2.2.0 / compileSdk 35 / minSdk 31). Run its `fetch-npu-libs.sh` to pull the dispatcher into
`jniLibs` (binary stays out of git), stage an **AOT-compiled** model (update item 3), build,
`adb install`, run. The app attempts CPU → NPU-lenient → NPU-strict in sequence and logs each
outcome + per-inference latency to logcat tag `RelaisTpuT0`. Start with the smallest AOT-compiled
classic model, **not** Gemma 3 1B — the 1B path adds the LiteRT-LM layer on top; prove raw TPU
execution first, then graduate.

**Pass:** the sample builds, installs on rango, and the strict-NPU request either runs or fails
with a *dispatcher-level* error (proves the dispatcher loaded). **Fail →** capture the exact
Gradle/SDK requirement or load error that blocks; that requirement itself is a finding about
degoogled feasibility.

---

## Gate T-1 — Play Feature Delivery actually delivers the TPU runtime on GrapheneOS

**Demoted to a production-packaging question (2026-07-09 update, item 4):** the spike bundles the
dispatcher via `jniLibs` and installs over adb, so T-2 does **not** wait on this gate. Run T-1
after T-2 passes, to decide the production delivery story (bundled lib vs Play Feature Delivery).

The docs say the runtime arrives via Play Feature Delivery;
GrapheneOS says it supports that — verify it *for this specific module* on rango.

**Do:** install the T-0 app via the sandboxed Play Store (not `adb install` — Play Feature Delivery
needs Play as the installer). Trigger the on-demand feature-module fetch. Watch:
```
adb -s 57211FDCG0023C logcat | grep -iE "SplitInstall|FeatureDelivery|PODAI|tensor|litert|tpu|edgetpu"
adb -s 57211FDCG0023C shell pm dump <pkg> | grep -iE "splits|dexModules|feature"
```

**Pass:** the TPU runtime/compiler split module downloads and the app reports it present. **Fail →**
sandboxed Play won't deliver *this* module (privileged vs standard delivery). Try: sideload the
split APKs manually; if only manual works, the path needs full GMS or is Play-privileged — record
that as the GrapheneOS verdict and stop.

---

## Gate T-2 — PROOF the model runs on the TPU (the crux, anti-fallback)

A correct completion is **not** proof — GPU/CPU produce correct completions too. Prove the TPU
executed:

**Primary — the SDK's own backend report:** query whatever accelerator/backend the LiteRT runtime
resolved (Tensor SDK exposes the selected accelerator). It must name the TPU, not GPU/CPU.

**Corroborate — TPU hardware activity (independent of the SDK's self-report):**
- TPU driver nodes light up during inference: watch `/sys/class/edgetpu/*`, `/dev/edgetpu*` /
  `/dev/gxp*` (G5 codename varies), and kernel logs (`adb shell dmesg | grep -iE "edgetpu|gxp|tpu"`)
  before/during/after a generate.
- Power/thermal signature differs from GPU: sample `/sys/class/power_supply/*/current_now` and the
  thermal zones during a TPU run vs a forced-GPU run.

**Negative control (mandatory):** force the same model onto GPU (`Backend.GPU()`, the current
resident path) and confirm the TPU indicators that lit up in the positive case stay dark. Without
the negative control, "TPU node active" could be ambient noise.

**Pass:** backend report says TPU **AND** at least one independent hardware indicator confirms TPU
activity that the GPU negative control does not show. **Fail (delivered but no TPU) →** the runtime
routes through privileged PCC/AICore after all → dead on GrapheneOS, consistent with the G4b AICore
verdict; record and stop.

---

## Gate T-3 — Is it worth it? Perf vs the GPU baseline

Only if T-2 is green.

**Do:** on rango, measure Gemma 3 1B on TPU vs the same model on GPU — decode tok/s, prefill,
time-to-first-token, sustained power (mW), and thermal behavior over a 5-min loop. GPU reference:
the resident engine's ~5.6 tok/s E-series baseline (re-measure 1B-on-GPU for an apples-to-apples
number; 1B on GPU will be much faster than E4B, so the honest comparison is 1B-TPU vs 1B-GPU).

**Pass = decision input, not a gate:** TPU wins clearly on tok/s **or** power-per-token → worth
integrating. Marginal → note it and shelve (a 1B lane may not justify the Play coupling).

---

## Gate T-4 — Resident-node integration sketch (only if T-2 green)

Wire the Tensor SDK into `RelaisEngine` as a **G5 TPU acceleration lane**, gated behind
`BackendSelector` like the existing AICore branch:
- New `RelaisBackend.TPU_TENSOR` (or reuse the wired `Backend.NPU` path) selected when the SDK
  reports the TPU available **and** the resident model is a TPU-compiled Gemma 3 1B.
- Flavor-gate to `full` (Play Feature Delivery dependency); `degoogledOpen` stays GPU-only and
  honest about it.
- Availability probe mirrors `RelaisAicore.available()` (cache + fail-safe to GPU). Never a silent
  downgrade the other way: if TPU is requested but unavailable, fall to GPU with a logged reason.
- Tests: a pure `BackendSelector` case for the new lane (JVM) + an on-device probe
  (`TensorTpuProbe.kt` in `androidTestFull`, single `am instrument` line in its header, matching the
  existing probe convention) that re-runs T-2's proof.

This is a real feature on an official SDK — **zero reverse-engineering** anywhere in it.

---

## Decision tree (one glance)

| Outcome | Meaning | Next |
|---|---|---|
| T-1 fails | Sandboxed Play won't deliver the module | Needs full GMS / Play-privileged → dead on GrapheneOS. Document, stop. |
| T-1 ok, T-2 fails | Delivered but no TPU exec (routes via privileged PCC/AICore) | Dead on GrapheneOS, matches G4b. Document, stop. |
| T-2 passes | **TPU works on GrapheneOS** | T-3 perf → T-4 integrate. The reversal is real. |

## Device hygiene (after)
Uninstall the sample/test app, remove any staged TPU-compiled model, leave rango's node build +
staged E2B as found. rango has a secure lockscreen now → needs JD-unlock for any screencap/tap step.

---

*Sources: Tensor SDK — developers.google.com/edge/tensor-sdk + the Google Developers Blog Beta
announcement; NPU delivery — developers.google.com/edge/litert/next/npu (Play for On-device AI);
Play Feature Delivery on GrapheneOS — grapheneos.org/features. Full trail in memory
`relais-tensor-tpu-path`.*
