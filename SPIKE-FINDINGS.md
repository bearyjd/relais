# Gate 0.5 — Pre-Flight Spike Findings

> Settled facts from on-device investigation on **JD's Pixel 9 Pro Fold (Tensor G4 / zumapro, arm64-v8a)**.
> Feeds directly into `/goal relais-node-GOAL.md`. **Do not re-derive or contradict these.**
> Date captured: 2026-05-25. Gallery app version under test: 1.0.15. litertlm: `0.11.0`.

---

## TL;DR
- **Runtime foundation = LiteRT-LM (`com.google.ai.edge.litertlm.Engine`) on GPU.** Portable across Pixel 9 (now) and Pixel 10 (later). Full multimodal: **image + audio + text**, 32K context.
- **E4B on NPU is NOT available via LiteRT-LM on any device** — and not at all on the Pixel 9.
- **NPU is an opportunistic, Pixel-10-only, image+text-only backend** via AICore/Gemini Nano — never a gate, never for audio.
- Gates assert **"engine resident + correct multimodal response (+ tok/s floor)"**, never `backend == NPU`.
- **Proven on-device:** one resident GPU engine serves text + image + audio, headless. GPU baseline captured.

---

## Q1 — Can the active backend be read back programmatically? **NO.**
Decompiled `litertlm-android:0.11.0`. The public API exposes **no resolved/active-backend getter**:
- `Engine.getEngineConfig().backend` only **echoes the requested `Backend`** you constructed — not what the runtime resolved. If the native layer silently falls back, this still reports the request. Reading it proves nothing.
- `Engine` has only `isInitialized()`, `initialize()`, `createConversation()`, `createSession()`, `close()`. No `getActiveBackend()` / resolved-config / runtime capability query.
- `Capabilities` exposes only `hasSpeculativeDecodingSupport()` (static model introspection).

**Consequence for gate design:** prove backend behavior by **measured throughput** (`BenchmarkInfo` via `Conversation.benchmarkInfo` or top-level `benchmark(modelPath, backend, prefillTokens=256, decodeTokens=256, cacheDir=null)`), and otherwise assert **"resident + correct response."** On the AICore path the question is moot: `checkStatus() == AVAILABLE` + a successful inference *is* the NPU proof by construction (AICore only runs Nano on the Tensor NPU).

## Premise correction — "E4B runs on NPU" does NOT hold on this hardware
From the live `model_allowlist.json` (authoritative; fetched at runtime) and every bundled snapshot `1_0_4`→`1_0_15`:

| E4B path | Image | **Audio** | Context | Devices | Runtime |
|---|---|---|---|---|---|
| **AICore / Gemini Nano (NPU)** | ✅ | **❌** | Nano-managed (small) | **Pixel 10/11, Galaxy S26, SD-8850/MTK-6993 only** | ML Kit GenAI (`GenerativeModel`) |
| **LiteRT-LM (GPU)** | ✅ | ✅ | 32K | **any — Pixel 9 *and* 10** | `litertlm.Engine` |

- `Gemma-4-E4B-it` (the `.litertlm` file) is `accelerators: "gpu,cpu"` in **every** allowlist version — **NPU never offered.** The only LiteRT-LM NPU model anywhere is `Gemma3-1B-IT NPU` (a 1B model).
- The AICore E4B entry exists **only in the live allowlist** (repo snapshots are stale) and is gated by `Utils.isAICoreSupported()` — an **exact `Build.MODEL` string match** against the allowed groups. `Pixel 9 Pro Fold` is in **none**.
- Pixel quirk (`ModelAllowlist.kt:115-118`): on any Pixel the UI **relabels `npu`→`tpu`**, and `LlmChatModelHelper.kt:91-106` routes **both `NPU` and `TPU` labels → `Backend.NPU`**. This is the likely source of the original "E4B on NPU" misread — E4B actually defaults to **GPU** here.

## Q2 — Headless / screen-off / Doze survival — **NOT YET TESTED** (for the `/goal` run).
## Q3 — Inbound bind `0.0.0.0:<port>` + LAN curl — **NOT YET TESTED** (for the `/goal` run).

---

## PROVEN on-device (do NOT re-test)
Single resident `litertlm.Engine`, `Backend.GPU()` (visionBackend=GPU, audioBackend=CPU), one `initialize()`, headless (instrumented process, no UI):

| Stage | Result |
|---|---|
| Engine init (resident) | ~23 s, `isInitialized()=true` |
| **TEXT** ("reply one word: ping") | `"ping"` ✅ |
| **IMAGE** (red 64×64 PNG) | `"Red"` ✅ |
| **AUDIO** (synthetic WAV) | processed end-to-end ✅ (modality works; can't interpret a sine tone) |
| Engine close | clean ✅ |

### GPU baseline (E4B, Tensor G4, `gpuBenchmark`)
| metric | value |
|---|---|
| **decode** | **5.80 tok/s** |
| prefill | 111.1 tok/s |
| time-to-first-token | 2.48 s |
| (cold init) | 44.6 s |

**Gate floor:** `decode > 4.0 tok/s` (below that implies a regression off GPU; CPU is far slower).

**Throughput levers tried:** **Speculative decoding** — E4B reports support
(`Capabilities.hasSpeculativeDecodingSupport()=true`) but **measured a regression**: ~2.56 tok/s
on vs ~5.6 tok/s off (draft overhead > gains, no draft model bundled). **Left OFF.** Real speedup
on this device needs either the **NPU (Pixel 10 / AICore)** or the **MTP-enabled model variant**
(`gemma4_4b_..._thinking.litertlm`, a separate download per the allowlist `updatableModelFiles`).
~5.5 tok/s decode is the practical ceiling for E4B on the Tensor-G4 GPU via this runtime.

---

## Architecture (settled direction)
- **Foreground Service** holding ONE resident GPU `Engine`, alive across requests; streams tokens.
- **Modality-aware backend Selector** above the existing seam (`runtime/ModelHelperExt.kt` → `Model.runtimeHelper`, `LlmChatModelHelper` vs `AICoreModelHelper`, both `: LlmModelHelper`):
  - request has **audio** → **GPU/LiteRT-LM** (only option)
  - **image/text** + AICore available on device (Pixel 10+) → **AICore/NPU**
  - otherwise → **GPU/LiteRT-LM**
- **Endpoints** reuse existing **Ktor** dep + **MCP manager** (`McpManagerViewModel`): HTTP on `0.0.0.0:<port>` for LAN, and/or MCP tools for on-device apps. Normalize one multimodal request schema → per-runtime adapter.

`EngineConfig` multimodal recipe (from `LlmChatModelHelper.kt`): `backend=Backend.GPU()`, `visionBackend=Backend.GPU()`, `audioBackend=Backend.CPU()`, `maxNumTokens`, `cacheDir = getExternalFilesDir(...)`.

---

## Build / test / device facts (for the `/goal` run)
- **Model:** `Gemma-4-E4B-it` → `gemma-4-E4B-it.litertlm` (3.66 GB, `litert-community/gemma-4-E4B-it-litert-lm`). Already on device at `/sdcard/Android/data/com.google.aiedge.gallery/files/relais/` (and a copy at `/data/local/tmp/relais/`).
- **SELINUX GOTCHA:** the app process **cannot** read `/data/local/tmp` — model must live in the app's own files dir; `cacheDir` must be app-writable.
- **SDK / build:** `ANDROID_HOME=/home/user/Android/Sdk`; build from `Android/src` via `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest`.
- **App IDs:** debug = `com.google.aiedge.gallery` (coexists with release `com.google.ai.edge.gallery`).
- **Validation pattern (reuse this):** `Android/src/app/src/androidTest/java/com/google/ai/edge/gallery/RelaisBackendBenchmarkTest.kt`. Run a gate:
  ```
  adb shell am instrument -w -e class com.google.ai.edge.gallery.RelaisBackendBenchmarkTest#<method> \
    -e model /sdcard/Android/data/com.google.aiedge.gallery/files/relais/gemma-4-E4B-it.litertlm \
    com.google.aiedge.gallery.test/androidx.test.runner.AndroidJUnitRunner
  ```
  Results via `adb logcat -s RelaisBench`.

## Gate results — node built & validated on Pixel 9 (2026-05-25)
Implemented in `app/.../relais/` (`RelaisEngine`, `RelaisNodeService`, `RelaisHttpServer`,
`BackendSelector`); validated via `RelaisNodeTest.kt` + host curl. All on-device, real runs:

- **G1 ✅** Resident multimodal engine IN the foreground service (`RelaisNodeService`, dataSync FGS +
  partial wake lock). `resident=true`; text→"ping", image(red)→"Red", audio→processed (routed GPU);
  streamed decode **5.63 tok/s** (>4.0 floor). Throughput timed by token streaming (BenchmarkInfo
  only populates via `benchmark()`, per Q1).
- **G2 ✅** Survival under **real Doze** (elapsed-time, not forced): screen off + battery
  unplugged-sim, real 5-min screen-off, then real ~15-min wait during which the device entered
  Doze on its own (`deviceidle=IDLE`). LAN inference then succeeded in 7.6s @ **5.37 tok/s** with a
  correct full-sentence answer — resident engine not evicted. Needed: foreground service (dataSync)
  + partial wake lock (both in impl). (A faster forced-idle pass corroborated this @5.59 tok/s.)
- **G3 ✅** Endpoint bound `0.0.0.0:8080` (raw `ServerSocket`, no dep). **Real LAN inbound** from a
  separate host to `192.168.68.57:8080`: `/health` ok; `/generate` text→"ping", **text+image→"Red"**,
  sentence gen @ 5.6 tok/s. Also reachable via adb-forward.
- **G4 ✅** `BackendSelector`: audio→GPU always; image/text→AICore on Pixel 10+ else GPU. The NPU
  path (`RelaisAicore`, ML Kit GenAI / Gemini Nano) is **fully wired with a real `checkStatus()`
  probe** — not a stub. On this Pixel 9 the probe returns `FEATURE_NOT_FOUND` (ML Kit error 606),
  empirically confirming the device is excluded from AICore, so all traffic resolves to GPU. The
  `g4b_npuAicorePathOrSkip` test runs the real NPU generation on a Pixel 10+ and auto-skips as
  **UNVERIFIED** here — the deferred gate closes by simply connecting a Pixel 10, no code change.

## Robustness (workstream 3 — validated on Pixel 9)
- **Battery saver ✅** — inference under `low_power=1` works (6.0 tok/s).
- **Real Doze ✅** — engine survives genuine Doze while running (see G2).
- **Crash / OOM recovery ✅ (after a fix).** FINDING: `START_STICKY` alone did **not** restart the
  foreground service after an app-process crash (no recovery in 250s), and a plain background alarm
  hit `ForegroundServiceStartNotAllowed` (Android 12+). FIX: `RelaisWatchdog` — a self-rescheduling
  **exact** alarm (`setExactAndAllowWhileIdle` + `USE_EXACT_ALARM`), which grants the temporary
  FGS-start exemption. After a crash the node is revived in **~55s** (new PID, inference returns
  "Alive"). Gated by a `shouldRun` latch so an intentional stop is honored.
- **Not yet covered (longer soak):** overnight/multi-hour Doze, true low-memory OOM-kill (vs
  simulated crash), wifi drop/reconnect with IP change. Left for a soak test.

## Honesty / stop conditions for the `/goal` run
- Do **not** claim NPU on the Pixel 9. Do **not** add audio to the AICore path.
- Implement the AICore/NPU branch but **guard it behind a runtime `checkStatus()`/device probe**; on the Pixel 9 it must **skip with a logged TODO** and be marked **UNVERIFIED** (no Pixel 10 to validate).
- **Validation is mandatory per gate** — end each with an on-device proof captured in logcat/test result, never an assumption. If a gate can't be proven on hardware in hand, STOP and say so.
