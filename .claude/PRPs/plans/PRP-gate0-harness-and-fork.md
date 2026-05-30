# PRP — Gate 0: Harness & Fork Skeleton

> Drop in `.claude/PRPs/plans/`. Branch: `feat/relais-gate0-harness`.
> Per `model-routing` v5.0.0: this is delegated to Claude Code CLI; the Opus brain only adjudicates the review gate. Security-sensitive (network service) → Codex adversarial pass runs alongside `/devils-advocate`.

## Objective
Stand up the fork and the shared test harness so every later gate can self-close via tests. **No feature code ships in this gate** — only scaffolding that makes "done" mechanically checkable.

## Context (verified against cloned `google-ai-edge/gallery`)
- License: Apache-2.0. Preserve every Google copyright header; add a top-level `NOTICE`.
- Inference seam: `runtime/LlmModelHelper.kt` (interface) + `ui/llmchat/LlmChatModelHelper.kt` (LiteRT-LM impl).
- The interface contract this project builds on:
  - `runInference(model, input: String, resultListener: (partial: String, done: Boolean, thinking: String?) -> Unit, cleanUpListener, onError, images, audioClips, coroutineScope, extraContext)` — streams via callback.
  - `initialize(context, model, taskId, supportImage, supportAudio, onDone, systemInstruction, tools, enableConversationConstrainedDecoding, coroutineScope)`.
- Relevant data symbols: `data/Types.kt` → `enum Accelerator { CPU, GPU, NPU }`; `data/Config.kt` → `ConfigKeys.ACCELERATOR`; `data/Consts.kt` → `DEFAULT_ACCELERATORS = listOf(Accelerator.GPU)` ⚠️ stock default is GPU, not NPU — Gate 1 must override this.

## Tasks (delegate: `claude -p '<below>' --allowedTools 'Read,Edit,Write,Bash' --max-turns 15`)
1. Fork → `entrevoix/relais`, full history. Add `NOTICE` crediting Google AI Edge Gallery (Apache-2.0). Leave all file headers intact.
2. Quarantine non-core modules into a `legacy/` source set so the build stays green without them on the main path: `customtasks/tinygarden`, `ui/benchmark`, `ui/home` (model browser), HF discovery. **Keep** `runtime/`, `ui/llmchat/*ModelHelper*`, `data/`. **Keep but leave dormant:** `customtasks/agentchat` (phase-2 MCP).
3. Create `relais-harness/` (host-side, JVM or Python):
   - OpenAI client helper + **SSE parser**.
   - `FakeEcho : LlmModelHelper` — deterministic: emits input tokens back as 3 partials then `done=true`, faithfully matching the real callback timing (partial→partial→partial→done). This lets the server layer be tested with **no model and no NPU**.
4. Wire `FakeEcho` behind a build flag / DI so the (future) server can boot against it.

## Devils-advocate focus (Claude `/devils-advocate` + `codex -q … --model gpt-5.1-codex`)
- Did quarantining break compilation or strand a dangling reference?
- Is `FakeEcho`'s partial/done cadence actually faithful to `LlmChatModelHelper`'s `MessageCallback` (onMessage×N → onDone)? A divergence here would make every downstream server test lie.

## Smoke test — `gate0_smoke` (closes the gate)
- `./gradlew :app:assembleDebug` exits 0.
- Harness boots server with `FakeEcho`, POSTs `/v1/chat/completions`, asserts a well-formed OpenAI response object (id, choices[0].message.content non-empty, role=assistant). **No NPU, no model.**

## Done when
debug APK builds AND `gate0_smoke` is green. Commit (conventional), `gh pr create`, self-merge on green.
