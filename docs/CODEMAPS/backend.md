# Backend — HTTP API & Node Lifecycle

<!-- Generated: 2026-06-26 | Files scanned: core/ + RelaisHttpServer/Engine/Node + worker/batch | main @ 44879e6 -->

## Routes (RelaisHttpServer ~1570L)
Auth: bearer token, constant-time compare; all routes except `/health` gated.
```
GET  /health                  → status/ready/thermal           (no auth)
GET  /                        → scriptless metrics dashboard (CSP)
GET  /metrics                 → Prometheus text | JSON (Accept)
GET  /v1/models               → buildModelsResponse (OpenAI list)
POST /generate                → RelaisEngine.generate → {text, decode_tok_s}
POST /v1/chat/completions     → handleOpenAi → session+RAG inject → tools|structured|plain
                                 → RelaisEngine.generate (SSE stream or JSON)
POST /v1/embeddings           → RelaisEmbedderProvider → EmbeddingGemmaEmbedder.embed (768-dim)
GET/POST/DELETE /v1/rag/documents   → RagStore ingest/list/delete
POST /v1/rag/query            → RagStore cosine top-k
POST /v1/batch                → enqueue (Room) → 202 {job_id}; BatchWorker drains
GET  /v1/batch/{job_id}       → job status/result
GET/DELETE /v1/sessions       → session-memory turns (#5)
GET  /v1/clientconfig         → paste-ready {baseUrl,apiKey,modelId,caps}
POST /v1/images/generations   → RelaisImageGeneratorProvider → honest 501 (#16 unregistered)
```
Shed order per request: thermal **503** → queue **429** → auth **401** → run **200**.

## Node lifecycle (RelaisNodeService FGS ~186L)
```
onCreate → wakelock + ThermalGovernor + restartCount++
 → RelaisEngine.ensureInitialized (bg): provision model → G5/E4B gate
      → build resident Engine (multimodal→text fallback), isMultimodal flag
 → EmbeddingGemmaEmbedder warmIfProvisioned
 → bind HTTP 127.0.0.1:8080 + HTTPS 0.0.0.0:8443 (BouncyCastle self-signed)
 → RelaisDiscovery mDNS _relais._tcp → SessionPruneWorker → BatchWorker.kick
 → notify "engine ready"
WATCHDOG (RelaisWatchdogReceiver, exact alarm): if shouldRun & !ready & !starting
   → exp backoff (60s→cap 15m) → NodeService.start() (idempotent); reset() when ready
```

## core/ pure seams (JVM, unit-testable, no Android Context)
| File | Purpose |
|---|---|
| Admission | rate-limit + queue/thermal shed decisions |
| ToolParsing | parse model tool_calls / fold tool results (#9) |
| StructuredOutput | json_schema-as-tool, json_object validate+repair (#15) |
| SessionPolicy | session-key resolve + history eligibility (#5) |
| Reasoning | reasoning_content channel deltas (#11) |
| FinishReason | stop/length/tool finish mapping |
| ClientConfig | /v1/clientconfig envelope |
| RelaisInference (~106L) | in-process non-HTTP inference for tiles/widgets/nfc/share; NodeNotReady eager check |
| NodeState (~43L) | OFF/STARTING/LIVE/HOT/ERROR enum + computeNodeState() |
| RelaisNodeController (~43L) | thin isRunning/state/start/stop for tiles/widgets |

## Key files
| Path | Purpose | ~LOC |
|---|---|---|
| RelaisHttpServer.kt | routing + OpenAI compat layer | 1570 |
| RelaisEngine.kt | resident GPU/NPU engine; generate dispatcher; tool+reasoning; thermal-cancel seam | 800+ |
| RelaisNodeService.kt | FGS host orchestration | 186 |
| RelaisWatchdog.kt | exp-backoff alarm recovery | 143 |
| ThermalGovernor.kt | thermal status + decode-throughput-floor shed/truncate | ~150 |
| RelaisMetrics.kt | Prometheus/JSON, latency histograms, label hygiene (M6) | ~150 |
| batch/BatchWorker.kt | off-path drain + HMAC-signed webhook (#14) | ~140 |
| batch/WebhookGuard.kt | SSRF allowlist for webhook URLs | — |

## Admission / backpressure
Rate limit 30 req/min per IP (stale-IP eviction >4096); queue cap 16; thermal shed at SEVERE / low decode tok/s; cooldown on MODERATE. Body read capped (no Content-Length pre-alloc).
