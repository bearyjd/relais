# Data Layer — Room, DataStore, DI

<!-- Generated: 2026-07-19 | Files scanned: data/ + di/ + rag/RagStore + tts/imagegen provisioners | main @ ab345ff -->

## Room — `relais.db` v5 (was v4; still additive-only, NO destructive fallback)
Accessed via static `RelaisDatabase.get(context)` (not Hilt-provided).

| Entity | Table | Key columns | Feature |
|---|---|---|---|
| SchemaMeta | schema_meta | id (PK=1) | meta |
| SessionTurn | session_turns | id, sessionKey, role, content, createdAt | server session memory |
| RagDocument | rag_documents | id, title, createdAt | RAG |
| RagChunk | rag_chunks | id, documentId, chunkIndex, text, embedding(BLOB,256-dim MRL), createdAt | RAG |
| BatchJob | batch_jobs | id, jobId(UNIQUE), status, requestJson, resultJson, webhookUrl | batch |
| **Conversation** | conversations | id, title, modelId, created/updatedAt | **chat depth [NEW]** |
| **ChatTurn** | chat_turns | id, conversationId(FK CASCADE), role, content, attachmentPath?, answeredByBackend? | **chat depth [NEW]** |

DAOs: SessionDao, RagDao, BatchDao, SchemaMetaDao, **ChatDao** (upsert/rename/touch/delete conversation, observe turns Flow, delete-turns-after for edit/retry). Migrations: 1→2→3→4 (unchanged) + **4→5 chat depth**.

## Proto DataStore (unchanged since 06-26)
Settings/UserData/Cutouts/BenchmarkResults/Skills — same 5 serializers, same facade (`DataStoreRepository`).

## Config storage (NOT DataStore)
`RelaisConfig` — `EncryptedSharedPreferences` for API key/TLS password/HF token; plaintext prefs for modelId, opt-ins, shed thresholds.

## Model/voice provisioning (byte-size/filename-keyed on disk, NOT DB-tracked)
| Asset | Path | Completeness check |
|---|---|---|
| TTS voices | `externalFiles/tts/<voice>/` | onnx + tokens.txt + espeak-ng-data/ all present |
| Embedding model | `externalFiles/relais/embed/` | variant file + tokenizer, byte-exact size |
| Image-gen model | `externalFiles/relais/imagegen/` | byte-size check |
| Chat attachments | `filesDir/chat/<turnId>.<ext>` | tracked via `ChatTurn.attachmentPath`, Room |

## DI — Hilt `AppModule` (unchanged since 06-26)
5 proto Serializers → `DataStore<*>` → `DataStoreRepository`; `AppLifecycleProvider`; `DownloadRepository`. Still **no Room provider** (static `RelaisDatabase.get()`), no engine/HTTP/embedder/TTS in Hilt.
