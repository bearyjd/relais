# Tensor TPU spike — Gate T-0 sample

Standalone app for **Gate T-0** of [`docs/tensor-tpu-spike-plan.md`](../../docs/tensor-tpu-spike-plan.md)
(read its 2026-07-09 update first). Kept outside the Relais app on purpose: a clean sample removes
Relais as a variable. Toolchain matches the main project (AGP 8.8.2 / Kotlin 2.2.0 / compileSdk 35).

**Pinned recipe (do not drift):** `com.google.ai.edge.litert:litert:2.1.1` +
`libLiteRtDispatch_GoogleTensor.so` from the **v2.1.1** `litert_npu_runtime_libraries.zip`.
LiteRT 2.1.2–2.1.5 ship no dispatcher; mixed versions silently fall back to CPU or SIGSEGV
([LiteRT #7787](https://github.com/google-ai-edge/LiteRT/issues/7787)).

## 1. Fetch the dispatcher (not in git)

```bash
./fetch-npu-libs.sh
```

Drops `libLiteRtDispatch_GoogleTensor.so` into `app/src/main/jniLibs/arm64-v8a/`.

## 2. Stage an AOT-compiled model

The v2.1.1 drop has **no on-device (JIT) compiler plugin for Google Tensor** — only Qualcomm and
MediaTek get one. The model must be **AOT-compiled for Tensor G5**:

- easiest: a precompiled NPU model from <https://huggingface.co/litert-community>
  (pick a small classic vision model first — prove TPU execution before any LLM), or
- the LiteRT AOT toolchain (Linux x86_64 + Bazel; see the Tensor SDK docs).

A plain un-compiled `.tflite` on the strict-NPU case is **expected to fail** — useful as a
dispatcher-loaded check, but not a T-0 pass.

```bash
adb -s 57211FDCG0023C push model.tflite \
  /sdcard/Android/data/cc.grepon.relais.spike.tensortpu/files/t0-model.tflite
```

## 3. Build & install (uses the main project's Gradle wrapper)

```bash
../../Android/src/gradlew -p . :app:assembleDebug
adb -s 57211FDCG0023C install -r app/build/outputs/apk/debug/app-debug.apk
```

## 4. Run & read the result

Launch "Tensor TPU T-0" on the device, watch:

```bash
adb -s 57211FDCG0023C logcat -s RelaisTpuT0
```

The app runs CPU → NPU-lenient → NPU-strict and logs per-inference latency. Interpretation:

| Observation | Meaning |
|---|---|
| strict-NPU create/run OK, latency ≪ CPU | T-0 pass — proceed to T-2 hardware proof |
| strict-NPU fails, lenient "works" at ~CPU latency | silent fallback — the #7787 failure mode |
| strict-NPU fails at create with dispatcher error | dispatcher loaded but rejected model/device — capture the exact message |

## 5. T-2 evidence (self-report is not proof)

While an NPU run loops, corroborate on real hardware activity, then repeat with the model forced
to CPU/GPU as the negative control:

```bash
adb -s 57211FDCG0023C shell 'ls /dev/edgetpu* /dev/gxp* 2>/dev/null; ls /sys/class/edgetpu 2>/dev/null'
adb -s 57211FDCG0023C shell dmesg | grep -iE "edgetpu|gxp|tpu"   # needs adb root or a permissive build
adb -s 57211FDCG0023C shell cat /sys/class/power_supply/battery/current_now
```

## Caveats

- The LiteRT Next Kotlin API surface in 2.1.1 is young; if `CompiledModel.Options`/`close()`
  signatures differ at compile time, adjust `MainActivity.kt` — the strict-vs-lenient distinction
  (`Options(setOf(Accelerator.NPU))` vs `Options(Accelerator.NPU)`) is the part that matters.
- Device hygiene per the spike plan: uninstall this app and remove the staged model when done.
