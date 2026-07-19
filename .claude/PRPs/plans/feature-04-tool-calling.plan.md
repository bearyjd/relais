# Feature 04 — OpenAI Tool Calling for `/v1/chat/completions`

> Branch: `feat/relais-tool-calling`.
> **DEPENDS ON feature-03 (multi-turn messages).** feature-03 threads a full `messages` history through
> `RelaisEngine.generate`; feature-04 requires that seam to inject tool-result turns and to carry
> conversation state across the tool-call → tool-result round-trip. Do not begin this work until
> feature-03 is merged and green.

---

## Summary

Accept OpenAI `tools` + `tool_choice` fields in `POST /v1/chat/completions`, drive the model to emit
a tool call (via constrained JSON prompt injection — see §Engine path), parse the model output back
into the OpenAI `tool_calls` shape, and return `finish_reason:"tool_calls"`. In the subsequent
request accept `role:"tool"` messages and fold them into the conversation so the model can produce a
final natural-language reply. This is the minimal round-trip required by agent frameworks (LangChain,
OpenAI Agents SDK, LlamaIndex) that rely on the OpenAI tool-calling protocol.

**Native LiteRT-LM Tool Use is NOT available** (see §Engine path). The bundled
`com.google.ai.edge.litertlm` API surface in this project is:
`Backend`, `BenchmarkInfo`, `Content`, `Contents`, `ConversationConfig`, `Engine`, `EngineConfig`,
`ExperimentalApi`, `ExperimentalFlags`, `Message`, `MessageCallback`, `SamplerConfig`.
No `FunctionTool`, `ToolCall`, `ToolDeclaration`, or structured-output / constrained-decoding type is
exposed. `sendMessageAsync` takes `Contents` + `MessageCallback` + `emptyMap()` — the third argument
is an `emptyMap()` with no tool-definition slot visible. **A prompt-based fallback is therefore the
only viable engine path.** This document designs that fallback as a first-class, unit-testable seam.

---

## Context: current request/response shaping

### Where `tools` would be parsed — `parseOpenAiRequest` (HttpServer.kt:368-393)

```kotlin
// RelaisHttpServer.kt:368-393
private fun parseOpenAiRequest(body: JSONObject): RelaisRequest {
    val messages = body.optJSONArray("messages") ?: JSONArray()
    var text = ""
    var image: ByteArray? = null
    var audio: ByteArray? = null
    for (i in messages.length() - 1 downTo 0) {
        val msg = messages.optJSONObject(i) ?: continue
        if (msg.optString("role") != "user") continue   // <-- only the LAST user message today
        when (val content = msg.opt("content")) {
            is String -> text = content
            is JSONArray -> /* extract text/image_url/input_audio parts */
        }
        break
    }
    return RelaisRequest(text = text, imagePng = image, audioWav = audio)
}
```

**Today's gap:** `body.optJSONArray("tools")` is never read; `body.optString("tool_choice")` is never
read; `role:"tool"` messages are silently discarded (the loop skips non-`"user"` roles).

### Where `tool_calls` would be emitted — `handleOpenAi` (HttpServer.kt:310-365)

```kotlin
// RelaisHttpServer.kt:316-330 (non-streaming path)
val result = RelaisEngine.generate(context, request, shouldCancel = { … })
val resp = JSONObject()
    .put("id", id)
    .put("object", "chat.completion")
    .put("model", model)
    .put("choices", JSONArray().put(
        JSONObject()
            .put("index", 0)
            .put("message", JSONObject().put("role", "assistant").put("content", result.text))
            .put("finish_reason", "stop")))   // <-- always "stop" today
```

**Today's gap:** `finish_reason` is hardcoded `"stop"`; `message.tool_calls` is never emitted; the
response object has no mechanism to carry structured tool-call JSON.

### Current `RelaisRequest` / `RelaisResult` DTOs (RelaisEngine.kt:86-96)

```kotlin
data class RelaisRequest(
    val text: String,
    val imagePng: ByteArray? = null,
    val audioWav: ByteArray? = null,
) {
    val modalities: RequestModalities
        get() = RequestModalities(hasImage = imagePng != null, hasAudio = audioWav != null)
}

data class RelaisResult(val text: String, val backend: RelaisBackend, val decodeTokensPerSec: Double)
```

`RelaisRequest.text` is the single text prompt today. feature-03 will extend this (or add a parallel
`messages: List<ChatMessage>` field); feature-04 adds `tools` + `toolChoice` alongside it.

---

## Design

### 1. Request DTOs (pure Kotlin, no Android types)

**New file: `cc/grepon/relais/openai/ToolDtos.kt`** (pure JVM; unit-testable without Robolectric)

```kotlin
// Mirrors OpenAI wire format exactly so JSON field names need no @SerializedName
data class OAIFunctionDef(
    val name: String,
    val description: String? = null,
    val parameters: org.json.JSONObject? = null,  // raw JSON schema, pass-through
)

data class OAITool(
    val type: String,           // "function"
    val function: OAIFunctionDef,
)

// tool_choice: "none" | "auto" | "required" | {"type":"function","function":{"name":"…"}}
sealed interface ToolChoice {
    data object None     : ToolChoice
    data object Auto     : ToolChoice
    data object Required : ToolChoice
    data class Forced(val functionName: String) : ToolChoice
}

// Parsed from a role:"tool" message in the messages array
data class ToolResultMessage(
    val toolCallId: String,
    val content: String,
)

// Emitted in the response
data class OAIToolCall(
    val id: String,             // "call_<random>"
    val type: String = "function",
    val function: OAIFunctionCall,
)
data class OAIFunctionCall(
    val name: String,
    val arguments: String,      // JSON string (model-emitted, may be malformed)
)

// Extended request context passed down to the engine layer
data class ToolContext(
    val tools: List<OAITool>,
    val toolChoice: ToolChoice,
)
```

All types are `org.json`-free except `OAIFunctionDef.parameters` which holds the raw schema
pass-through as a `JSONObject` (never re-serialized by us — the model only reads a text
description, not the live schema object). No Gson dependency added; all parsing is done with
`org.json.JSONObject`/`JSONArray` already in use throughout `RelaisHttpServer`.

### 2. Parser seam — `ToolRequestParser` (pure JVM)

**New file: `cc/grepon/relais/openai/ToolRequestParser.kt`**

Two pure functions (no `Context`, no Android types):

```kotlin
object ToolRequestParser {
    /** Parse tools + tool_choice from a /v1/chat/completions body. Returns null if no tools. */
    fun parseToolContext(body: JSONObject): ToolContext? { … }

    /** Parse role:"tool" messages from the messages array into ToolResultMessage list. */
    fun parseToolResults(messages: JSONArray): List<ToolResultMessage> { … }
}
```

`parseToolContext` reads `body.optJSONArray("tools")` — returns `null` (no-op path) when absent or
empty. Reads `body.opt("tool_choice")`: string `"none"/"auto"/"required"` → sealed variants;
`JSONObject` with `type:"function"` → `Forced(name)`; absent → `Auto`.

`parseToolResults` scans `messages` for entries with `"role":"tool"`, extracts `"tool_call_id"` and
`"content"`, returns them in order.

### 3. Engine path — prompt-injection fallback

Because no native Tool Use API exists in `com.google.ai.edge.litertlm`, tool dispatch is driven by
**prompt injection + output parsing**:

**Prompt injection (`ToolPromptBuilder.kt`, pure JVM):**

```kotlin
object ToolPromptBuilder {
    /**
     * Injects a tool-call system prompt into the user text so the model emits structured JSON.
     * Called when toolContext != null && toolChoice != ToolChoice.None.
     *
     * Produces a prefix like:
     *   "You have access to the following tools:\n<JSON schema list>\n\n
     *    When you want to call a tool respond ONLY with a JSON object in this format:
     *    {\"tool_call\":{\"name\":\"<name>\",\"arguments\":{…}}}\n
     *    Otherwise respond normally.\n\n<original user prompt>"
     *
     * When toolChoice == Required, adds: "You MUST call one of the tools."
     * When toolChoice == Forced(name), adds: "You MUST call tool '<name>'."
     */
    fun injectToolPrompt(userText: String, ctx: ToolContext): String { … }

    /**
     * Injects tool results back into the conversation text for the follow-up turn.
     * Appends a structured block describing each tool result before the user's next message.
     */
    fun injectToolResults(userText: String, results: List<ToolResultMessage>): String { … }
}
```

The JSON schema list appended to the prompt uses `OAIFunctionDef.name` + `description` only (the
`parameters` JSONObject is serialized as-is if present, else omitted). This keeps the prompt tight
and avoids encoding the full JSON schema for every request.

**Output parser seam (`ToolCallParser.kt`, pure JVM):**

```kotlin
object ToolCallParser {
    /**
     * Attempts to extract a tool call from raw model output.
     * Returns a parsed OAIToolCall on success; null if the output is a normal reply.
     * On malformed JSON in the arguments field, returns the OAIToolCall with arguments="{}"
     * and logs a warning — does NOT throw (graceful degradation).
     *
     * Looks for the sentinel object {"tool_call":{…}} anywhere in the output
     * (the model may emit surrounding prose; scan for the first '{' bracket).
     */
    fun parse(rawOutput: String): OAIToolCall? { … }
}
```

`parse` uses `org.json.JSONObject` (already on classpath). Strategy:
1. Scan for `"tool_call"` substring; if absent → return `null` (normal reply).
2. Find the enclosing `{…}` bracket (first `{` before the match, last balanced `}` after).
3. Parse with `JSONObject`; extract `.getJSONObject("tool_call")` → name + arguments.
4. Validate `name` is non-blank; validate `arguments` parses as `JSONObject` — if not, substitute
   `"{}"` and emit a `Log.w`.
5. Generate `id = "call_" + System.currentTimeMillis()`.
6. Return `OAIToolCall`.

**`toolChoice == None`:** skip injection entirely; return normal `"stop"` response even if tools are
listed. This is spec-correct.

**`toolChoice == Auto`:** inject prompt; if `ToolCallParser.parse` returns null, treat as a normal
reply (`finish_reason:"stop"`).

**`toolChoice == Required` / `Forced`:** inject prompt with must-call directive; if parse fails,
return the raw text with `finish_reason:"stop"` rather than an error (graceful degradation; log the
failure).

### 4. Response shaping — `handleOpenAi` changes

In `RelaisHttpServer.handleOpenAi` (non-streaming path):

```kotlin
val toolCtx = ToolRequestParser.parseToolContext(body)
val request = parseOpenAiRequest(body, toolCtx)   // extended to pass toolCtx for prompt injection

val result = RelaisEngine.generate(context, request, shouldCancel = { … })

val toolCall = if (toolCtx != null && toolCtx.toolChoice != ToolChoice.None)
    ToolCallParser.parse(result.text) else null

val (messageJson, finishReason) = if (toolCall != null) {
    val tcJson = JSONObject()
        .put("id", toolCall.id)
        .put("type", toolCall.type)
        .put("function", JSONObject()
            .put("name", toolCall.function.name)
            .put("arguments", toolCall.function.arguments))
    JSONObject()
        .put("role", "assistant")
        .put("content", JSONObject.NULL)
        .put("tool_calls", JSONArray().put(tcJson)) to "tool_calls"
} else {
    JSONObject().put("role", "assistant").put("content", result.text) to "stop"
}
```

**Streaming:** when `finish_reason` would be `"tool_calls"`, streaming is upgraded to a
**buffered-then-emit** path: accumulate the full decode into `StringBuilder` (same as the existing
non-streaming latch path), then emit a single SSE `tool_calls` chunk + `[DONE]`. This avoids partial
JSON tool-call chunks reaching the client mid-stream. Normal replies continue to stream token-by-token.

### 5. `role:"tool"` message handling

In `parseOpenAiRequest` (extended per feature-03 multi-turn), if the last message before the final
`user` message contains `role:"tool"` entries, `ToolRequestParser.parseToolResults` extracts them and
`ToolPromptBuilder.injectToolResults` prepends the results block to the user text. The conversation
is then submitted as a single-turn prompt with the tool result context embedded — the simplest
stateless approach consistent with feature-03's multi-turn threading.

### 6. Seam diagram

```
POST /v1/chat/completions body
         │
         ▼
ToolRequestParser.parseToolContext()     ← SEAM A (pure, unit-testable)
         │  ToolContext? (tools + choice)
         ▼
ToolPromptBuilder.injectToolPrompt()     ← SEAM B (pure, unit-testable)
         │  augmented user text
         ▼
RelaisEngine.generate()                  ← unchanged engine call
         │  RelaisResult.text (raw model output)
         ▼
ToolCallParser.parse()                   ← SEAM C (pure, unit-testable)
         │  OAIToolCall? or null
         ▼
handleOpenAi response shaping            ← finish_reason + tool_calls JSON
```

Each seam (A, B, C) is a pure function with no Android/Context dependency — testable with plain
JUnit4 on the JVM (`src/test`), mirroring `RelaisModelCatalogTest` style.

---

## TDD plan (`src/test`, pure JUnit4, no Robolectric)

All tests live in `Android/src/app/src/test/java/cc/grepon/relais/openai/`.
Write RED first; run `./gradlew :app:testDebugUnitTest --offline` to confirm failure; implement;
confirm GREEN.

### Suite 1 — `ToolRequestParserTest`

| # | Case | Input | Expected |
|---|---|---|---|
| 1 | No tools field | body with no `"tools"` key | `parseToolContext` returns `null` |
| 2 | Empty tools array | `"tools":[]` | returns `null` |
| 3 | Single function tool | valid `{"type":"function","function":{"name":"get_weather","description":"…"}}` | returns `ToolContext` with 1 tool, name=="get_weather" |
| 4 | tool_choice absent | no `tool_choice` key | `toolChoice == Auto` |
| 5 | tool_choice "none" | `"tool_choice":"none"` | `toolChoice == None` |
| 6 | tool_choice "required" | `"tool_choice":"required"` | `toolChoice == Required` |
| 7 | tool_choice forced | `{"type":"function","function":{"name":"fn"}}` | `toolChoice == Forced("fn")` |
| 8 | role:tool messages | messages array with 2 `role:"tool"` entries | `parseToolResults` returns 2 `ToolResultMessage` in order |
| 9 | Mixed roles | user/assistant/tool messages | only tool entries returned |
| 10 | Malformed tool entry | tool entry missing `tool_call_id` | entry skipped (no crash) |

### Suite 2 — `ToolPromptBuilderTest`

| # | Case | Expected |
|---|---|---|
| 1 | Auto choice, 1 tool | output contains tool name, "tool_call" sentinel instruction, original user text |
| 2 | Required choice | output additionally contains "MUST call" directive |
| 3 | Forced choice | output contains forced tool name directive |
| 4 | Two tools | both names appear in the prompt |
| 5 | Tool results injection | injected block contains `tool_call_id` and content from each result |

### Suite 3 — `ToolCallParserTest` (the critical reliability suite)

| # | Case | Input (raw model output) | Expected |
|---|---|---|---|
| 1 | Clean JSON-only output | `{"tool_call":{"name":"get_weather","arguments":{"city":"London"}}}` | `OAIToolCall` with name=="get_weather", arguments==`{"city":"London"}` |
| 2 | JSON embedded in prose | `"Sure! {"tool_call":{…}} Let me help."` | OAIToolCall extracted correctly |
| 3 | Arguments is a JSON string | arguments serialized as a string not object | parsed into arguments string |
| 4 | Malformed arguments | `{"tool_call":{"name":"fn","arguments":"not json {{"}}` | returns OAIToolCall with arguments=`"{}"`, no throw |
| 5 | Missing name | `{"tool_call":{"arguments":{}}}` | returns `null` (not a tool call) |
| 6 | No tool_call key | normal prose output | returns `null` |
| 7 | Empty output | `""` | returns `null` |
| 8 | Nested braces in arguments | `{"tool_call":{"name":"fn","arguments":{"q":{"nested":true}}}}` | arguments contains nested object correctly |
| 9 | Multiple tool_call blocks | model emits two | first one wins |
| 10 | tool_call_id generation | any valid call | id starts with `"call_"` |

### Suite 4 — `ToolFinishReasonTest`

Pure shaping logic (no engine); assert:

| # | Case | Expected |
|---|---|---|
| 1 | toolCtx null + parse returns null | finish_reason=="stop", no tool_calls field |
| 2 | toolCtx present + parse returns OAIToolCall | finish_reason=="tool_calls", message has tool_calls array, content==null |
| 3 | toolCtx None + output has tool_call | finish_reason=="stop" (None suppresses) |

---

## Acceptance criteria

1. `./gradlew :app:testDebugUnitTest --offline` green with all suites above (RED before, GREEN after
   each fix).
2. `./gradlew :app:assembleRelease -x lint --offline` green (no new compile errors or R8 issues).
3. Independent code review APPROVE on the final diff (do not self-approve; wait for review).
4. CI green (testDebugUnitTest + assembleRelease jobs).
5. PR merged to `main`.

**Deferred on-device gate (document in PR, do NOT fake):**
An agent framework (e.g. a minimal Python LangChain or OpenAI Agents SDK script) drives a complete
tool-call round-trip against the live node:
- POST `/v1/chat/completions` with `tools:[{get_weather}]` + `tool_choice:"auto"` + user prompt
  "What's the weather in London?"
- Receive `finish_reason:"tool_calls"` + a `tool_calls` array.
- POST a follow-up with `role:"tool"` result injected.
- Receive `finish_reason:"stop"` with a natural-language reply that references the tool result.

Owner runs this gate on the Pixel 9 node (`comet`) when available.

---

## Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Small-model tool-call reliability (gemma-4-E4B, Qwen3 may not reliably emit the sentinel JSON) | High | Med | `toolChoice == Auto` degrades gracefully to `"stop"` when parse fails; `Required`/`Forced` also degrade rather than error. Document reliability caveat in PR. Prompt phrasing is tunable post-merge. |
| Model emits valid-looking but semantically wrong JSON (wrong tool name, missing fields) | Med | Low | Parser validates name non-blank; malformed arguments substitute `"{}"`. Caller framework handles semantic errors. |
| Bracket-scan in `ToolCallParser` misidentifies prose as JSON | Med | Low | False-positive tool-call detection: the model must emit the exact key `"tool_call"` — highly unlikely in natural prose. Scan is keyed on substring match before bracket extraction. |
| Streaming tool_calls buffering increases latency | Low | Low | Tool-call decode is bounded by `maxNumTokens`; the buffered path is identical to the non-streaming engine path. |
| feature-03 not yet merged when this is implemented | High (timing) | High | Hard dependency: do not start feature-04 until feature-03 is merged+green. `parseOpenAiRequest` must already thread `messages` history; feature-04 adds `tools` on top. |
| `org.json.JSONObject` bracket-balancing edge cases (e.g. JSON strings containing `{`) | Med | Med | Use `JSONObject(substring)` constructor — it does full RFC parsing, not naive bracket matching. Catch `JSONException`; return null/empty-args on failure. |

---

## Guardrails / PR

- Branch: `feat/relais-tool-calling` off `main` (after feature-03 merge).
- Do NOT touch `RelaisAicore`, AICore paths, or any non-Relais subsystem.
- Do NOT add new Gradle dependencies — use `org.json` (already present) and the existing Kotlin
  stdlib. No Gson added to new files (pure JVM DTOs use `org.json` or plain data classes).
- New files under `cc/grepon/relais/openai/` use the AGPL-3.0 / Entrevoix copyright header
  (matching other new Relais files). Modified Google-origin files keep their Apache header.
- Keep `RelaisEngine.generate` signature stable — the tool-prompt injection happens in
  `ToolPromptBuilder` before the call, not inside `generate`. This preserves the existing
  single-turn and (post-feature-03) multi-turn paths unchanged.
- PR must include: summary of LiteRT-LM Tool Use investigation result (not available; fallback
  used), the deferred on-device gate description, and a note on small-model reliability.
- Review the diff; merge on green CI.

---

## Mandatory reading before implementation

| Priority | File | Lines | Why |
|---|---|---|---|
| P0 | `RelaisHttpServer.kt` | 310–393 | `handleOpenAi` + `parseOpenAiRequest` — the two functions being modified |
| P0 | `RelaisEngine.kt` | 262–358 | `generate` signature + `sendMessageAsync` call — must remain stable |
| P0 | `RelaisEngine.kt` | 1–35 | Full litertlm import list — confirms no Tool Use API |
| P1 | feature-03 plan (when written) | all | multi-turn seam; `parseOpenAiRequest` extension point |
| P1 | `src/test/.../RelaisModelCatalogTest.kt` | all | JUnit4 + pure-JVM test idiom to mirror |
| P2 | `RelaisEngine.kt` | 86–96 | `RelaisRequest`/`RelaisResult` DTOs — fields to extend |
