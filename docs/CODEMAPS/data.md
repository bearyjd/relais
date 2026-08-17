# Data Layer — Room, DataStore, DI

<!-- Generated: 2026-08-17 | Files scanned: data/ + di/ + rag/RagStore + tts/imagegen provisioners + RelaisModelRegistry | main @ 4a283858 -->

## Room — `relais.db` v6 (was v5; still additive-only, NO destructive fallback)
Accessed via static `RelaisDatabase.get(context)` (not Hilt-provided).

| Entity | Table | Key columns | Feature |
|---|---|---|---|
| SchemaMeta | schema_meta | id (PK=1) | meta |
| SessionTurn | session_turns | id, sessionKey, role, content, createdAt | server session memory |
| RagDocument | rag_documents | id, title, createdAt | RAG |
| RagChunk | rag_chunks | id, documentId, chunkIndex, text, embedding(BLOB,256-dim MRL), createdAt | RAG |
| BatchJob | batch_jobs | id, jobId(UNIQUE), status, requestJson, resultJson, webhookUrl | batch |
| Conversation | conversations | id, title, modelId, created/updatedAt | chat depth |
| ChatTurn | chat_turns | id, conversationId(FK CASCADE), role, content, attachmentPath?, answeredByBackend? | chat depth |
| **ContentReport** | content_reports | id(auto PK), reasonId(enum **id string**, not ordinal), excerpt, note?, modelId?, backend?, surface(`chat`/`gallery_chat`), createdAt(indexed) | **AI-content reports, #258 [NEW]** |

DAOs: SessionDao, RagDao, BatchDao, SchemaMetaDao, ChatDao (upsert/rename/touch/delete conversation, observe turns Flow, delete-turns-after for edit/retry), **ReportDao**. Migrations: 1→2→3→4→5 (unchanged) + **5→6 content_reports**.

**Report egress (the one Room table with an off-device leg):** local insert is unconditional on
SUBMIT; delivery is per-report opt-in, default off — `ChatViewModel`/`ChatPanel` →
`deliverReport()` (`chat/ContentReportOutcome.kt`, shared gate) → `ContentReportDelivery` (POST
`https://report.ventouxlabs.com/report`, redirects disabled, notice generation-guarded per
report). No delivery-status column exists — a failed opt-in send is unrecoverable (#273 tracks
retry). Rows reviewable in-app: `ContentReportsActivity` (CONFIGURE › REPORTED OUTPUT).

## Proto DataStore (unchanged since 06-26)
Settings/UserData/Cutouts/BenchmarkResults/Skills — same 5 serializers, same facade (`DataStoreRepository`).

## Config storage (NOT DataStore)
`RelaisConfig` — `EncryptedSharedPreferences` for API key/TLS password/HF token; plaintext prefs for modelId, opt-ins, shed thresholds.

## Model registry (#180) — plaintext pref, not Room
`provisioned_models` holds `ProvisionedModel(modelId, path, displayName)` as a JSON array via `RelaisConfig.provisionedModels`/`setProvisionedModels`. It is the **safety boundary** for per-request model swaps: it only grows on a locally-successful provision, so a LAN client can complete a swap the operator already initiated but can never originate a download. **Pruned on READ**, not just on write — `pruneMissingProvisioned` drops entries whose file has vanished (storage cleared, model deleted, side-load removed), so eligibility reflects the filesystem rather than the last write.

## Model/voice provisioning (byte-size/filename-keyed on disk, NOT DB-tracked)
| Asset | Path | Completeness check |
|---|---|---|
| TTS voices | `externalFiles/tts/<voice>/` | onnx + tokens.txt + espeak-ng-data/ all present |
| Embedding model | `externalFiles/relais/embed/` | variant file + tokenizer, byte-exact size |
| Image-gen model | `externalFiles/relais/imagegen/` | byte-size check |
| Chat attachments | `filesDir/chat/<turnId>.<ext>` | tracked via `ChatTurn.attachmentPath`, Room |

## DI — Hilt `AppModule` (unchanged since 06-26)
5 proto Serializers → `DataStore<*>` → `DataStoreRepository`; `AppLifecycleProvider`; `DownloadRepository`. Still **no Room provider** (static `RelaisDatabase.get()`), no engine/HTTP/embedder/TTS in Hilt.
