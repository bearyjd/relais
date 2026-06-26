# Data Layer — Room, DataStore, DI

<!-- Generated: 2026-06-26 | Files scanned: data/ + di/ | main @ 44879e6 -->

## Room — `relais.db` v4 (RelaisDatabase, versioned/additive migrations, NO destructive fallback)
Accessed via static `RelaisDatabase.get(context)` (node-layer idiom; **not** Hilt-provided). Schemas exported to `app/schemas/`.

| Entity | Table | Key columns | Feature |
|---|---|---|---|
| SchemaMeta | schema_meta | id (PK=1) | meta |
| SessionTurn | session_turns | id, sessionKey, role, content, createdAt; idx(sessionKey,createdAt) | #5 session memory |
| RagDocument | rag_documents | id, title, createdAt | #4 RAG |
| RagChunk | rag_chunks | id, documentId, chunkIndex, text, embedding(BLOB), dim, createdAt; idx(documentId) | #4 RAG |
| BatchJob | batch_jobs | id, jobId(UNIQUE), status, requestJson, resultJson, webhookUrl, created/updatedAt; idx(status,createdAt) | #14 batch |

DAOs: `SessionDao` (insert/recent/prune-TTL/trim-cap), `RagDao` (insert + brute-force chunk fetch + @Transaction delete), `BatchDao` (capacity-bounded insert, atomic claim, fail-stale, finish, prune), `SchemaMetaDao`.
Migrations: 1→2 sessions · 2→3 rag · 3→4 batch (all additive).

## Proto DataStore (javalite — these protos hard-block MediaPipe full-protobuf)
| Proto | Message | File | Holds |
|---|---|---|---|
| settings.proto | Settings | settings.pb | theme, text-input history, imported models, TOS, feature flags, viewed promos |
| settings.proto | UserData | user_data.pb | access token, secrets{}, chat sessions, mcp auths |
| settings.proto | CutoutCollection | cutouts.pb | scrapbook demo cutouts |
| benchmark.proto | BenchmarkResults | benchmark_results.pb | benchmark runs |
| skill.proto | Skills | skills.pb | agentchat skills (name, url, instructions, built_in, selected) |
Serializers: Settings/UserData/Cutouts/BenchmarkResults/Skills (object singletons). `DataStoreRepository` is the (currently blocking) facade.

## Config storage (NOT DataStore)
`RelaisConfig` — `EncryptedSharedPreferences` for API key / TLS password / HF token; plaintext prefs for modelId, opt-in flags (share/nfc/triage/session), shed thresholds (clamped on read+write), webhook allowlist, restart count.

## DI — Hilt `AppModule` (@InstallIn SingletonComponent)
Provides (all @Singleton): 5 proto Serializers → 5 `DataStore<*>` (DataStoreFactory) → `DataStoreRepository`; `AppLifecycleProvider` (GalleryLifecycleProvider); `DownloadRepository`.
**No Room provider** — `RelaisDatabase.get()` static. **No engine/HTTP in Hilt** — node core is plain singletons reachable from cold-start paths.
