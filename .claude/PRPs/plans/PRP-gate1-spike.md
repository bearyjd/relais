# PRP — Gate 1: Spike (Real Model on NPU, One Curl)

> Drop in `.claude/PRPs/plans/`. Branch: `feat/relais-gate1-spike`.
> Depends on Gate 0 (harness + `FakeEcho` + green build).
> Per `model-routing` v5.0.0: delegated to Claude Code CLI; **security-sensitive** (binds a network endpoint) → Codex adversarial pass runs alongside `/devils-advocate`.

## Objective
Forked app runs **Gemma 4 E4B on the NPU** and answers **one `curl`** to `POST /v1/chat/completions` over the LAN. Proves the real seam end-to-end (real engine, real NPU, real wire).

## Pre-flight status
- ✅ E4B-on-NPU confirmed working via stock Gallery APK on this device (Gate 0.5, Q-already-confirmed).
- ⚠️ NPU backend *programmatic readback* — resolve in Gate 0.5 Q1; if absent, this PRP's §smoke item 1 becomes a benchmark-threshold test instead of a direct backend assertion.

## Context (verified symbols)
- Real impl to call: `ui/llmchat/LlmChatModelHelper.kt` (`initialize` builds `EngineConfig` with `Backend.NPU(nativeLibraryDir=context.applicationInfo.nativeLibraryDir)` when accelerator label == `NPU`).
- ⚠️ **The wedge risk, made concrete:** `data/Consts.kt` sets `DEFAULT_ACCELERATORS = listOf(Accelerator.GPU)`. In stock code, `initialize()` reads `ConfigKeys.ACCELERATOR` defaulting toward GPU, and the `else` branch in backend selection falls to `Backend.CPU()`/`Backend.GPU()`. **This gate must force NPU and hard-error if NPU init fails — never silently serve from CPU/GPU.**
- Streaming seam: `runInference`'s `resultListener(partial, done, thinking)` ← bridge to HTTP later; Gate 1 is **non-streaming** (buffer to `done`).

## Tasks (delegate: `claude -p '<below>' --allowedTools 'Read,Edit,Write,Bash' --max-turns 20`)
1. Add **Ktor (CIO)**. One route: `POST /v1/chat/completions`, non-streaming.
2. **Bearer-token auth** in the Ktor pipeline: reject missing/invalid token with 401 before any inference. Token read from app config (first-run generated). Then: parse OpenAI `messages` → flatten to a single `input` string → call real `LlmChatModelHelper.runInference(model, input, resultListener = buffer, …)` → on `done=true` return OpenAI-shaped JSON (`choices[0].message.content`). Map `onError` → HTTP 500 with JSON error.
3. **Force NPU:** set the served model's `ConfigKeys.ACCELERATOR` to `Accelerator.NPU.label`. In `initialize`, if the NPU backend cannot be constructed/initialized, invoke `onError` and **fail the request with an explicit 503 "NPU required"** — do NOT fall through to GPU/CPU. (Override the `Consts.kt` GPU default for this path.)
4. Expose a tiny internal accessor to read back the engine's *active* backend after init (for the test in §smoke), since a log line is not acceptable proof.

## Devils-advocate focus — each finding discharged by a test, not argument
- **CRITICAL — is it really the NPU?** Demand a test asserting active backend == NPU; reject log-only evidence.
- **CRITICAL — silent fallback?** Prove NPU-unavailable returns 5xx, never a CPU/GPU answer.
- **HIGH — token fidelity:** server output for a fixed prompt equals in-app chat output for the same prompt/seed.
- Codex pass additionally probes: input flattening correctness (multi-message roles), 500 path leaks no stack trace to client.

## Smoke test — `gate1_smoke` (on-device + harness; closes the gate)
1. **NPU-active assertion** (instrumented): after `initialize`, active backend reads `NPU`, else FAIL.
2. **LAN curl (authed):** harness `POST http://<phone-ip>:8080/v1/chat/completions` with valid `Authorization: Bearer` + fixed prompt → non-empty completion within N seconds. Plus: missing/invalid token → 401, no completion.
3. **Negative/fallback test:** force NPU-unavailable → assert HTTP 503 "NPU required", assert response body contains **no** model-generated text.

## Done when
all three smoke checks green (the "it's real" gate). Commit (conventional), `gh pr create`, self-merge on green. Hands off to Gate 2 (resident service + SSE + token auth).
