# Feature 02 — Token usage block in chat/completions responses

> Branch: `feat/relais-usage-block`. No upstream GitHub issue yet — open one when the branch is
> created and link it here.

## Summary

The OpenAI `/v1/chat/completions` spec includes a `usage` object in every non-streaming response
and optionally in the final streaming chunk. Relais currently omits it entirely. Clients that bill
by token, measure context efficiency, or auto-truncate conversation history on token budget all
depend on this field. This PR adds `usage{prompt_tokens, completion_tokens, total_tokens}` to both
the non-streaming response and the terminal SSE chunk (`finish_reason: "stop"`) using counts the
engine already tracks internally.

## Context (VERIFIED code refs)

All line numbers reference `main` at the time this plan was written.

### What the engine actually exposes — `RelaisEngine.kt`

`generate()` (lines 262–358) maintains these local decode-time counters inside the
`sendMessageAsync` callback:

```kotlin
var tokens = 0          // incremented on every MessageCallback.onMessage delta (line 315)
var firstTokenNs = 0L   // wall-clock ns at first token (line 316)
var lastTokenNs  = 0L   // wall-clock ns at last token (line 317)
```

`tokens` is the **decode (completion) token count** — the number of `onMessage` callbacks fired,
which equals output tokens from the model's perspective. LiteRT-LM does not surface a
`BenchmarkInfo`-style prompt-token count through the normal `sendMessageAsync` path (confirmed in
`SPIKE-FINDINGS.md` / Q1 comment at line 299: "BenchmarkInfo only populates via the library's
benchmark() path, not normal conversations"). Prompt token count is therefore **not available** from
the LiteRT-LM engine in non-benchmark mode.

`RelaisResult` (line 96) carries only what `generate()` assembles from those counters:

```kotlin
data class RelaisResult(val text: String, val backend: RelaisBackend, val decodeTokensPerSec: Double)
```

The `decodeTokensPerSec` is derived from `(tokens - 1) / decodeSec` (line 346); the raw `tokens`
count is not forwarded.

### Non-streaming response — `RelaisHttpServer.kt` lines 316–330

```kotlin
val resp =
  JSONObject()
    .put("id", id)
    .put("object", "chat.completion")
    .put("model", model)
    .put("choices", JSONArray().put(
      JSONObject()
        .put("index", 0)
        .put("message", JSONObject().put("role", "assistant").put("content", result.text))
        .put("finish_reason", "stop")))
```

No `usage` key is added today.

### Streaming final chunk — `RelaisHttpServer.kt` lines 358–360

```kotlin
sendChunk(null, "stop")
out.write("data: [DONE]\n\n".toByteArray()); out.flush()
```

`sendChunk` (lines 343–350) builds a `chat.completion.chunk` JSON object with `choices` only — no
`usage`.

### `sendChunk` closure shape — lines 343–350

```kotlin
fun sendChunk(delta: String?, finish: String?) {
  val choice = JSONObject().put("index", 0)
    .put("delta", if (delta != null) JSONObject().put("content", delta) else JSONObject())
    .put("finish_reason", finish ?: JSONObject.NULL)
  val chunk = JSONObject().put("id", id).put("object", "chat.completion.chunk")
    .put("model", model).put("choices", JSONArray().put(choice))
  out.write("data: $chunk\n\n".toByteArray()); out.flush()
}
```

Extending the final `sendChunk(null, "stop")` call to also emit `usage` requires either widening
the closure signature or adding a second `sendUsageChunk` call after it.

## Design

### Token count sourcing

| Field | Source | Availability |
|---|---|---|
| `completion_tokens` | `tokens` counter in `generate()` callback | Available via `RelaisResult` (requires adding the field) |
| `prompt_tokens` | LiteRT-LM tokenizer count | **Not available** via `sendMessageAsync` in litertlm 0.11/0.13 — engine-estimated only |
| `total_tokens` | `prompt_tokens + completion_tokens` | Exact only when prompt is available |

**Decision:** surface `completion_tokens` exactly (engine-counted). For `prompt_tokens`, use a
word-boundary heuristic estimate (`text.trim().split(Regex("\\s+")).size`) on the concatenated
prompt text as a best-effort approximation. Set `total_tokens = prompt_tokens + completion_tokens`.
Document in the API response that `prompt_tokens` is an estimate (a top-level `"usage_note"` field
on the `usage` object, value `"prompt_tokens_estimated"`), and in the PR description / inline
comments. Do NOT claim exactness. If a future litertlm version exposes tokenizer counts, the note
field can be dropped without breaking callers.

This matches the precedent set by other on-device inference servers that lack a detached tokenizer.

### `RelaisResult` change

Add `completionTokens: Int` to the result type so the HTTP layer does not need to re-count:

```kotlin
data class RelaisResult(
    val text: String,
    val backend: RelaisBackend,
    val decodeTokensPerSec: Double,
    val completionTokens: Int,   // NEW — raw token count from the callback loop
)
```

Change the construction site in `generate()` (line 349):

```kotlin
RelaisResult(text = sb.toString(), backend = backend, decodeTokensPerSec = tokS, completionTokens = tokens)
```

The NPU/AICore path (`RelaisBackend.NPU_AICORE`, lines 275–278) does not count tokens; it returns
`completionTokens = 0` (unknown). That path is marked UNVERIFIED and never selected on the Pixel 9.

### Pure usage-assembly function

Extract into an `internal` pure function (no `Context`, no Android types) so the unit test runs
on the JVM without Robolectric:

```kotlin
internal fun buildUsageObject(promptText: String, completionTokens: Int): JSONObject {
    val promptTokens = estimatePromptTokens(promptText)
    return JSONObject()
        .put("prompt_tokens", promptTokens)
        .put("completion_tokens", completionTokens)
        .put("total_tokens", promptTokens + completionTokens)
        .put("usage_note", "prompt_tokens_estimated")
}

internal fun estimatePromptTokens(text: String): Int =
    if (text.isBlank()) 0 else text.trim().split(Regex("\\s+")).size
```

### Non-streaming wiring

In `handleOpenAi()`, after `generate()` returns, add `usage` to `resp`:

```kotlin
val usage = buildUsageObject(request.text, result.completionTokens)
val resp = JSONObject()
    ...existing fields...
    .put("usage", usage)
```

`request.text` is the text extracted by `parseOpenAiRequest()` (the last user message text,
lines 368–392) — a reasonable prompt proxy for the heuristic estimate.

### Streaming wiring

Add a `sendFinalChunk` call that includes `usage` in the terminal `stop` chunk.  The OpenAI spec
allows `usage` on the final chunk only (when `stream_options: {include_usage: true}` is set by the
caller, but many clients check for it unconditionally). Always include it on the `finish_reason:
"stop"` chunk for maximum compatibility:

```kotlin
// Replace the bare sendChunk(null, "stop") with:
val usage = buildUsageObject(request.text, result.completionTokens)
val finalChunk = JSONObject().put("id", id).put("object", "chat.completion.chunk")
    .put("model", model)
    .put("choices", JSONArray().put(
        JSONObject().put("index", 0)
            .put("delta", JSONObject())
            .put("finish_reason", "stop")))
    .put("usage", usage)
out.write("data: $finalChunk\n\n".toByteArray()); out.flush()
out.write("data: [DONE]\n\n".toByteArray()); out.flush()
```

The `result` from `generate()` is already in scope for the streaming path (the outer `try` block
lines 351–364 calls `generate` and collects `result` at line 353 — verify during implementation
that `result` is accessible; if not, hoist it or pass `completionTokens` to a local `var`).

**Note:** the streaming path currently calls `generate()` without capturing its return value
(line 353 is a statement, not an assignment). The implementation must capture the result:
`val result = RelaisEngine.generate(...)` to get `completionTokens`.

### Files to touch

| File | Change |
|---|---|
| `RelaisEngine.kt` | Add `completionTokens: Int` to `RelaisResult`; set it in `generate()` |
| `RelaisHttpServer.kt` | Add `buildUsageObject` + `estimatePromptTokens` helpers; wire into non-streaming response and streaming final chunk; capture `result` in streaming path |

No changes to `RelaisModelCatalog.kt`, `RelaisConfig.kt`, AICore, or any UI file.

## TDD plan (pure JVM, `src/test`)

Test file: `Android/src/app/src/test/java/cc/grepon/relais/RelaisUsageBlockTest.kt`

All tests are pure: call `buildUsageObject` / `estimatePromptTokens` directly; no socket, no
Android Context, no Robolectric.

### Test 1 — completion tokens round-trip

```
Given: completionTokens = 42, promptText = "hello world"
When:  buildUsageObject("hello world", 42)
Then:
  - result["completion_tokens"] == 42
  - result["total_tokens"] == result["prompt_tokens"] + 42
  - result["usage_note"] == "prompt_tokens_estimated"
```

### Test 2 — prompt token estimator, basic cases

```
Given: "hello world"         → estimatePromptTokens == 2
Given: "one two three four"  → estimatePromptTokens == 4
Given: "  spaces  "          → estimatePromptTokens == 1   (trim + split collapses runs)
Given: ""                    → estimatePromptTokens == 0
Given: "   "                 → estimatePromptTokens == 0   (blank guard)
```

### Test 3 — total_tokens is sum of parts

```
Given: any promptText, completionTokens = N
When:  obj = buildUsageObject(promptText, N)
Then:  obj["total_tokens"] == obj["prompt_tokens"] + obj["completion_tokens"]
```

This is an invariant test — regardless of the heuristic formula, the sum must hold.

### Test 4 — zero completion tokens (AICore / unknown path)

```
Given: completionTokens = 0, promptText = "some prompt"
When:  buildUsageObject("some prompt", 0)
Then:
  - result["completion_tokens"] == 0
  - result["total_tokens"] == result["prompt_tokens"]  (prompt estimate only)
  - result["usage_note"] == "prompt_tokens_estimated"
```

RED → GREEN → IMPROVE workflow:
1. Write the four tests (all fail — `buildUsageObject` / `estimatePromptTokens` do not exist yet).
2. Add `completionTokens` to `RelaisResult` and the `generate()` construction site.
3. Implement `buildUsageObject` + `estimatePromptTokens` in `RelaisHttpServer.kt`.
4. Wire both call sites (non-streaming response, streaming final chunk).
5. All four new tests pass; `RelaisModelCatalogTest`, `RelaisEngineConfigTest`,
   `RelaisHuggingFaceTest` still pass.
6. `./gradlew testDebugUnitTest` green.

## Acceptance criteria

- Non-streaming `POST /v1/chat/completions` response includes a top-level `"usage"` key with
  `completion_tokens` (exact, engine-counted), `prompt_tokens` (estimated), `total_tokens` (sum),
  and `usage_note: "prompt_tokens_estimated"`.
- Streaming response: the final SSE chunk (`finish_reason: "stop"`) includes the same `"usage"` key;
  intermediate chunks do not.
- `completion_tokens` in the non-streaming response equals the number of `onMessage` callbacks
  fired during that inference (verifiable by counting SSE tokens in the streaming response for the
  same prompt).
- `total_tokens == prompt_tokens + completion_tokens` is always true.
- `./gradlew testDebugUnitTest` green (new tests + existing suite).
- `./gradlew assembleRelease` green.
- Independent reviewer APPROVE on the final diff.
- CI green before merge.

## Deferred / out of scope

- Exact prompt token count: requires a detached tokenizer or a future litertlm API. Track in a
  follow-on issue; document the gap in the PR description and in an inline comment in `generate()`.
- `stream_options: {include_usage: true}` request-level gating — always emit usage on the final
  chunk for now; add the guard only if a client complains about extra fields.
- On-device verification (curl a streaming response and count SSE token chunks vs reported
  `completion_tokens`) — document in PR; owner runs after merge on Pixel 9 (comet).

## Guardrails / PR

Branch `feat/relais-usage-block`; PR to `main`. Review the full diff before marking ready.

- **Do not touch AICore** (`RelaisAicore.kt`, `AICoreModelHelper.kt`). The AICore path returns
  `completionTokens = 0`; document this in a comment, do not attempt to count AICore tokens.
- **Respect the existing auth/CSP posture** — no new endpoints, no new auth logic.
- **No UI changes** — `DESIGN.md` is not consulted.
- **Keep `buildUsageObject` and `estimatePromptTokens` pure** (no `Context`, no Android types).
- **Never claim `prompt_tokens` is exact** — the `usage_note` field and inline comments must make
  the estimation explicit. Do not remove the note without a verified exact-count source.
- **Do not change `decodeTokensPerSec` semantics** in `RelaisResult` — `RelaisMetrics` reads it.
- Merge only after independent review APPROVE + CI green.
