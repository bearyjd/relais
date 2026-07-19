# Feature-03 — Multi-turn `messages[]` for the OpenAI-compatible endpoint

> Branch: `fix/relais-multiturn-messages`. No GitHub issue yet — self-contained correctness fix.
> Depends on the existing `src/test` infrastructure (landed in PR1).

## Summary

`RelaisHttpServer.parseOpenAiRequest()` (lines 368–393) reads only the **last `user` message**,
silently discarding the `system` prompt and every prior `assistant`/`user` turn. This makes the
`/v1/chat/completions` endpoint useless for any real chat client — system prompts are never seen
by the model and conversation history is lost between turns.

Fix: replace the "last user message only" scan with a pure function
`buildPromptParts(messages: JSONArray): ParsedMessages` that walks the entire `messages[]` array,
extracts the system prompt, extracts ordered prior-turn pairs, and extracts image/audio from the
last user message exactly as today. Feed the result into the existing `RelaisEngine.generate` call
by replaying prior turns through the LiteRT-LM session before sending the live user message. Add
overflow/truncation so a long history cannot exceed `MAX_NUM_TOKENS`.

## Current parser — verbatim, with line numbers

File: `Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpServer.kt`

```kotlin
367  /** Extracts text + first image + first audio from the last user message (string or parts array). */
368  private fun parseOpenAiRequest(body: JSONObject): RelaisRequest {
369    val messages = body.optJSONArray("messages") ?: JSONArray()
370    var text = ""
371    var image: ByteArray? = null
372    var audio: ByteArray? = null
373    for (i in messages.length() - 1 downTo 0) {
374      val msg = messages.optJSONObject(i) ?: continue
375      if (msg.optString("role") != "user") continue
376      when (val content = msg.opt("content")) {
377        is String -> text = content
378        is JSONArray ->
379          for (j in 0 until content.length()) {
380            val part = content.optJSONObject(j) ?: continue
381            when (part.optString("type")) {
382              "text" -> text = part.optString("text")
383              "image_url" ->
384                image = part.optJSONObject("image_url")?.optString("url")?.let { dataUriBytes(it) } ?: image
385              "input_audio" ->
386                audio = part.optJSONObject("input_audio")?.optString("data")?.let { decode(it) } ?: audio
387            }
388          }
389      }
390      break   // <-- exits after the first user message found scanning from the end
391    }
392    return RelaisRequest(text = text, imagePng = image, audioWav = audio)
393  }
```

### What is dropped

| Input element | Today | After fix |
|---|---|---|
| `{"role":"system","content":"..."}` | **Silently dropped** | Captured as system prompt |
| Prior `{"role":"user"}` turns | **Silently dropped** | Replayed into LiteRT-LM session |
| Prior `{"role":"assistant"}` turns | **Silently dropped** | Replayed into LiteRT-LM session |
| Image/audio in last user message | Preserved (correctly) | Preserved (unchanged logic) |
| Text in last user message | Preserved (correctly) | Preserved (unchanged logic) |

The `break` on line 390 is the key defect: the reverse scan finds the last `user` message and
immediately exits, so `system` and all `assistant` turns are never visited.

## Design

### 1. New pure data types (no Android dependency)

```kotlin
/** One turn from the OpenAI messages array, after normalization. */
data class ParsedTurn(
  val role: String,           // "user" | "assistant" | "system"
  val text: String,
  val imagePng: ByteArray? = null,
  val audioWav: ByteArray? = null,
)

/** Result of parsing the full messages[] array. */
data class ParsedMessages(
  val systemPrompt: String?,            // null if no system message present
  val history: List<ParsedTurn>,        // prior turns, oldest first (excludes last user turn)
  val lastUserText: String,             // text of the final user turn
  val lastUserImage: ByteArray? = null, // image from final user turn
  val lastUserAudio: ByteArray? = null, // audio from final user turn
)
```

### 2. Pure extraction function

Extract to a package-private top-level function so it is testable without a device or
`RelaisHttpServer` instance:

```kotlin
internal fun buildPromptParts(
  messages: JSONArray,
  maxHistoryChars: Int = MAX_HISTORY_CHARS,   // overflow policy constant
): ParsedMessages
```

Algorithm (in order):

1. Walk `messages` **forwards** (index 0 → last).
2. For each message, extract `role` and `content` (string or parts array). The parts-array
   extraction logic for `text`/`image_url`/`input_audio` is identical to the current code; lift it
   into a private helper `fun extractParts(content: JSONArray): Triple<String, ByteArray?, ByteArray?>`.
3. A `role == "system"` message sets `systemPrompt` (last one wins if there are multiple, which
   is non-standard but defensive).
4. `role == "user"` or `role == "assistant"` messages accumulate into `history` as `ParsedTurn`
   values — **except the last `user` message**, which becomes `lastUser*`.
5. Identify the last `user` message index before the loop so the "is this the last user?" check is
   O(1) (scan once for `lastUserIndex = messages.indices.last { messages.getJSONObject(it).optString("role") == "user" }`).

### 3. Context-window overflow / truncation policy

LiteRT-LM is initialized with `maxNumTokens = 1024` (constant `MAX_NUM_TOKENS` in `RelaisEngine.kt`
line 39). Token counting without running the tokenizer is impractical on the JVM; use a
**character-based proxy** (conservative: 1 token ≈ 4 chars for English, so 1024 tokens ≈ 4096
chars; use 3 chars/token = 3072 chars as the safe proxy).

Policy: **oldest-first drop, preserve system prompt always.**

```
MAX_HISTORY_CHARS = 3 * MAX_NUM_TOKENS   // = 3072; exposed as internal const for testing
```

After collecting history, if `history.sumOf { it.text.length } > MAX_HISTORY_CHARS`:
- Drop `history[0]`, re-check. Repeat until it fits.
- Always drop in `user`+`assistant` pairs from the oldest end (to preserve turn parity). If
  dropping yields an odd count (a dangling assistant turn with no preceding user), drop that too.
- The system prompt is **never** counted against the budget; it is always sent.
- Log a `Log.w(TAG, "History truncated: dropped $n turns to fit context window")` line when
  truncation fires (observable in logcat; not surfaced to the API caller).

### 4. LiteRT-LM session population (multi-turn replay)

The LiteRT-LM `Conversation` object used in `RelaisEngine.generate` is single-turn today:

```kotlin
// Today (RelaisEngine.kt lines 308-342)
conversation.sendMessageAsync(Contents.of(contents), callback, emptyMap())
```

`sendMessageAsync` is cumulative within a `Conversation`: calling it N times plays N turns. The
model sees the growing context window. This is the correct mechanism for history replay.

**Sequence for a request with system prompt + history:**

```
1. If systemPrompt != null:
     conversation.sendMessageAsync(Contents.of(Content.Text("<system>\n$systemPrompt\n</system>")), ...)
     // Wait for response; discard the assistant reply (it may be "OK" or similar preamble)
2. For each (userTurn, assistantTurn) pair in history:
     conversation.sendMessageAsync(Contents.of(Content.Text(userTurn.text)), ...)  // wait
     conversation.sendMessageAsync(Contents.of(Content.Text(assistantTurn.text)), ...) // wait
3. Send the live user message (with image/audio if present) and stream to the caller as today.
```

**Blocking pattern:** each replay call uses the same `CountDownLatch(1)` pattern already used for
the live message. Wrap each replay call in a private `fun replaySend(conversation, contents)`:

```kotlin
private fun replaySend(conversation: Conversation, vararg contentItems: Content) {
  val latch = CountDownLatch(1)
  val err = arrayOfNulls<Throwable>(1)
  conversation.sendMessageAsync(
    Contents.of(contentItems.toList()),
    object : MessageCallback {
      override fun onMessage(message: Message) = Unit // discard historical reply
      override fun onDone() = latch.countDown()
      override fun onError(t: Throwable) { err[0] = t; latch.countDown() }
    },
    emptyMap(),
  )
  if (!latch.await(30, TimeUnit.SECONDS)) error("history replay timed out")
  err[0]?.let { throw it }
}
```

**Impact on `RelaisEngine.generate`:** the function signature stays unchanged (`RelaisRequest` is
the input). Instead, `RelaisRequest` gains an optional `history` field:

```kotlin
data class RelaisRequest(
  val text: String,
  val imagePng: ByteArray? = null,
  val audioWav: ByteArray? = null,
  val systemPrompt: String? = null,            // new
  val history: List<ParsedTurn> = emptyList(), // new
)
```

`parseOpenAiRequest` (renamed `buildRelaisRequest`) calls `buildPromptParts` and maps to
`RelaisRequest`. The `/generate` endpoint (non-OpenAI path) passes `systemPrompt=null,
history=emptyList()` — no change to its caller.

The existing `RelaisNodeService.LocalBinder.generate(request: RelaisRequest)` remains unchanged
(default values handle backward compat).

### 5. Role mapping to LiteRT-LM conventions

LiteRT-LM `Content` types today: `Content.Text(String)`, `Content.ImageBytes(ByteArray)`,
`Content.AudioBytes(ByteArray)`. There is **no explicit role field in the Content API** — the
conversation model infers role from turn order: odd sends are user, even sends are model. This
matches the replay strategy above (user send, then model send, alternating).

For the system prompt: LiteRT-LM has no `Content.System` type. The industry convention for models
that lack a native system token is to prepend the system text in the first user turn using a
`<system>…</system>` wrapper, which all Gemma and Qwen3 chat templates recognize. Use this
approach. If a future LiteRT-LM version exposes a dedicated system-role API, update only
`replaySend` for the system prompt.

**Parity requirement:** if `history` has an odd count after truncation (i.e., ends on a user turn
with no assistant reply), that dangling user turn becomes an earlier user context; it is replayed
normally. The session still ends with the live user message.

## TDD plan (`src/test`, pure JUnit — no device)

All tests go in `src/test/java/cc/grepon/relais/OpenAiRequestParserTest.kt`.

Test the pure `buildPromptParts(JSONArray)` function in isolation — no `RelaisHttpServer`,
no `Context`, no Android SDK.

### Case 1 — system-only (no user turns) RED

```kotlin
@Test fun `system-only messages returns system prompt and empty history`() {
  val messages = JSONArray("""[{"role":"system","content":"Be concise."}]""")
  // Before fix: buildPromptParts doesn't exist → compilation error (RED).
  // After fix:
  val result = buildPromptParts(messages)
  assertEquals("Be concise.", result.systemPrompt)
  assertTrue(result.history.isEmpty())
  assertEquals("", result.lastUserText)
}
```

### Case 2 — system + single user (canonical first request) RED

```kotlin
@Test fun `system prompt and single user turn extracted correctly`() {
  val messages = JSONArray("""
    [{"role":"system","content":"You are a pirate."},
     {"role":"user","content":"Ahoy!"}]
  """)
  val result = buildPromptParts(messages)
  assertEquals("You are a pirate.", result.systemPrompt)
  assertTrue(result.history.isEmpty()) // single user = live turn, not history
  assertEquals("Ahoy!", result.lastUserText)
}
```

### Case 3 — multi-turn history preserved RED

```kotlin
@Test fun `multi-turn history accumulated in order`() {
  val messages = JSONArray("""
    [{"role":"system","content":"SYS"},
     {"role":"user","content":"Q1"},
     {"role":"assistant","content":"A1"},
     {"role":"user","content":"Q2"},
     {"role":"assistant","content":"A2"},
     {"role":"user","content":"Q3"}]
  """)
  val result = buildPromptParts(messages)
  assertEquals("SYS", result.systemPrompt)
  assertEquals(4, result.history.size)
  assertEquals("Q1", result.history[0].text); assertEquals("user",      result.history[0].role)
  assertEquals("A1", result.history[1].text); assertEquals("assistant", result.history[1].role)
  assertEquals("Q2", result.history[2].text); assertEquals("user",      result.history[2].role)
  assertEquals("A2", result.history[3].text); assertEquals("assistant", result.history[3].role)
  assertEquals("Q3", result.lastUserText)
}
```

### Case 4 — multimodal parts preserved in last user message RED

```kotlin
@Test fun `image and audio extracted from last user message parts array`() {
  val fakeB64 = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
  val messages = JSONArray("""
    [{"role":"user","content":[
       {"type":"text","text":"describe this"},
       {"type":"image_url","image_url":{"url":"data:image/png;base64,$fakeB64"}},
       {"type":"input_audio","input_audio":{"data":"$fakeB64","format":"wav"}}
    ]}]
  """)
  val result = buildPromptParts(messages)
  assertEquals("describe this", result.lastUserText)
  assertNotNull(result.lastUserImage)
  assertNotNull(result.lastUserAudio)
  assertArrayEquals(byteArrayOf(1, 2, 3), result.lastUserImage)
  assertArrayEquals(byteArrayOf(1, 2, 3), result.lastUserAudio)
  // Multimodal content must NOT appear in history
  assertTrue(result.history.isEmpty())
}
```

### Case 5 — overflow truncation drops oldest turns, preserves system RED

```kotlin
@Test fun `overflow truncation drops oldest turns and always keeps system prompt`() {
  // Build a history that exceeds MAX_HISTORY_CHARS.
  val longText = "x".repeat(MAX_HISTORY_CHARS / 2 + 1)
  val messages = JSONArray().apply {
    put(JSONObject().put("role", "system").put("content", "SYS"))
    put(JSONObject().put("role", "user").put("content", longText))
    put(JSONObject().put("role", "assistant").put("content", longText))
    put(JSONObject().put("role", "user").put("content", longText))
    put(JSONObject().put("role", "assistant").put("content", longText))
    put(JSONObject().put("role", "user").put("content", "live"))
  }
  val result = buildPromptParts(messages)
  assertEquals("SYS", result.systemPrompt)  // never dropped
  // history is truncated: at least one pair dropped
  val historyChars = result.history.sumOf { it.text.length }
  assertTrue("history must fit budget: $historyChars > $MAX_HISTORY_CHARS",
    historyChars <= MAX_HISTORY_CHARS)
  assertEquals("live", result.lastUserText)
  // Remaining history must have even count (no dangling assistant turn)
  assertEquals(0, result.history.size % 2)
}
```

### RED → GREEN execution

Run before implementation to confirm compilation/test failure:
```
./gradlew :app:testDebugUnitTest --tests "cc.grepon.relais.OpenAiRequestParserTest"
```
Expected: compile error on `buildPromptParts` (symbol not found) for cases 1–5. That is the RED.

Implement `buildPromptParts` as a top-level `internal fun` in a new file
`RelaisOpenAiParser.kt` (same package). Migrate `parseOpenAiRequest` to call it.
Run again — all 5 tests must pass. That is the GREEN.

Then run the full suite to confirm no regressions:
```
./gradlew :app:testDebugUnitTest
```

## Acceptance criteria

- All 5 new `OpenAiRequestParserTest` tests fail before the fix (RED) and pass after (GREEN).
- `RelaisEngineConfigTest`, `RelaisModelCatalogTest`, `RelaisHuggingFaceTest` continue to pass.
- `./gradlew testDebugUnitTest` + `assembleRelease` both green.
- CI green.
- The `/generate` endpoint (non-OpenAI) is unaffected: `RelaisRequest` default arguments
  (`systemPrompt=null, history=emptyList()`) preserve its behavior identically.
- Independent review APPROVE on the final diff (do not self-merge).

## Deferred on-device gate (document in PR, do NOT fake)

Run a real multi-turn chat session against the live node (Pixel 9 / comet, `4A111FDKD0000C`)
using a chat client (e.g. `curl` script or Open WebUI configured to `https://<LAN-IP>:8443/v1`):

```
Turn 1: system="Answer only in haiku", user="What is Rust?"
Turn 2: user="Give me an example."   <-- model should still be in haiku style
```

If the model honors the system prompt in turn 1 and the haiku constraint carries into turn 2, the
deferred gate passes. Owner runs later; do not block PR merge on it.

## Guardrails / PR

- Branch `fix/relais-multiturn-messages`; PR target `main`.
- **Do not regress multimodal**: the image/audio extraction from the last user message must be
  preserved exactly as today (Case 4 enforces this).
- **Do not touch AICore**: `RelaisAicore.generate` receives a `RelaisRequest`; the new fields
  (`systemPrompt`, `history`) are silently ignored by it — no change needed to the AICore path.
- **Do not exceed `MAX_NUM_TOKENS` in replay**: the overflow policy (Case 5) must fire before
  submitting to `RelaisEngine.generate`. Never pass more history than the budget allows.
- Keep the change minimal: `RelaisOpenAiParser.kt` (new, pure functions) +
  `RelaisHttpServer.kt` (call site only) + `RelaisEngine.kt` (`RelaisRequest` fields + replay
  loop in `generate`). No other files.
