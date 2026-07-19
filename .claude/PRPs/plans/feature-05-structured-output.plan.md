# Feature-05 — Structured Output (`response_format`) on `/v1/chat/completions`

> Branch: `feat/relais-structured-output`. Depends on **feature-03** (multi-turn conversation
> state — the retry loop needs a stateful conversation handle). No AICore involvement.

## Summary

Add support for the OpenAI `response_format` field on `POST /v1/chat/completions`.  Callers
can request `{"type":"json_object"}` (any valid JSON) or
`{"type":"json_schema","json_schema":{"name":"…","schema":{…}}}` (JSON conforming to a
caller-supplied JSON Schema).  When the field is absent or `{"type":"text"}`, behaviour is
unchanged.

**Constrained-decoding verdict — FALLBACK REQUIRED.**  `com.google.ai.edge.litertlm`
exposes `ExperimentalFlags.enableConversationConstrainedDecoding`, but it is a **global
process-level boolean**, not a per-request grammar parameter passed through
`ConversationConfig` (which accepts only `samplerConfig`, `systemInstruction`, `tools`, and
`initialMessages`).  There is no JSON-schema grammar hook on `createConversation` or
anywhere in the `Engine` / `ConversationConfig` API reachable from the Relais HTTP path.
True token-level grammar constraints are therefore **not available** to this feature.
The implementation uses the mandatory fallback: inject the schema contract into the prompt
as a system instruction addition, then validate the model's output and retry up to
`MAX_RETRIES` times before returning a 200 with the best candidate or a 422 when all
retries are exhausted.

## Design

### New file: `RelaisStructuredOutput.kt`
`cc.grepon.relais` package.  Contains all structured-output logic as pure functions (no
`Context`, no `Engine` reference) so the entire file is testable under plain JUnit in
`src/test`.

```
// Conceptual shape — implementer fills in bodies
object RelaisStructuredOutput {

    /** Parsed, validated representation of the `response_format` field. */
    sealed interface ResponseFormat {
        data object Text : ResponseFormat
        data object JsonObject : ResponseFormat
        data class JsonSchema(
            val name: String,
            val schema: JSONObject,          // raw caller-supplied schema
            val strict: Boolean,             // mirrors OpenAI's `strict` field (advisory only)
        ) : ResponseFormat
    }

    /**
     * Parses `body.opt("response_format")`.  Returns [ResponseFormat.Text] for absent/null/text.
     * Returns [ResponseFormat.JsonObject] for `{"type":"json_object"}`.
     * Returns [ResponseFormat.JsonSchema] for `{"type":"json_schema","json_schema":{…}}`.
     * Returns null (caller should 400) for an unrecognised non-null type.
     */
    fun parseResponseFormat(body: JSONObject): ResponseFormat?

    /**
     * Returns true iff [candidate] is valid JSON (for [ResponseFormat.JsonObject]) or
     * valid JSON that satisfies [schema] (for [ResponseFormat.JsonSchema]).
     * Pure: no I/O, no side effects.
     *
     * JSON Schema validation scope: `type`, `required`, `properties` (with nested `type`),
     * `additionalProperties:false`.  This is the minimal subset needed to be useful without
     * pulling in a full schema-validator library that hasn't been vetted for on-device use.
     * Extend incrementally as real callers surface gaps.
     */
    fun isValidOutput(candidate: String, format: ResponseFormat): Boolean

    /**
     * Attempts to repair [candidate] by extracting the first top-level JSON object/array
     * from surrounding prose (common model behaviour: "Sure! Here you go: {…}").
     * Returns the trimmed substring when extraction succeeds, or null when the text is
     * structurally unsalvageable.
     */
    fun repairOutput(candidate: String): String?

    /**
     * Decides whether to retry.  Returns true iff [attempts] < [maxRetries] and
     * the repaired candidate (if any) is still invalid.
     * Pure decision function — no I/O.
     */
    fun shouldRetry(attempts: Int, maxRetries: Int, repaired: String?, format: ResponseFormat): Boolean
}
```

#### JSON Schema validation scope (v1)
Implement a minimal validator that covers the subset callers actually need:
- Top-level `type` (`"object"`, `"array"`, `"string"`, `"number"`, `"boolean"`, `"null"`)
- `required` (array of strings — all must be present in the JSON object)
- `properties` with per-property `type` (one level deep; recursive nesting is out of scope v1)
- `additionalProperties: false` (reject keys not listed in `properties`)

Do **not** pull in `org.json.JSONObject.similar()` as a schema validator — it only checks
structural equality, not schema conformance.  A hand-rolled 80-line validator is correct,
auditable, and adds zero dependencies.

### Changes to `RelaisHttpServer.kt`

#### `parseOpenAiRequest` (lines 368–393)
Extend the return type or add a second return channel to surface the parsed `ResponseFormat`.
Concretely: extract a `data class OpenAiParsed(val request: RelaisRequest, val format: ResponseFormat)` and have `parseOpenAiRequest` return it.

#### `handleOpenAi` (lines 310–365)
After parsing, branch on `format`:
- `ResponseFormat.Text` → existing path, no change.
- `ResponseFormat.JsonObject` / `ResponseFormat.JsonSchema` → structured path.

**Structured path (non-streaming only in v1):**
1. Build an augmented `RelaisRequest` whose `text` prepends a system-level instruction:
   `"Respond with valid JSON only. No prose, no markdown fences."` (for `json_object`),
   or the schema's `description` + a serialised schema excerpt (for `json_schema`).
   Append this as a system-instruction prefix — feature-03's multi-turn handle will expose a
   `systemInstruction` field to thread this through `ConversationConfig`.
2. Call `RelaisEngine.generate(…)`.
3. Validate via `RelaisStructuredOutput.isValidOutput`.
4. On failure: attempt `repairOutput`; if the repaired string is valid, use it.
5. If still invalid and `shouldRetry` → increment attempt counter, go to step 2 (same
   conversation, same engine call — the retry appends a correction nudge to the prompt).
6. After `MAX_RETRIES` (= 2) exhausted: return HTTP 422 with
   `{"error":"model failed to produce valid JSON after N retries","last_output":"…"}`.

**Streaming (`stream:true`) + structured output:** return 400
`{"error":"stream and response_format cannot be combined"}` for v1.  This avoids the
complexity of buffering the full stream to validate before emitting tokens.  Document this
in the response body so callers know it is intentional.

#### `finish_reason` in the success response
When structured output succeeds, set `finish_reason` to `"stop"` (normal).  When the output
was repaired (not originally valid), add `"structured_output_repaired":true` to the choice
object as an advisory field so clients can detect the degraded path.

### `MAX_RETRIES` constant
`const val MAX_STRUCTURED_OUTPUT_RETRIES = 2` at file top of `RelaisHttpServer.kt`.  Two
retries means at most three inference calls per request.  Operators should be aware that
structured output can triple latency; this is documented in the endpoint's KDoc.

### `RelaisRequest` / `RelaisEngine.generate`
No changes to `RelaisEngine.generate` itself — it is agnostic to structured output.  The
system-instruction injection happens at the HTTP layer by modifying the `text` field of
`RelaisRequest` before the engine call.  This is the correct layering: the engine knows
nothing about OpenAI semantics.

---

## TDD Plan (`src/test/java/cc/grepon/relais/RelaisStructuredOutputTest.kt`)

All tests are pure JUnit 4 (`@Test`, `assertEquals`, `assertTrue`/`assertFalse`), zero
Android dependencies, zero Robolectric.  Mirror the fixture style of `RelaisModelCatalogTest`
and `RelaisEngineConfigTest`.

### Block 1 — `parseResponseFormat` (RED first)

```
T1  absent field → ResponseFormat.Text
T2  {"type":"text"} → ResponseFormat.Text
T3  {"type":"json_object"} → ResponseFormat.JsonObject
T4  {"type":"json_schema","json_schema":{"name":"addr","schema":{"type":"object","required":["street"]}}}
      → ResponseFormat.JsonSchema(name="addr", strict=false, schema contains "type":"object")
T5  {"type":"json_schema","json_schema":{"name":"x","strict":true,"schema":{}}}
      → ResponseFormat.JsonSchema(strict=true)
T6  {"type":"unknown_future_type"} → null  (caller should 400)
T7  response_format is a non-object (e.g. "json_object" as a bare string) → null
```

### Block 2 — `isValidOutput` (RED first)

```
T8   "{}" is valid JsonObject
T9   "not json" is invalid JsonObject
T10  "{\"a\":1}" is valid JsonObject
T11  "   " (blank) is invalid JsonObject
T12  JsonSchema with required=["street"]: "{\"street\":\"Main\"}" is valid
T13  JsonSchema with required=["street"]: "{\"zip\":\"90210\"}" is invalid (missing required)
T14  JsonSchema with additionalProperties:false, properties:{name:{}}: "{\"name\":\"x\",\"extra\":1}" is invalid
T15  JsonSchema with type:object: "[1,2,3]" is invalid (array not object)
T16  JsonSchema with type:array: "[1,2,3]" is valid
T17  Nested property type check: schema requires properties.age.type=number;
      "{\"age\":\"old\"}" is invalid, "{\"age\":42}" is valid
T18  ResponseFormat.Text: any string (even "not json") → isValidOutput returns true (no constraint)
```

### Block 3 — `repairOutput` (RED first)

```
T19  "Sure! Here you go: {\"a\":1}" → repairs to "{\"a\":1}"
T20  "```json\n{\"b\":2}\n```" → repairs to "{\"b\":2}"
T21  "{\"c\":3}" → returns as-is (already a valid JSON object boundary)
T22  "no json here at all" → returns null
T23  "prefix [1,2] suffix" → repairs to "[1,2]"
```

### Block 4 — `shouldRetry` decision (RED first)

```
T24  attempts=0, maxRetries=2, repaired=null, format=JsonObject → true (retry)
T25  attempts=2, maxRetries=2, repaired=null, format=JsonObject → false (exhausted)
T26  attempts=1, maxRetries=2, repaired="{}", format=JsonObject → false (repaired is valid, no retry)
T27  attempts=0, maxRetries=0, repaired=null, format=JsonObject → false (zero retries configured)
```

### Block 5 — `parseOpenAiRequest` extension (RED first, in existing or new test class)

```
T28  body with response_format absent → OpenAiParsed.format = ResponseFormat.Text
T29  body with response_format.type="json_schema" + json_schema object → format is JsonSchema
T30  body with response_format.type="json_object" → format is JsonObject
```

GREEN: implement `RelaisStructuredOutput` and the `parseOpenAiRequest` change so all tests
pass.  Run `./gradlew testDebugUnitTest` from `Android/src`.

---

## Acceptance Criteria

1. `./gradlew testDebugUnitTest` (working dir `Android/src`) green with all new tests in
   `RelaisStructuredOutputTest`; no regressions in existing test classes.
2. `./gradlew assembleRelease` green (no compile errors in `RelaisHttpServer.kt`,
   `RelaisStructuredOutput.kt`).
3. Independent code review APPROVE on the final diff.
4. CI green on `feat/relais-structured-output`.
5. PR body explicitly states: "LiteRT-LM constrained decoding not used — see Design section."

## Deferred on-device gate (document in PR, do NOT fake)

With the Pixel 9 live node running:
- Send a `json_schema` request for a structured address schema and confirm the response body
  is parseable JSON containing the required fields.
- Send a deliberately hard schema to a small model and observe the 422 path in logcat.
- Confirm that `stream:true` + `response_format` returns 400 (not a crash).

This gate is owned by the operator and run after CI merges; do not block the PR on it.

---

## Risk Register

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Model ignores prompt injection and returns prose | High | High | `repairOutput` extraction + retry loop; 422 on exhaustion |
| Retry triples latency (3× inference) | Medium | Medium | Cap `MAX_STRUCTURED_OUTPUT_RETRIES=2`; document in endpoint KDoc; streaming+format=400 avoids unbounded SSE hangs |
| Minimal JSON Schema validator misses real-world schemas | Medium | Low | v1 scope is explicit; extend incrementally; 422 is a safe degradation |
| `ExperimentalFlags.enableConversationConstrainedDecoding` race | Low | Medium | Flag is per-process and already set/reset around `createConversation` in the UI path; RelaisEngine does NOT set it (confirmed by code inspection) — no race on the Relais HTTP path |
| feature-03 not yet merged when implementing | High | Medium | Design the system-instruction injection to work with the current single-turn `RelaisRequest.text` prefix as a fallback until feature-03 lands |

---

## Guardrails / PR

- Branch: `feat/relais-structured-output` off `main`.
- PR to `main` after feature-03 merges (or off feature-03 branch with explicit rebase plan).
- No AICore involvement — `RelaisAicore` is untouched.
- `RelaisEngine.generate` signature is unchanged.
- `MAX_STRUCTURED_OUTPUT_RETRIES = 2` is a named constant, not a magic number.
- Do not enable `ExperimentalFlags.enableConversationConstrainedDecoding` in the Relais
  HTTP path — it is a global flag that would interfere with concurrent UI conversations and
  does not expose a JSON schema API anyway.
- Keep `RelaisStructuredOutput.kt` under 300 lines; split if it grows.
- Review the diff; merge on green CI; do not merge without independent APPROVE.
