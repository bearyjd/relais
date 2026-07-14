# LiteRT-LM native API — surface reference (maximize-the-native-API)

**Why this file exists.** Relais is built on the bundled `com.google.ai.edge.litertlm` AAR.
Multiple feature plans in this repo assumed a capability was *missing* and designed a hand-rolled
fallback — and were **wrong**: the native API already did it, better. (Examples: feature-03 designed a
per-turn "replay" because it didn't know about `ConversationConfig.initialMessages`; feature-04 designed
a prompt-injection + bracket-scraping tool parser because it didn't know about the native `OpenApiTool`
/ `ToolManager` path; feature-05 dismissed `enableConversationConstrainedDecoding`.)

**The rule:** _native-API-first_. Before designing any fallback, prompt-injection scheme, or new
SDK integration, **check this file** (and re-derive from the AAR if unsure). If a plan says "X is not
available," verify against the AAR before believing it. See [`CLAUDE.md`](../CLAUDE.md).

- **Pinned version:** `com.google.ai.edge.litertlm:litertlm-android:0.12.0` (see `Android/src/gradle/libs.versions.toml`). A `0.14.0` AAR is also in the gradle cache but **0.12.0 is what ships** (a 0.14.0 bump was A/B-tested on rango/G5 and **reverted** — it regresses the G5 TPU lane, `PR #150`; see memory `relais-tensor-tpu-path`).
- **Regenerate this inventory** after any version bump: `scripts/dump-litertlm-api.sh` (decompiles the AAR's `classes.jar` with `javap`). Re-verify the "Verified on-device" claims below — a bump can change behavior even when signatures are stable.
- **Verified on-device** = exercised on rango (Pixel 10 / Tensor G5) with Gemma-4 E2B via an `androidTest` probe or the live HTTP node. Probes live in `Android/src/app/src/androidTest/java/cc/grepon/relais/*Probe.kt`.

---

## 1. Core inference objects

| Type | Key members | Notes |
|---|---|---|
| `Engine(EngineConfig)` | `initialize()`, `isInitialized()`, `createConversation(ConversationConfig)`, **`createSession(SessionConfig)`**, `close()` | One resident engine per process (Relais holds it in `RelaisEngine`). `Engine.Companion.setNativeMinLogSeverity(LogSeverity)` tunes native log noise. |
| `EngineConfig(modelPath, backend, visionBackend, audioBackend, maxNumTokens, maxNumImages, cacheDir)` | — | `maxNumImages` is **not currently set by Relais** (defaults). `cacheDir` enables the xnnpack/mldrift weight caches. |
| `Conversation` (AutoCloseable) | `sendMessage(Message\|Contents\|String, extraContext): Message` (blocking), `sendMessageAsync(…, MessageCallback, …)` (streaming) **and** a `Flow<Message>` overload, `cancelProcess()`, `getBenchmarkInfo()`, **`renderMessageIntoString(Message, extraContext)`**, `getToolManager()`, `getAutomaticToolCalling()` | High-level, template-aware, KV-cache-bearing turn machine. Relais creates one per HTTP request. |
| `Session` (AutoCloseable) | **`runPrefill(List<InputData>)`**, **`runDecode(): String`**, `generateContent(List<InputData>)`, `generateContentStream(…, ResponseCallback)`, `cancelProcess()` | **Low-level, NO chat template.** Separates prefill from decode. Use when you need raw token control or to inject context without generation. Not currently used by Relais. |
| `Backend` | `Backend.CPU(numThreads?)`, `Backend.GPU()`, `Backend.NPU(nativeLibraryDir)` | NPU backend exists at the litertlm level (distinct from the AICore/Gemini-Nano NPU path in `RelaisAicore`). |
| `Capabilities(modelPath)` | `hasSpeculativeDecodingSupport()` | Query a model file's capabilities without loading the full engine. |
| `BenchmarkKt.benchmark(modelPath, backend, …): BenchmarkInfo` | — | One-shot benchmark. |
| `BenchmarkInfo` | `initTimeInSecond`, `timeToFirstTokenInSecond`, `lastPrefillTokenCount`, `lastDecodeTokenCount`, `lastPrefillTokensPerSecond`, `lastDecodeTokensPerSecond` | Also reachable via `Conversation.getBenchmarkInfo()`. **Opportunity:** real prefill/decode tok/s + TTFT vs Relais's current wall-clock estimate. |

## 2. Messages, content, roles

- `Role` enum: **`SYSTEM`, `USER`, `MODEL`, `TOOL`** (`.value` is the wire string).
- `Message.Companion`: `system(String|Contents)`, `user(String|Contents)`, `model(String)` / `model(Contents, toolCalls, channels)`, **`tool(Contents)`**, `of(…)`. A `Message` carries `role`, `contents`, `toolCalls: List<ToolCall>`, `channels: Map<String,String>`.
- `Contents.of(String | vararg Content | List<Content>)`.
- `Content` subtypes: `Text(String)`, `ImageBytes(ByteArray)`, `ImageFile(path)`, `AudioBytes(ByteArray)`, `AudioFile(path)`, **`ToolResponse(name, response: Any)`**.
- `InputData` subtypes (for the `Session` low-level path): `Text`, `Image`, `Audio` (bytes only).

**Verified on-device (feature-03):** seed a stateless multi-turn conversation via
`ConversationConfig(systemInstruction = Contents.of(sys), initialMessages = List<Message>)` — system is
honored, history is prefilled (one decode regardless of depth), roles map `assistant→MODEL`. This
replaced the catastrophic per-turn replay. **Do not** hand-roll history replay.

## 3. ConversationConfig — the per-request control surface

```
ConversationConfig(
  systemInstruction: Contents = empty,         // first-class system prompt   [USED: feature-03]
  initialMessages: List<Message> = [],         // prefilled prior turns        [USED: feature-03]
  tools: List<ToolProvider> = [],              // advertised tools             [USED: feature-04]
  samplerConfig: SamplerConfig = default,      // topK/topP/temperature/seed   [USED]
  automaticToolCalling: Boolean = false,       // false => return tool_calls   [USED: feature-04]
  channels: List<Channel> = [],                // <-- UNEXPLOITED (see §6)
  extraContext: Map<String, Any> = {},         // <-- UNEXPLOITED (per-request context; also a sendMessage param)
)
```
All args are defaulted — call with named args (as `RelaisEngine` does). `extraContext` is also accepted
by `sendMessage`/`sendMessageAsync`/`renderMessageIntoString`.

`SamplerConfig(topK: Int, topP: Double, temperature: Double, seed: Int)` — note **`seed`** is exposed
(deterministic sampling; Relais doesn't set it yet).

## 4. Tools (native function-calling) — `[USED: feature-04, verified on-device]`

- Build a tool from an OpenAI `function` object: implement `OpenApiTool { getToolDescriptionJsonString(): String; execute(paramsJsonString): String }` and wrap with the top-level `tool(openApiTool): ToolProvider`. The description JSON must have a top-level `name`; pass OpenAI's `function` object verbatim — the bridge re-wraps it as `{"type":"function","function":{…}}`.
- Alternatively, annotate Kotlin functions with `@Tool(description)` / `@ToolParam(description)` on a `ToolSet`, wrap with `tool(toolSet)` (uses `ReflectionTool` → needs kotlin-reflect).
- `ConversationConfig(tools = [...], automaticToolCalling = false)` → `Conversation.sendMessage(...)` returns a `Message` whose `toolCalls: List<ToolCall>` (each `name` + `arguments: Map<String,Any?>`) is populated; `execute()` is **not** called (the client executes, OpenAI-style). With `automaticToolCalling = true` the library calls `execute()` and feeds the result back automatically.
- Round-trip a result: `Message.tool(Contents.of(Content.ToolResponse(name, resultJson)))`.
- `ToolManager(providers).getToolsDescription(): JsonArray` returns exactly what the library injects into the prompt (useful for debugging).
- **Gotcha (verified):** small models (E2B) sometimes emit malformed/typed/nested args, e.g. `{"city":{"type":"STRING","value":"Berlin"}}`. This is model output quality, not the API. Relais passes args through verbatim; constrained decoding (§5) may fix it.

## 5. Structured / constrained decoding — `ExperimentalFlags.enableConversationConstrainedDecoding`

`ExperimentalFlags` (a process-global singleton; setters):
- **`enableConversationConstrainedDecoding: Boolean`** — constrains decoding. The JNI plumbs a per-conversation boolean at `nativeCreateConversation(...)`, so the flag is read **at `createConversation` time**. Because Relais serializes all inference under one lock, set-before-create is race-free on the node (no concurrent UI conversation in the headless node).
- **`overwritePromptTemplate: String`** — **override the model's chat template wholesale.** Powerful + dangerous; lets you support a model whose bundled template is wrong, or inject a custom format.
- `convertCamelToSnakeCaseInToolDescription: Boolean` — tool-name casing.
- `filterChannelContentFromKvCache: Boolean` — see §6 (channels).
- `enableSpeculativeDecoding: Boolean?` — Relais sets this **false** deliberately (measured a regression on E4B/G4; no draft model bundled).
- `enableBenchmark: Boolean` — populates `BenchmarkInfo` on the normal path.

**Verified on-device (`StructuredOutputProbe`, E2B):**
- A single **tool whose `parameters` ARE the requested JSON schema** reliably yields clean,
  schema-conforming arguments (4/4 runs: `{name:"John Smith", age:42.0}`, correctly typed, no nesting).
  → The strong path for `response_format: json_schema` is a forced schema-tool whose call arguments
  become the response content (serialize via `JsonConvertersKt.toJsonObject`). For `json_object`
  (no schema) there is no tool to build from → prompt + validate.
- **`enableConversationConstrainedDecoding` made NO observable difference** (ON ≡ OFF; `age` came back
  `42.0` for an `"integer"` schema in both). It is **not** a strict token-level grammar enforcer in
  0.11.0 — do **not** rely on it for hard guarantees. **Always validate** model output against the
  schema and repair/retry; treat the schema-tool as a high-hit-rate generator, not a guarantee.

## 6. Channels — reasoning / thinking separation `[USED: feature-10a, verified on-device]`

`Message.channels: Map<String,String>` + `Channel(channelName, start, end)` + `ConversationConfig.channels` + `ExperimentalFlags.filterChannelContentFromKvCache`. The mechanism for models that emit delimited side-channels (e.g. a `<think>…</think>` reasoning channel).

**Verified on-device (`ReasoningChannelProbe`, rango/G5/E2B):** Gemma-4 E2B populates a `message.channels["thought"]` stream **only** when `extraContext["enable_thinking"]="true"` is passed to `sendMessageAsync` — NOT by defining a `Channel` or by default. The visible answer (`message.toString()`) stays clean (no `<think>` leakage). Reasoning arrives as **per-token deltas** (concatenate them; one callback ≠ the whole chain), interleaved/parallel with visible deltas in the same callback stream. Baseline (`emptyMap()`, today's `RelaisEngine` default) → `channels` empty, no reasoning. The `enable_thinking` key is a chat-template variable (see §8 extraContext), not a `Channel` definition. Relais exposes this as OpenAI `reasoning_content`, gated by `reasoning_effort` (`RelaisReasoning` / `RelaisEngine.generate(onReasoning=…)`). The inherited gallery chat path already used it (`LlmChatViewModel` sets the key; `LlmChatModelHelper` reads `channels["thought"]`).

## 7. Utilities

- `JsonConvertersKt`: `toJsonObject(Map): JsonObject`, `toJsonElement(Any): JsonElement`, `toMap(JsonObject): Map`, `toKotlinValue(JsonElement): Any`. **The library's own Map↔JSON converters** — prefer these over `org.json.JSONObject(map)` for tool args (better fidelity; relevant to the §4 args gotcha).
- `Conversation.renderMessageIntoString(Message, extraContext)`: render a message through the model's template to a String (debugging / manual prompt assembly). Gemma-4 renders roles as `<|turn>role … <turn|>`.
- `LogSeverity` + `Engine.Companion.setNativeMinLogSeverity(...)`: control native log verbosity.
- `LiteRtLmJni` (internal): the raw native methods (`nativeRunPrefill`, `nativeRunDecode`, `nativeCreateConversation(... 2 booleans = automaticToolCalling, constrainedDecoding ...)`, etc.). Not for direct use, but documents what the high-level API can ultimately reach.

## 7.5 Mid-decode cancellation — `cancelProcess()` `[static-verified 2026-07-14 (0.12.0); on-device probe pending]`

**The API exists — "true mid-decode native stop is a TODO" (in `RelaisEngine.kt`) was stale w.r.t. the
AAR.** `javap` on `litertlm-android:0.12.0` `classes.jar` (2026-07-14):

- `Conversation.cancelProcess(): void` → `LiteRtLmJni.nativeConversationCancelProcess(handle)`.
- `Session.cancelProcess(): void` → `LiteRtLmJni.nativeCancelProcess(handle)`.
- The bundled native `.so` carries `LiteRtSetCompiledModelCancellationFunction`, the log string
  `"Client requested cancel during Invoke()"`, and `kLiteRtStatusCancelled` — i.e. a **cooperative
  cancel checked mid-`Invoke()`** at the compiled-model level, not merely a post-hoc bound.

Relais today only does *cooperative* cancel (stop streaming to the client + set `finish_reason`);
native decode still runs to `maxNumTokens`, burning battery/thermal budget on tokens nobody reads
(the always-on-appliance anti-goal in issue #125). Wiring `conversation.cancelProcess()` into the
`shouldCancel`/broken-pipe path would truly halt decode.

**Threading caveat (why this needs the probe before wiring):** `MessageCallback.onMessage` runs on the
native decode/callback thread. `cancelProcess()` must be issued from **another** thread (reentrant
cancel against the in-flight `Invoke()` is untested and risky). `MidDecodeStopProbe` cancels from a
watcher thread and this is the exact behavior it must confirm on-device.

**Still to verify on-device (rango / G5 / E2B):** that `cancelProcess()` halts token callbacks within
~1 token-interval and the stream then terminates via `onDone`/`onError`. Probe:
`Android/src/app/src/androidTest/java/cc/grepon/relais/MidDecodeStopProbe.kt` (records
tokensAfterCancel / stopLatencyMs / terminal; blocked on rango being on USB). Close #125 by pasting the
probe verdict here with a date.

## 8. Unexploited hooks — Relais opportunities (maximize)

| Hook | Opportunity | Status |
|---|---|---|
| `enableConversationConstrainedDecoding` | Guarantee schema-valid structured output / clean tool args | **see §5 verdict** |
| `message.channels["thought"]` + `extraContext["enable_thinking"]` | Expose model reasoning as `reasoning_content` | **DONE: feature-10a, §6** |
| `ConversationConfig.extraContext` | Per-request chat-template variables (e.g. `enable_thinking`) | **verified: consumed by the template (§6); a generic "RAG document" slot is NOT how it behaves** |
| `Session.runPrefill`/`runDecode` | Token-level control; prompt-cache reuse; embeddings-ish pooling experiments | unverified |
| `Conversation.cancelProcess()` | Truly halt native decode on client-disconnect / thermal / stop (not just stop streaming) | **API confirmed (§7.5, 0.12.0); on-device behavior probe pending (#125, `MidDecodeStopProbe`)** |
| `SamplerConfig.seed` | Deterministic/reproducible sampling (testing, `seed` passthrough) | available |
| `getBenchmarkInfo()` / `enableBenchmark` | Real prefill/decode tok/s + TTFT + exact token counts on the live path | **DEAD END (0.11.0): see below** |
| `overwritePromptTemplate` | Support models with broken/missing templates | available |
| `Capabilities(modelPath)` | Pre-flight model capability checks in the model picker | available |
| `maxNumImages` (EngineConfig) | Multi-image requests | available |

**Benchmark on the live path is a DEAD END in 0.11.0 (verified, `ReasoningChannelProbe` predecessor / `RelaisBackendBenchmarkTest`).** Setting `ExperimentalFlags.enableBenchmark = true` then calling `conversation.getBenchmarkInfo()` throws `INTERNAL: Benchmark is not enabled. Please make sure the BenchmarkParams is set in the EngineSettings.` — and `javap` on the AAR shows **no public `BenchmarkParams` and no `EngineSettings`** type; `EngineConfig(modelPath, backend, visionBackend, audioBackend, maxNumTokens, maxNumImages, cacheDir)` has no benchmark hook. The only populated `BenchmarkInfo` comes from the standalone `BenchmarkKt.benchmark(modelPath, backend, …)` one-shot, which re-loads the model and so **cannot run on the resident serving engine**. → Real per-request prefill/decode tok/s + TTFT + **exact `prompt_tokens`** are unreachable on the live path; Relais keeps the wall-clock decode estimate and the `x_relais_usage_note: prompt_tokens_estimated` flag. Exact token counts would need a tokenizer or the low-level `Session.runPrefill` count, not this API. (Confirms `RelaisEngine.kt` SPIKE-FINDINGS Q1; the table's prior "available" was wrong — a cautionary case for §-labels, see the `litertlm-native-api-probe-first` learned skill.)

## 9. How to regenerate / re-verify

```bash
scripts/dump-litertlm-api.sh          # prints the full public API of the pinned AAR
# Re-run the on-device probes after a version bump (rango / E2B):
#   MultiTurnReplayProbe, ToolCallingProbe, StructuredOutputProbe, ReasoningChannelProbe
# in Android/src/app/src/androidTest/java/cc/grepon/relais/
```
When a probe's behavior changes, update the "Verified on-device" claims and the §5/§6 verdicts here.
