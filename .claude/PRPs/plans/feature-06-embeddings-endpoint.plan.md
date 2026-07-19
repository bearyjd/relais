# Feature 06 — `POST /v1/embeddings` endpoint

> Branch: `feat/relais-embeddings`. No AICore. No on-device gate until an embedding-capable
> model is provisioned; that gate is documented as **deferred** and explicitly marked in the PR.

## Summary

Add an OpenAI-compatible `POST /v1/embeddings` endpoint to `RelaisHttpServer` so the node can
serve embedding vectors for RAG clients alongside its existing chat service.

**Critical dependency / blocker**: the LiteRT-LM library bundled in this project
(`com.google.ai.edge.litertlm`) exposes **no embedding API**. The full symbol set used in the
codebase is: `Engine`, `EngineConfig`, `Backend`, `Content`, `Contents`, `ConversationConfig`,
`SamplerConfig`, `Message`, `MessageCallback`, `ExperimentalApi`, `ExperimentalFlags`, `Tool`,
`ToolParam`, `ToolSet`, `ToolProvider`. There is no `Embedder`, `EmbeddingConfig`,
`EmbeddingResult`, or equivalent. Additionally, `ModelCapability` (in `data/Model.kt`) only
enumerates `LLM_THINKING` and `SPECULATIVE_DECODING` — no `EMBEDDINGS` capability exists.
No catalog model today has an embedding task type.

**The embedding-generation step is therefore a hard dependency that must be resolved before the
endpoint can return real vectors.** This PRP scopes the work to:

1. The complete HTTP API surface (route, request DTO, response DTO, auth, metrics, thermal shed).
2. A pluggable `EmbeddingProvider` seam that makes the generation step injectable, so the
   API layer and all surrounding logic can be unit-tested without a real embedding engine.
3. A `StubEmbeddingProvider` that returns a well-formed zero vector today (enables integration
   testing of the full HTTP stack with real clients).
4. A `LiteRtEmbeddingProvider` placeholder whose `generate()` throws
   `UnsupportedOperationException("LiteRT-LM embedding API not yet available")` — wired in
   production, replaced under test with the stub.
5. TDD-first: all pure-JVM tests written RED before implementation.

The on-device acceptance gate (real embedding vector returned for real input) is **deferred**
pending one of: (a) a future LiteRT-LM release that adds an embedding API, (b) a standalone
on-device TFLite sentence-encoder model loaded outside LiteRT-LM, or (c) a CPU-only embedding
library. The PR must document this explicitly.

---

## Grounding in existing code

### `RelaisHttpServer.kt` — routing pattern

The server is a hand-rolled HTTP/1.1 raw-socket server (no framework). Routes are matched in a
`when` block inside `handle()`:

```kotlin
when {
  method == "GET"  && path.startsWith("/health")              -> …
  method == "GET"  && path.startsWith("/metrics")             -> …
  method == "POST" && path.startsWith("/generate")            -> …
  method == "POST" && path.startsWith("/v1/chat/completions") -> handleOpenAi(…)
  else -> reply(404, …)
}
```

New route slots in as another `method == "POST" && path.startsWith("/v1/embeddings")` arm.
Auth, rate-limiting, body-size cap, and thermal shed are already applied uniformly above the
`when` block to all non-health POST endpoints — the embeddings route inherits all of these
for free.

`endpointLabel()` (used for metrics) must be extended with `"/v1/embeddings"`.

`reason()` already covers all needed HTTP status codes (200, 400, 401, 413, 429, 503).

### `RelaisEngine.kt` — inference model

`RelaisEngine` is a singleton object wrapping a single `com.google.ai.edge.litertlm.Engine`
instance (chat/generation only). There is no embedding call path. The provider seam is
intentionally separate so a future embedding engine (different `EngineConfig`, possibly
a different native library) does not couple into the generation path.

### `RelaisModelCatalog.kt` / `ModelAllowlist.kt` — no embedding models today

`isNodeRunnable()` filters to `taskTypes.contains(BuiltInTaskId.LLM_CHAT)` and
`runtimeType == RuntimeType.LITERT_LM`. No `LLM_EMBEDDING` task type exists in
`BuiltInTaskId`, `ModelCapability`, or the allowlist schema. The catalog section of this
feature therefore covers only documentation: a future embedding-capable model must have
`taskTypes: ["llm_embedding"]` and the catalog must add an `isEmbeddingRunnable()` predicate.

### `RelaisMetrics.kt` — observability

`recordRequest(endpoint, status)` is already wired for every route. Add
`"/v1/embeddings"` to the label set. No new counter types needed; token/throughput tracking
is N/A for embeddings (usage counts go in the response body, not the metrics system).

### Test harness

`RelaisModelCatalogTest` (in `Android/src/app/src/test/java/cc/grepon/relais/`) shows the
pattern: pure JUnit, Gson fixture strings, no Robolectric, no Android SDK. All new tests
follow this pattern. Run with `./gradlew testDebugUnitTest` from `Android/src`.

---

## Design

### Request DTO

```
POST /v1/embeddings
Authorization: Bearer <key>
Content-Type: application/json

{
  "model": "text-embedding-relais-v1",   // advisory; node uses its provisioned model
  "input": "Hello world"                  // string OR array of strings
}
```

`input` may be either a JSON string or a JSON array of strings (matching OpenAI spec).
Both forms are parsed into `List<String>` before dispatch.

### Response DTO

```json
{
  "object": "list",
  "data": [
    {
      "object": "embedding",
      "embedding": [0.12, -0.34, …],
      "index": 0
    }
  ],
  "model": "text-embedding-relais-v1",
  "usage": {
    "prompt_tokens": 3,
    "total_tokens": 3
  }
}
```

Shape matches the OpenAI embeddings response exactly so drop-in RAG clients work without
configuration changes.

### L2 normalization

Embedding vectors are L2-normalized before serialization to match peer behavior (OpenAI
embeddings are unit-norm; downstream cosine similarity then reduces to a dot product). The
normalization step is a pure mathematical function — ideal for unit testing.

### `EmbeddingProvider` seam

```kotlin
// cc/grepon/relais/RelaisEmbeddingProvider.kt  (new file, ~30 lines)
interface EmbeddingProvider {
    /** Returns a float vector for [text]. Vector length is provider-defined. Throws on error. */
    fun embed(text: String): FloatArray
}

/** Production stub: throws until a real embedding engine is wired. */
object LiteRtEmbeddingProvider : EmbeddingProvider {
    override fun embed(text: String): FloatArray =
        throw UnsupportedOperationException(
            "LiteRT-LM embedding API not yet available — " +
            "wire a real EmbeddingProvider when a supported model/library ships."
        )
}

/** Test/demo stub: returns a zero vector of fixed dimension (normalized to zero → all zeros). */
class StubEmbeddingProvider(private val dim: Int = 4) : EmbeddingProvider {
    override fun embed(text: String): FloatArray = FloatArray(dim)
}
```

`RelaisHttpServer` accepts an `embeddingProvider: EmbeddingProvider` constructor parameter
with a default of `LiteRtEmbeddingProvider`. Tests pass `StubEmbeddingProvider`.

### Normalization utility (pure function)

```kotlin
// cc/grepon/relais/RelaisEmbeddingProvider.kt (same file, ~10 lines)
internal fun l2Normalize(v: FloatArray): FloatArray {
    val norm = Math.sqrt(v.fold(0.0) { acc, x -> acc + x * x }.toDouble()).toFloat()
    if (norm == 0f) return v.copyOf()
    return FloatArray(v.size) { v[it] / norm }
}
```

A zero vector stays zero (no divide-by-zero).

### Bearer auth + rate limiting + thermal shed

The new route lives inside the existing `if (!(method == "GET" && path.startsWith("/health")))` auth
guard — no additional auth code needed. Thermal shed (`shedIfHot`) is called at the top of
the handler, before dispatch to the provider, matching the `/generate` and chat patterns.

### Token counting (usage field)

Approximate prompt tokens = `ceil(input_chars / 4)` per string, summed. This matches the
rough heuristic used by several OpenAI-compatible servers and is documented as approximate in
a code comment. No tokenizer dependency is introduced.

### Error handling

| Condition | HTTP status | Body |
|-----------|-------------|------|
| Missing / blank `input` | 400 | `{"error":"input required"}` |
| `input` is neither string nor array | 400 | `{"error":"input must be string or array"}` |
| Provider throws `UnsupportedOperationException` | 501 | `{"error":"embedding generation not available — no embedding model provisioned"}` |
| Provider throws any other exception | 500 | `{"error":"internal error"}` |
| Thermal shed | 503 | existing shed body |

`reason()` in `RelaisHttpServer` must be extended with `400 -> "Bad Request"` and
`501 -> "Not Implemented"`.

---

## Files to create / modify

| File | Action |
|------|--------|
| `cc/grepon/relais/RelaisEmbeddingProvider.kt` | **New** — `EmbeddingProvider` interface, `LiteRtEmbeddingProvider`, `StubEmbeddingProvider`, `l2Normalize` |
| `cc/grepon/relais/RelaisHttpServer.kt` | **Modify** — add constructor param, new route arm, `handleEmbeddings()`, extend `endpointLabel()` and `reason()` |
| `src/test/.../RelaisEmbeddingsTest.kt` | **New** — all TDD tests (see below) |

No changes to `RelaisEngine`, `RelaisModelCatalog`, `ModelAllowlist`, or any data DTO class.

---

## TDD plan (pure JVM, `src/test`, JUnit 4, no Robolectric)

All tests use `StubEmbeddingProvider` and drive the parsing/assembly/normalization logic
directly — no socket, no Android SDK, no `Context`.

### Test file: `RelaisEmbeddingsTest.kt`

#### E1 — Request parsing: single string input

```kotlin
@Test
fun `single string input parsed as one-element list`() {
    val body = JSONObject().put("input", "hello").put("model", "m")
    val inputs = parseEmbeddingInputs(body)    // pure fun to extract
    assertEquals(1, inputs.size)
    assertEquals("hello", inputs[0])
}
```

RED before `parseEmbeddingInputs` exists. GREEN after extraction.

#### E2 — Request parsing: array input

```kotlin
@Test
fun `array input parsed as multi-element list`() {
    val body = JSONObject().put("input", JSONArray().put("foo").put("bar")).put("model", "m")
    val inputs = parseEmbeddingInputs(body)
    assertEquals(listOf("foo", "bar"), inputs)
}
```

#### E3 — Request parsing: missing input throws / returns empty sentinel

```kotlin
@Test
fun `missing input field returns empty list (caller maps to 400)`() {
    val inputs = parseEmbeddingInputs(JSONObject().put("model", "m"))
    assertTrue(inputs.isEmpty())
}
```

#### E4 — Response shape: single embedding

```kotlin
@Test
fun `response envelope matches OpenAI shape for single input`() {
    val provider = StubEmbeddingProvider(dim = 4)
    val resp = buildEmbeddingResponse(
        inputs = listOf("hello"), model = "test-model", provider = provider
    )
    assertEquals("list", resp.getString("object"))
    val data = resp.getJSONArray("data")
    assertEquals(1, data.length())
    val item = data.getJSONObject(0)
    assertEquals("embedding", item.getString("object"))
    assertEquals(0, item.getInt("index"))
    assertEquals(4, item.getJSONArray("embedding").length())
    assertTrue(resp.has("usage"))
    assertEquals("test-model", resp.getString("model"))
}
```

#### E5 — Response shape: batch (two inputs)

```kotlin
@Test
fun `response has correct indices for batch input`() {
    val resp = buildEmbeddingResponse(
        inputs = listOf("a", "b"), model = "m", provider = StubEmbeddingProvider(2)
    )
    val data = resp.getJSONArray("data")
    assertEquals(2, data.length())
    assertEquals(0, data.getJSONObject(0).getInt("index"))
    assertEquals(1, data.getJSONObject(1).getInt("index"))
}
```

#### E6 — L2 normalization: unit vector preserved

```kotlin
@Test
fun `l2Normalize of unit vector is unchanged`() {
    val v = floatArrayOf(1f, 0f, 0f)
    val n = l2Normalize(v)
    assertEquals(1f, n[0], 1e-6f)
    assertEquals(0f, n[1], 1e-6f)
}
```

#### E7 — L2 normalization: non-unit vector normalized

```kotlin
@Test
fun `l2Normalize produces unit-norm vector`() {
    val v = floatArrayOf(3f, 4f)
    val n = l2Normalize(v)
    val norm = Math.sqrt((n[0]*n[0] + n[1]*n[1]).toDouble())
    assertEquals(1.0, norm, 1e-6)
}
```

#### E8 — L2 normalization: zero vector stays zero (no divide-by-zero)

```kotlin
@Test
fun `l2Normalize of zero vector returns zero vector`() {
    val n = l2Normalize(floatArrayOf(0f, 0f, 0f))
    n.forEach { assertEquals(0f, it, 0f) }
}
```

#### E9 — Usage token count

```kotlin
@Test
fun `usage prompt_tokens is sum of per-input approx token counts`() {
    // "hello" = 5 chars → ceil(5/4) = 2; "world!" = 6 chars → ceil(6/4) = 2; total = 4
    val resp = buildEmbeddingResponse(
        inputs = listOf("hello", "world!"), model = "m", provider = StubEmbeddingProvider(2)
    )
    val usage = resp.getJSONObject("usage")
    assertEquals(4, usage.getInt("prompt_tokens"))
    assertEquals(4, usage.getInt("total_tokens"))
}
```

#### E10 — Provider throws `UnsupportedOperationException` (production stub)

```kotlin
@Test
fun `UnsupportedOperationException from provider is surfaced as distinct result`() {
    val provider = object : EmbeddingProvider {
        override fun embed(text: String): FloatArray =
            throw UnsupportedOperationException("not available")
    }
    val ex = assertThrows(UnsupportedOperationException::class.java) {
        buildEmbeddingResponse(listOf("x"), "m", provider)
    }
    assertTrue(ex.message!!.contains("not available"))
}
```

(The HTTP layer maps this to 501; the pure function simply rethrows.)

**Test execution order**: RED (all 10 fail before any production code) → GREEN (implement
`parseEmbeddingInputs`, `buildEmbeddingResponse`, `l2Normalize`, route arm) → refactor.

---

## Implementation notes for the executor

- Extract `parseEmbeddingInputs(body: JSONObject): List<String>` and
  `buildEmbeddingResponse(inputs, model, provider): JSONObject` as internal top-level
  functions (not methods on `RelaisHttpServer`) so tests can call them without instantiating
  the server (which requires `Context`).
- `l2Normalize` is `internal fun` in `RelaisEmbeddingProvider.kt`, accessible from both
  the server and the test.
- The `handleEmbeddings` private method on `RelaisHttpServer` orchestrates: parse → validate
  → shed check → loop over inputs (call provider, normalize) → build response → reply.
- Vectors are serialized as `JSONArray` of `Double` (cast from `Float`). OpenAI uses double
  precision in the wire format.
- Constructor signature change: `RelaisHttpServer(context, port, tls, bindAddr,
  embeddingProvider = LiteRtEmbeddingProvider)`. Existing call sites in `RelaisNodeService`
  use named/default parameters — no change needed there.

---

## Acceptance criteria

1. `./gradlew testDebugUnitTest` from `Android/src` passes with all 10 new tests green.
2. `./gradlew assembleRelease` from `Android/src` passes (no compile errors).
3. CI green on the PR branch.
4. A `curl -sk -H "Authorization: Bearer <key>" -d '{"input":"hello","model":"m"}' \
   https://<node>/v1/embeddings` returns a well-formed JSON response with `object: "list"`,
   a `data` array, and `usage` — even if the embedding values are all zeros (stub path).
5. The 501 path is exercised: a client receives `{"error":"embedding generation not available …"}`
   when `LiteRtEmbeddingProvider` is wired (production build, no real embedding model).
6. Independent APPROVE review of the final diff before merge.

## Deferred on-device gate (document in PR, do NOT fake)

Real embedding vector (non-zero, semantically meaningful, reproducible) returned for a fixed
test input. Blocked on one of:
- A future `litertlm` release adding an `Embedder` / `EmbeddingConfig` API.
- A standalone TFLite sentence-encoder `.tflite` model loaded via `org.tensorflow:tensorflow-lite`
  outside the LiteRT-LM path (viable today; see risk section).
- A CPU-only JVM embedding library (e.g. `djl`).

Owner runs this gate on-device once unblocked. The PR explicitly lists the gate as deferred.

---

## Risk register

| ID | Risk | Likelihood | Impact | Mitigation |
|----|------|-----------|--------|------------|
| R1 | LiteRT-LM never adds an embedding API | Medium | High | Use standalone TFLite model path (see R3). Track `google-ai-edge/LiteRT` release notes. |
| R2 | No embedding-capable `.litertlm` model in the catalog | High (certain today) | Medium | Stub path allows API surface to ship; catalog updated separately when model available. |
| R3 | TFLite standalone path adds APK size + another native SO | Low–Medium | Medium | Evaluate at dependency-addition time; keep as an option, not a commitment in this PRP. |
| R4 | Batch embedding of many/long strings causes OOM or timeout | Low | Medium | Bound batch size (e.g. max 16 inputs, max 8 KB per input) in `handleEmbeddings`; return 400 if exceeded. Add to acceptance criteria when provider is real. |
| R5 | Float→Double casting precision loss in JSON serialization | Very Low | Low | Acceptable; OpenAI also serializes float32 as double in JSON. |

---

## Catalog dependency: embedding-capable model

When the embedding generation dependency is resolved, an allowlist entry must add:

```json
{
  "taskTypes": ["llm_embedding"],
  "runtimeType": "litert_lm",
  "modelFile": "some-encoder.litertlm",
  ...
}
```

`RelaisModelCatalog` needs a new `isEmbeddingRunnable(m: AllowedModel)` predicate (separate
from `isNodeRunnable`) and `BuiltInTaskId` needs an `LLM_EMBEDDING` constant. This is a
follow-on PR, not part of this feature. No catalog code changes land in this branch.

---

## Guardrails / PR

- Branch `feat/relais-embeddings`; PR target `main`.
- No AICore path. No changes to `RelaisEngine`, `RelaisModelCatalog`, `ModelAllowlist`, or any
  Gson-parsed data class.
- Keep `LiteRtEmbeddingProvider` as a clearly-named production stub (not silently returning
  zeros) so operators know the endpoint is wired but not yet functional.
- Review diff before merge; merge on green CI + APPROVE.
- PR description must explicitly state: "On-device embedding vector gate is deferred pending a
  LiteRT-LM embedding API or a standalone encoder model."
