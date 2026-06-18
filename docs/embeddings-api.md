# `/v1/embeddings` — on-device EmbeddingGemma

OpenAI-compatible embeddings over the LAN, served by the on-device EmbeddingGemma-300M model. Native
**768-dim**, L2-normalized vectors.

```
POST /v1/embeddings          Authorization: Bearer <node key>
```

## Request

| field            | type                | notes                                                             |
| ---------------- | ------------------- | ----------------------------------------------------------------- |
| `input`          | string \| string[]  | required; non-blank. Batch capped (`EMBEDDINGS_MAX_INPUTS`) and per-item length capped. |
| `model`          | string              | optional; echoed back in the response.                            |
| `embedding_task` | `"query"` \| `"document"` | optional; **defaults to `document`**. See below. (`x_relais_embedding_task` is an alias.) |

## Retrieval asymmetry — read this before using for RAG

EmbeddingGemma is trained with **different instruction prefixes for queries vs. documents**, and
mixing them collapses retrieval quality. Pass `embedding_task` to pick the side:

- `embedding_task: "document"` — **the default.** Use when embedding passages/content to store.
- `embedding_task: "query"` — use when embedding a **search query** to match against stored documents.

A standard OpenAI embeddings client that does **not** send `embedding_task` gets the `document`
prefix for everything. That is correct for indexing content, but for retrieval **queries you must set
`embedding_task: "query"`** or the query and its matching documents are embedded asymmetrically-wrong
and ranking degrades. An unknown value → `400`.

## Response

```json
{
  "object": "list",
  "model": "…",
  "data": [{ "object": "embedding", "index": 0, "embedding": [/* 768 floats */] }],
  "usage": { "prompt_tokens": 42, "total_tokens": 42 }
}
```

Vectors are L2-normalized, so cosine similarity is just a dot product. Matryoshka (MRL) truncation to
a smaller `dimensions` is not yet exposed (lands with RAG); truncate client-side if needed.

## Provisioning & status codes

The model is **license-gated** and downloaded on demand (~180 MB, once), not bundled:

- The operator must set a HuggingFace token (`RelaisConfig.setHfToken`) that has accepted the
  `litert-community/embeddinggemma-300m` license.
- The model + tokenizer run via the **bundled LiteRT runtime** (`org.tensorflow.lite.Interpreter`,
  CPU/XNNPACK) shipped in the APK — **no Play Services in the dependency graph** (verified at build +
  dependency-resolution time). It is therefore intended to run on **de-Googled devices**, though that
  has not yet been hardware-verified on one (no GrapheneOS-without-GMS device on hand; validated on a
  stock Pixel 10). (The GENERIC seq512 model runs on CPU; the SoC-NPU-compiled `.tflite` variants would
  need a vendor delegate the bundled runtime doesn't carry, so they're not used.)

| status | meaning |
| ------ | ------- |
| `200`  | embeddings returned. |
| `400`  | invalid `input` or an unknown `embedding_task`. |
| `503` + `Retry-After` | model is downloading/loading in the background — retry shortly (first request after a fresh install or restart). |
| `501`  | genuinely unavailable: no HF token set (the model is license-gated and can't be fetched). |

The download is triggered by the first `/v1/embeddings` request (not merely by having a token), runs
off the request thread, and is reused across restarts.
