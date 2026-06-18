# On-device RAG (`/v1/rag/*` + chat `rag` flag)

Retrieval-augmented generation over a local corpus, embedded with the on-device EmbeddingGemma model
(see [embeddings-api.md](embeddings-api.md)). Documents are chunked, embedded as **documents**, and
stored as 256-dim Matryoshka (MRL) vectors; retrieval embeds the query as a **query** and ranks the
corpus by cosine similarity.

Brute-force, in-memory ranking (no vector index — no sqlite-vec on Android), so the corpus has a
practical ceiling of ~10k chunks. All endpoints are bearer-authed. They need the embedder ready; until
it is, they return `503` (provisioning) or `501` (no token / no Google Play Services), exactly like
`/v1/embeddings`.

## Ingest — `POST /v1/rag/documents`

```json
{ "title": "optional", "text": "the document text to chunk, embed, and store" }
```
→ `200 { "object": "rag.document", "document_id": 7, "chunks": 12 }`. Text is chunked on
sentence/paragraph boundaries to ~400 tokens. The document + its chunks are written atomically.
`400` if `text` is missing, exceeds 1 MB, or has no embeddable content; `413` once the corpus is at
its enforced **10,000-chunk** cap (delete documents to make room).

## List — `GET /v1/rag/documents`

→ `200 { "object": "list", "data": [{ "document_id", "title", "created" }...],
"document_count": N, "chunk_count": M }`.

## Delete — `DELETE /v1/rag/documents`  (id in body)

```json
{ "document_id": 7 }
```
→ `200 { "object": "rag.document.deleted", "document_id": 7 }`. Removes the document and its chunks.

## Query — `POST /v1/rag/query`

```json
{ "query": "what is …?", "top_k": 4 }
```
→ `200 { "object": "list", "data": [{ "text", "score", "document_id", "chunk_index" }...] }`,
ranked by descending cosine. `top_k` defaults to 4, capped at 20. Returns chunks only — no generation.

## Retrieval in chat — `rag` flag on `/v1/chat/completions`

Add `"rag": true` (alias `"x_relais_rag"`, optional `"rag_top_k"`) to a chat request. The user's turn
is embedded as a query, the top-k chunks are retrieved, and they're prepended to the system prompt
before generation. Deterministic and **opt-in** — never auto-injected.

The caller's own system prompt stays first and authoritative; retrieved chunks are appended in a
fenced `<retrieved_context>` block explicitly framed as untrusted DATA, so a poisoned document can't
override the caller's guardrails via the system role. (Trust model: anyone with the bearer key can
ingest documents, and retrieved text influences generation — keep the corpus trusted.)

Best-effort: if the embedder isn't loaded yet, the chat is answered **without** retrieval that turn
(and a background provision is kicked, so later turns retrieve) — the flag never fails the chat. For
strict control, use `POST /v1/rag/query` and build the prompt yourself.
