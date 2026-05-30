# GOAL: Relais — NPU Inference Node

> **How to run this file.** This is a `/goal`-driven execution plan. Feed it to Claude Code with:
> `/goal relais-node-GOAL.md`
> It is written to drive itself: each sub-goal is a gate, each gate self-resolves through a `/devils-advocate` pass that is *closed by a test-harness run*, not by human judgment. No gate is "done" until its smoke test is green. Human involvement is a fallback, not a step.
>
> Skills invoked by name (resolved in JD's Claude Code env, not authored here): `/goal`, `/devils-advocate`, `prp`, `model-routing`. Delegation and review specifics live in each gate's execution contract below.

---

## North-star

Ship an open-source Android app under **Entrevoix** that turns a spare phone into a **headless, NPU-accelerated inference node** exposing an **OpenAI-compatible API** over the local network — a self-contained, offline APK that any standard OpenAI client can talk to and forget it's a phone.

**Product boundary:** Relais is **not** coupled to any gateway. It does not call the cloud. It exposes models from the NPU; what a user points at it (Open WebUI, a script, their own router/gateway) is documented as "works with," never a dependency. **Default network posture:** binds the LAN (`0.0.0.0`) but **requires a bearer token** before answering — an open LLM endpoint on an untrusted network is a self-DoS risk given thermal/battery limits. Token generated on first run, shown in-app, checked in the Ktor pipeline (`Authorization: Bearer <token>`).

**The wedge (every scope decision defends this):** Ollama owns CPU/GPU inference on desktop/server and *structurally cannot reach the mobile NPU* — llama.cpp has no path to Tensor/Snapdragon neural engines. Relais is not "Ollama for phones." It is the NPU node Ollama cannot be. **If a decision weakens NPU acceleration, it is wrong by default.**

**Name:** Relais — French for "relay," a coaching-inn waystation where you swap tired horses for fresh ones. Fits the `navette`/Entrevoix family (navette = shuttle). Package: `cc.grepon.relais`. Daemon/service convention: `relaisd` if a CLI companion is ever added.

---

## Strategy (fixed)

Fork `google-ai-edge/gallery` (Apache 2.0). It already wraps **LiteRT-LM** with NPU backend selection (`Backend.NPU(nativeLibraryDir=…)`) behind a clean `LlmModelHelper` interface. The exposure layer is the *only* genuinely new code.

**The seam — the real interface this plan builds against** (verified against the cloned repo, `runtime/LlmModelHelper.kt`):

```kotlin
interface LlmModelHelper {
  fun initialize(context, model, taskId, supportImage, supportAudio,
                 onDone: (String)->Unit, systemInstruction, tools,
                 enableConversationConstrainedDecoding, coroutineScope)
  fun runInference(model, input: String,
                   resultListener: (partial: String, done: Boolean, thinking: String?) -> Unit,
                   cleanUpListener, onError, images, audioClips, coroutineScope, extraContext)
  fun stopResponse(model)
  fun cleanUp(model, onDone)
  fun resetConversation(...)
}
```

`runInference`'s `resultListener` already streams `(partialText, done, thinking)`. That callback maps almost 1:1 onto **SSE chunks** for `/v1/chat/completions`. The concrete impl is `ui/llmchat/LlmChatModelHelper.kt` (LiteRT-LM `Engine`/`Conversation`, `sendMessageAsync` + `MessageCallback`). **We do not write inference. We write a network skin over a debugged callback.**

---

## Execution contract (applies to every gate)

Each gate runs the same loop, and **closes itself**:

1. **PLAN** — Opus brain writes the gate's PRP via `prp` skill into `.claude/PRPs/plans/`. Branch: `feat/relais-gate-N-<slug>`.
2. **BUILD** — delegate to subprocess (`claude -p '<task>' --allowedTools 'Read,Edit,Write,Bash' --max-turns <N>`). Brain never writes feature code.
3. **DEVILS-ADVOCATE (dual, security-sensitive path)** — run `/devils-advocate` on the diff via Claude Code AND a **Codex adversarial pass** (`codex -q … --model gpt-5.1-codex`) in parallel, per `model-routing` Rule 1 for security-sensitive code. Merge both findings; severity CRITICAL/HIGH/MEDIUM/LOW, max 5 rounds. **Every finding must be discharged by a *test*, not by argument.** A finding is "resolved" only when a named test in the harness reproduces the concern and then passes. CRITICAL/HIGH unresolved after round 5 → gate fails, halt, surface summary.
4. **SMOKE** — run the gate's smoke test (below). Red = loop back to BUILD with the failure as input. Green = gate passes.
5. **COMMIT + PR** — conventional commits, `gh pr create`. Self-merge allowed only on all-green (no required human reviewer for the homelab path; Gate 4 changes this for public release).

**Autonomy rule:** the plan proceeds gate→gate with zero human input as long as smoke stays green and no CRITICAL survives 5 DA rounds. The only human-surfacing events are: (a) a hard halt (unresolved CRITICAL), (b) a secret/credential needed that isn't in env, (c) Gate 4's license decision flagged for explicit sign-off.

**Test harness (shared, built FIRST — Gate 0):** `./relais-harness/` — a host-side runner that talks to the node over the network and a Robolectric/instrumented layer for on-device unit bits. Every gate adds tests to it; the harness is the single source of "done."

---

## GATE 0 — Harness & Fork Skeleton

**Goal:** the scaffolding that lets every later gate self-close exists before any feature.

**BUILD (delegate):**
- `gh repo fork google-ai-edge/gallery` → `entrevoix/relais` (keep history; preserve all Apache headers + add `NOTICE`).
- Strip to inference core: keep `runtime/`, `ui/llmchat/*ModelHelper*`, `data/`. Quarantine (don't delete yet) `tinygarden`, `benchmark`, `ui/home`, HF model browser behind a `legacy/` source set so the build stays green.
- Stand up `relais-harness/`: a Kotlin/JVM (or Python) host client with `curl`-equivalent helpers, an SSE parser, and a fake `LlmModelHelper` (`FakeEcho`) that returns deterministic tokens so the server layer is testable **without a model or NPU**.

**DEVILS-ADVOCATE focus:** Did stripping break the build? Is `FakeEcho` faithful to the real `runInference` callback timing (partial→partial→done)?

**SMOKE (`gate0_smoke`):**
- `./gradlew :app:assembleDebug` succeeds.
- Harness boots server with `FakeEcho`, POSTs to `/v1/chat/completions`, asserts a well-formed OpenAI response. **No NPU, no model — proves the wire contract in isolation.**

**Gate passes when:** debug APK builds AND `gate0_smoke` green.

---

## GATE 1 — Spike: Real Model on NPU, One Curl

**Goal:** forked app runs **Gemma 4 E4B on the NPU** and answers one `curl` to `/v1/chat/completions` over LAN. Proves the *real* seam end to end.

**BUILD (delegate):**
- Embed **Ktor** (CIO engine). Single route: `POST /v1/chat/completions`, non-streaming first.
- Wire the route to the *real* `LlmChatModelHelper`: parse OpenAI `messages` → flatten to `input` → `runInference(...)` → buffer until `done=true` → return OpenAI JSON. Map `onError` → 500.
- Force `ConfigKeys.ACCELERATOR = NPU` for the served model; fail loudly (not silently to CPU) if NPU init fails — **silent CPU fallback is a wedge violation and must be a hard error in this build.**

**DEVILS-ADVOCATE focus (CRITICAL candidates):**
- *Does it actually use the NPU?* DA must not accept the log line — it must demand a test that asserts the active backend is NPU.
- *Silent CPU fallback?* Prove it errors instead.
- *Token correctness:* response text equals in-app chat output for the same prompt/seed.

**SMOKE (`gate1_smoke`, on-device + harness):**
- Instrumented assertion: initialized engine reports `Backend.NPU` (read back from the LiteRT-LM engine/config), else fail.
- Harness over LAN: `curl http://<phone-ip>:8080/v1/chat/completions` with a fixed prompt returns non-empty completion in < N seconds.
- Negative test: force NPU-unavailable → server returns explicit 5xx with "NPU required", **not** a CPU answer.

**Gate passes when:** all three green. This is the "it's real" gate.

---

## GATE 2 — The Node (primary success criterion lives here)

**Goal:** resident-engine foreground service + SSE streaming + token-gated `0.0.0.0` bind + reachable over the network (LAN; Tailscale as an optional documented path).
**PRIMARY SUCCESS CRITERION:** a **standard OpenAI-compatible client, with no special integration, gets a correct streamed completion from the node over the network and cannot tell it's a phone.** (Auth token required; unauthenticated requests rejected.)

**BUILD (delegate):**
- `InferenceServerService` (foreground, type `dataSync` or specialized). Calls `initialize()` once, holds the `LlmModelInstance` resident across screen-off; acquires partial wake-lock; survives Doze (battery-exemption prompt documented).
- **SSE streaming:** bridge `resultListener(partial, done, thinking)` → `data:` chunks in OpenAI streaming delta format; `done=true` → `data: [DONE]`. `thinking` channel routed to a `reasoning`-style field, gated off by default (text-first).
- Bind `0.0.0.0:8080` behind **bearer-token auth** (generated first-run, shown in-app; reject missing/wrong token with 401). Document network reach options: LAN by default, Tailscale as an optional path for off-network access — both orthogonal to the app, just networking.
- **"Works with" docs (not a dependency):** show how to point Open WebUI, a curl script, or any OpenAI-compatible gateway/router at the node. Text-only model string for v1 (multimodal off the wire; see non-goals).

**DEVILS-ADVOCATE focus:**
- Stream correctness: do SSE deltas reassemble to exactly the buffered (Gate 1) answer? (golden-transcript test)
- Lifecycle: does the engine survive screen-off / app-backgrounded / 30-min idle without reload? (the resident-engine promise)
- `stopResponse` actually cancels mid-stream and frees the conversation?
- Concurrency: two overlapping requests — serialized or rejected cleanly, never corrupting the single `Conversation`.

**SMOKE (`gate2_smoke`):**
- Streaming vs non-streaming for same prompt produce identical concatenated text (golden test).
- Resident test: request → screen off 5 min → request again → **no model reload** (assert init count == 1).
- **Generic-client end-to-end:** harness, acting as a vanilla OpenAI-compatible client (correct bearer token), gets a correct streamed completion over the network. This is the "forgets it's a phone" check — proves spec compliance, not any one gateway.
- **Auth test:** request with missing/invalid token → 401, no completion; valid token → 200 stream.

**Gate passes when:** all green AND the generic-client round-trip + auth tests succeed unattended.

---

## GATE 3 — The Appliance

**Goal:** boot-to-server kiosk + `/health` + metrics + thermal-aware throttling + OOM-restart + warn-and-continue. **Success: runs unattended on a desk for a week.**

**BUILD (delegate):**
- Boot receiver → launches service on device boot (kiosk/launcher config documented; no ROM — stock Android per the app-not-distro decision).
- `GET /health` (liveness: engine resident, NPU active, queue depth) and `GET /metrics` (tok/s, temps, req count, OOM-restart count; Prometheus-style text).
- **Thermal governor:** subscribe to thermal status; above threshold → shed/queue load (lengthen latency) rather than crash; expose state in `/health`. Thermal throttling is the named #1 risk — this is its mitigation.
- OOM-restart: service auto-reinits engine on process death; **warn-and-continue** (never hard-fail the whole node for one bad request), matching JD's idempotent-script preference.

**DEVILS-ADVOCATE focus:**
- Does thermal shedding actually prevent the throttle-cliff, or just observe it? Demand a test that drives synthetic load and asserts graceful latency growth, not death.
- Does OOM-restart recover to a *serving* state (engine resident again), asserted by a follow-up `/health` 200?

**SMOKE (`gate3_smoke`):**
- Soak test (compressed): N-minute sustained request loop; assert zero unhandled crashes, `/health` stays 200, latency degrades gracefully under induced thermal pressure.
- Kill-the-process test: SIGKILL the engine → assert auto-restart → `/health` green within T seconds.
- (Real-world close: a documented 7-day desk soak; CI proxy is the compressed soak.)

**Gate passes when:** compressed soak + kill-recovery green.

---

## GATE 4 — Open-Source Readiness (Entrevoix)

**Goal:** someone who isn't JD can stand up a node from the docs alone.

**BUILD (delegate, except the flagged decision):**
- **Clean package rename** off `com.google.ai.edge.gallery` → final Entrevoix id; co-installs beside stock Gallery; no store collision.
- **License decision — HUMAN SIGN-OFF FLAG:** app shell is Apache-2.0 (preserve Google headers + NOTICE). Choose Relais's own license: AGPL-3.0 to match Netlens/Navette (Apache→AGPL is one-way compatible, fine) **or** stay Apache for adoption. *This is the one decision surfaced to JD; everything else is autonomous.*
- **LiteRT-LM dependency note:** document that the inference engine ships as a prebuilt artifact under its own terms — the fork is the *app*, not the engine. State this plainly in README + NOTICE.
- README + setup docs: flash spare phone → install APK → download E4B → set token → reach over LAN (or optional Tailscale). Plus a "works with" section (Open WebUI, scripts, gateways). Plus the **node contract** doc (the thin OpenAI+health spec) so the deferred "any device" framework stays *possible* without being built.
- Publish: GitHub under Entrevoix; F-Droid metadata (and Play later via the company entity).

**DEVILS-ADVOCATE focus:**
- Are all Apache headers/NOTICE intact on every Google-derived file post-rename? (license-lint test)
- Does a *clean-room* setup (fresh checkout, no JD env, secrets via env vars only) reach a serving node following only the README?
- Any hardcoded `grepon.cc`/`192.168.1.x`/keys leaked into the public tree? (secret-scan must be green — **no real keys in repo, ever**.)

**SMOKE (`gate4_smoke`):**
- License-lint: every modified-from-Google file retains Apache header; `NOTICE` present; secret-scan clean.
- **Clean-room bring-up:** harness provisions a fresh environment with only the README and env-var secrets, and reaches a green `/health`. This mechanizes "someone who isn't JD can stand it up."

**Gate passes when:** license-lint + secret-scan + clean-room bring-up green AND license decision recorded.

---

## Ship criteria (all must be green, checked by harness)

- Gates 0–4 each green by their own smoke tests.
- NPU-active assertion green (the wedge, mechanically proven — not a log line).
- Generic OpenAI-client round-trip + auth (401/200) green (Gate 2 primary criterion).
- Compressed soak + kill-recovery green (Gate 3).
- Clean-room bring-up + secret-scan + license-lint green (Gate 4).

**Definition of shipped:** tag `v0.1.0`, GitHub release under Entrevoix, F-Droid metadata submitted, README's clean-room path verified by the harness in CI.

---

## Risk register (tracked, each tied to a test)

| Risk | Why it matters | Mechanized guard |
|---|---|---|
| Thermal throttle under sustained load | The real enemy; kills the appliance promise | Gate 3 soak: graceful latency growth, no crash |
| NPU vendor-blob dependency broken | It's the entire wedge | Gate 1 NPU-active assertion; hard-error on CPU fallback |
| Silent CPU fallback | Quietly becomes "slow Ollama" | Gate 1 negative test: NPU-unavailable → 5xx, never a CPU answer |
| LiteRT-LM licensing (prebuilt engine vs Apache shell) | OSS distribution correctness | Gate 4 license-lint + NOTICE + documented dependency terms |
| Secret leakage into public repo | JD pastes keys; repo is public | Gate 4 secret-scan; env-vars only; clean-room has no JD secrets |
| Single-Conversation concurrency corruption | Multi-client use | Gate 2 concurrency test: serialize or clean-reject |

---

## Explicit non-goals for v1 (defended)

- General "any device" framework — **deferred**; only the *node contract* doc is produced so it stays possible.
- Multimodal over the wire — **off** for v1 (text-first; revisit once the wire format is proven).
- Training / fine-tuning — out.
- MCP / `agentchat` tool-serving — **phase-2 prize**, not v1 (the fork retains the code but it's dormant).
- Custom OS / ROM — rejected earlier: app-not-distro; an OS image risks the NPU vendor blobs that are the whole point.
