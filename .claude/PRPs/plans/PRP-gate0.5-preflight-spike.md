# PRP — Gate 0.5: Pre-Flight Spike (manual, ~30–45 min on-device)

> Run this **by hand on the actual phone before** `/goal relais-node-GOAL.md`. It is not autonomous — it converts the unknowns that would halt the `/goal` run into settled facts (or early redesigns). Findings feed directly back into Gate 1.
> Branch (throwaway): `spike/relais-preflight`.

## Already confirmed (do not re-test)
- ✅ **Gemma 4 E4B initializes and runs on the NPU** via the stock `google-ai-edge/gallery` APK on JD's device. The engine + model + chipset combination works. The wedge is real on this hardware.

## Question 1 — Can the active backend be read back programmatically? (CRITICAL — gates Gate 1's test design)
Gallery shows NPU usage in *its UI*; Relais needs to assert `backend == NPU` **from code** for the gate to self-close.
- Inspect the LiteRT-LM `Engine` / `EngineConfig` / `Capabilities` objects (already imported in `ui/llmchat/LlmChatModelHelper.kt`) for any getter exposing the *active/resolved* backend after `initialize`.
- Try: log the engine state post-init; check for a `.backend`, `.activeBackend`, `.config` readback, or a capabilities query.
- **Decision output:**
  - If a programmatic readback EXISTS → Gate 1's NPU-active assertion stands as written.
  - If it does NOT → **redesign Gate 1's proof** to a benchmark-threshold test (measure tok/s or prefill latency; assert it exceeds a CPU-impossible floor calibrated against a forced-CPU run on the same device). Record the CPU baseline number here.

## Question 2 — Does the engine survive headless, screen-off, under Doze? (HIGH — gates Gate 2 feasibility)
Gallery keeps the model alive in an interactive, screen-on app. Relais needs it resident in a headless foreground service.
- Minimal spike: load E4B in a bare foreground service (no UI), `initialize` once, then: turn screen off → wait 5 min → run an inference. Then: background the app → wait 15–30 min (let Doze engage) → run again.
- Watch for: engine eviction, OOM-kill, NPU handle invalidation, wake-lock needs.
- **Decision output:**
  - Survives clean → Gate 2's resident-engine design stands.
  - Gets reclaimed → record what's needed (partial wake-lock, `foregroundServiceType`, battery-exemption prompt, or periodic keep-alive) and fold into Gate 2 before the run.

## Question 3 — (cheap, while you're here) Confirm the bind + a raw curl
- Stand up the most trivial possible HTTP listener in that service, bind `0.0.0.0:8080`, and `curl` it from another device on the LAN. Just prove the phone will accept inbound connections on that port on JD's network (some carriers/firewalls interfere).
- **Decision output:** port reachable Y/N; if N, note the network fix before Gate 1.

## Exit criteria
A one-paragraph findings note answering Q1/Q2/Q3 with concrete decisions. Hand that note to the `/goal` run so Gate 1 (and Gate 2) are written against reality, not assumption. **Then** start `/goal relais-node-GOAL.md`.
