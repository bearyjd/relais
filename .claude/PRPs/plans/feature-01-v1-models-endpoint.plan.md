# Feature 01 — GET /v1/models endpoint

> Branch: `feat/relais-v1-models`. No upstream GitHub issue yet — open one when the branch is
> created and link it here.

## Summary

Clients that use standard OpenAI SDK auto-configuration (e.g. `openai.models.list()`) expect a
`GET /v1/models` endpoint. Today Relais 404s on that path, which breaks auto-discovery and requires
callers to hard-code the model name. This PR adds a minimal, spec-conformant `/v1/models` response
sourced from the same curated catalog the node already uses — no new data, no new state.

## Context (VERIFIED code refs)

All line numbers reference the `main` branch at the time this plan was written.

### HTTP routing — `RelaisHttpServer.kt`

- **Route dispatch** (`when` block, lines 241–285): four named branches (`/health`, `/metrics`,
  `/generate`, `/v1/chat/completions`) with a catch-all `else -> 404`. `/v1/models` falls through to
  the 404 today. The new `GET /v1/models` branch inserts here.
- **Auth gate** (lines 225–239): every path except `GET /health` requires `Authorization: Bearer
  <key>` (constant-time compare via `MessageDigest.isEqual`). `/v1/models` must also require auth
  (it reveals the installed model list to callers).
- **Rate limiter** (lines 231–233): per-IP 30 req/60s; the new route is inside the gate so it is
  automatically rate-limited.
- **`endpointLabel`** (lines 400–407): normalizes paths for metrics labels. Must add a
  `"/v1/models"` case so metrics are not bucketed as `"other"`.
- **`respond`** helper (line 450): writes `application/json` with correct `Content-Length +
  Connection: close`. The new handler reuses this directly.

### Catalog source — `RelaisModelCatalog.kt`

- **`curatedModels()`** (lines 49–58): blocking network call that fetches and filters the allowlist,
  returning `List<RelaisModelRef>`. Returns `emptyList()` on offline/fetch failure (never throws).
- **`curatedModelsFrom(allowlist)`** (line 61): the pure, network-free filter+map that tests
  exercise. Returns only enabled, LiteRT-LM, `llm_chat` models as `RelaisModelRef`s.
- **`RelaisModelRef.modelId`** (`data/RelaisModelRef.kt`, line 43): the HuggingFace repo id (e.g.
  `litert-community/gemma-4-E4B-it-litert-lm`). This is the value the `model` field in
  `/v1/chat/completions` requests already resolves against (`DEFAULT_MODEL` constant,
  `RelaisHttpServer.kt` line 54). IDs in the `/v1/models` response must use the same field so
  clients can round-trip.
- **`RelaisModelRef.SOURCE_ALLOWLIST` / `SOURCE_HUGGINGFACE`** (lines 53–54): provenance tag used
  as `owned_by` value — makes the response machine-readable without leaking internal paths.

### Currently active model — `RelaisConfig.kt`

- **`modelId(context)`** (lines 133–134): returns the operator-selected model id, defaulting to
  `DEFAULT_MODEL_ID = "litert-community/gemma-4-E4B-it-litert-lm"` (line 49). The endpoint should
  include the currently-running model even when the catalog is temporarily offline; this field is
  always readable.

### Token count exposed by the engine — `RelaisEngine.kt`

Not relevant to this feature. The engine does not need to be touched.

## Design

### Response shape

`GET /v1/models` returns `200 application/json`:

```json
{
  "object": "list",
  "data": [
    {
      "id": "litert-community/gemma-4-E4B-it-litert-lm",
      "object": "model",
      "owned_by": "allowlist"
    }
  ]
}
```

- `id` = `RelaisModelRef.modelId` — exactly what `/v1/chat/completions` accepts in the `"model"`
  field. IDs are stable across responses as long as the allowlist does not change.
- `object` = `"model"` per the OpenAI spec (the list wrapper has `"object": "list"`).
- `owned_by` = `RelaisModelRef.source` (`"allowlist"` or `"huggingface"`). This is a non-secret
  field from `RelaisModelRef`; it does not leak filesystem paths or the API key.
- No `created`, `permission`, or `root` fields — clients tolerant of the spec treat these as
  optional, and omitting them keeps the response minimal.

### Catalog strategy

The endpoint calls `RelaisModelCatalog.curatedModels()` on the request worker thread (already off
main). On offline failure that returns `emptyList()`; the response then contains only the
currently-provisioned model id from `RelaisConfig.modelId(context)`, synthesized as a fallback
entry. This ensures the response is always non-empty (a running node always has one model).

**Fallback entry shape** (used only when catalog is offline):
```json
{ "id": "<RelaisConfig.modelId(context)>", "object": "model", "owned_by": "node" }
```

### Pure mapping function

Extract the list-shaping logic into an `internal` pure function so it can be unit-tested without
a socket:

```kotlin
// in RelaisHttpServer.kt (or a new RelaisModelsResponse.kt — keep it ≤ 50 lines)
internal fun buildModelsResponse(
    refs: List<RelaisModelRef>,
    fallbackId: String,
): JSONObject {
    val data = JSONArray()
    val ids = refs.map { it.modelId }.toSet()
    if (ids.isEmpty()) {
        // Offline fallback: at least expose the currently-running model.
        data.put(JSONObject().put("id", fallbackId).put("object", "model").put("owned_by", "node"))
    } else {
        refs.forEach { ref ->
            data.put(JSONObject().put("id", ref.modelId).put("object", "model").put("owned_by", ref.source))
        }
    }
    return JSONObject().put("object", "list").put("data", data)
}
```

The HTTP handler becomes:

```kotlin
method == "GET" && path.startsWith("/v1/models") -> {
    val refs = RelaisModelCatalog.curatedModels()
    val fallback = RelaisConfig.modelId(context)
    reply(200, buildModelsResponse(refs, fallback))
}
```

### Auth and metrics wiring

- No changes to the auth gate logic: the route is inside the `if (!(method == "GET" && path ==
  "/health"))` block, so it inherits Bearer auth + rate limiting automatically.
- Add `"/v1/models"` to `endpointLabel()` (one line) so the `/metrics` scrape correctly labels this
  route's request counts.
- Add `402` is not needed — `reason()` already handles 200/401/404/429/431/500/503.

### Files to touch

| File | Change |
|---|---|
| `RelaisHttpServer.kt` | Add route branch + `endpointLabel` case + `buildModelsResponse` helper |
| `RelaisModelCatalog.kt` | No change — `curatedModelsFrom` already has the pure seam |
| `RelaisConfig.kt` | No change — `modelId` already readable |

No UI changes. No `DESIGN.md` impact. No AICore touch.

## TDD plan (pure JVM, `src/test`)

Test file: `Android/src/app/src/test/java/cc/grepon/relais/RelaisModelsResponseTest.kt`

Mirror `RelaisModelCatalogTest`'s style: Gson-parse a fixture, call the pure function, assert on
the resulting `JSONObject`.

### Test 1 — curated list maps correctly

```
Given: two RelaisModelRefs with distinct sources (SOURCE_ALLOWLIST + SOURCE_HUGGINGFACE)
When:  buildModelsResponse(refs, fallback="ignored")
Then:
  - response["object"] == "list"
  - response["data"].length() == 2
  - data[0]["id"] == refs[0].modelId
  - data[0]["object"] == "model"
  - data[0]["owned_by"] == "allowlist"
  - data[1]["owned_by"] == "huggingface"
  - fallbackId does NOT appear in the response (real list takes precedence)
```

### Test 2 — offline fallback (empty refs)

```
Given: emptyList<RelaisModelRef>(), fallbackId = "litert-community/gemma-4-E4B-it-litert-lm"
When:  buildModelsResponse(emptyList(), fallbackId)
Then:
  - response["object"] == "list"
  - response["data"].length() == 1
  - data[0]["id"] == "litert-community/gemma-4-E4B-it-litert-lm"
  - data[0]["owned_by"] == "node"
```

### Test 3 — ID round-trip

```
Given: a ref whose modelId == RelaisConfig.DEFAULT_MODEL_ID
When:  buildModelsResponse(listOf(ref), fallback)
Then:  data[0]["id"] == RelaisConfig.DEFAULT_MODEL_ID
```

This guards the ID-alignment requirement: the value a client receives from `/v1/models` must be
accepted verbatim in the `"model"` field of a `/v1/chat/completions` request.

RED → GREEN → IMPROVE workflow:
1. Write the three tests (all fail — `buildModelsResponse` does not exist yet).
2. Implement `buildModelsResponse` in `RelaisHttpServer.kt`.
3. Add the route branch + `endpointLabel` case.
4. All three tests pass; existing `RelaisModelCatalogTest` still passes.
5. `./gradlew testDebugUnitTest` green.

## Acceptance criteria

- `GET /v1/models` with a valid Bearer token returns `200 application/json` with
  `{"object":"list","data":[…]}` where every `data[i].id` is accepted by
  `POST /v1/chat/completions` without a model-not-found error.
- `GET /v1/models` without a token returns `401`.
- `GET /v1/models` is labeled `"/v1/models"` (not `"other"`) in `/metrics` output.
- Response is non-empty even when the catalog fetch fails (falls back to the running model id).
- `./gradlew testDebugUnitTest` green (new tests + existing suite).
- `./gradlew assembleRelease` green.
- Independent reviewer APPROVE on the final diff.
- CI green before merge.

## Deferred / out of scope

- Pagination, `limit`/`cursor` query params — not needed for a single-node, single-model server.
- `created` / `permission` / `root` fields — omitted; callers that need them can request them in a
  follow-on.
- On-device verification (confirm a real OpenAI SDK client auto-discovers the model) — document in
  PR; owner runs after merge on Pixel 9 (comet).

## Guardrails / PR

Branch `feat/relais-v1-models`; PR to `main`. Review the full diff before marking ready.

- **Do not touch AICore** (`RelaisAicore.kt`, `AICoreModelHelper.kt`).
- **Respect the existing auth/rate-limit posture** — `/v1/models` must NOT be open like `/health`.
- **No UI changes** — this is a pure API addition; `DESIGN.md` is not consulted.
- **Keep `buildModelsResponse` pure** (no `Context` dependency) so the unit test runs on the JVM
  without Robolectric.
- **Do not leak** filesystem paths, the API key, the HF token, or IP addresses into the response
  body or metrics labels.
- Merge only after independent review APPROVE + CI green.
