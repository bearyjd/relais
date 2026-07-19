# Relais — Architecture

<!-- Generated: 2026-07-19 | Files scanned: 334 main .kt | main @ ab345ff -->

## What it is
Headless on-device LLM node: runs a model on the phone, serves an **OpenAI-compatible API over the LAN** — now spanning chat, embeddings, RAG, rerank (completes the "RAG triad"), TTS, tool-calling, structured output, and batch. Fork of `google-ai-edge/gallery`. Relais-authored node code (AGPL) lives under `Android/src/app/src/main/java/cc/grepon/relais/`; `ui/` + `customtasks/` are mostly still **live inherited-Gallery code**, wired in via Hilt `@IntoSet` multibinding (not visible to a plain import search) — only a small, hand-verified 29-file pocket is actually dead. See `frontend.md`.

## Layer boundaries
- **Node core** (Relais): HTTP API, engine adapter, headless FGS host, in-process inference seam.
- **Feature subsystems** (Relais): embed, rerank, rag, tts, nodetools, batch, imagegen, automation, triage/notifications, share, nfc, tile, widget, templates.
- **App shell** (Relais): `RelaisAppShell` NavHost — Dashboard/Chat/Models bottom nav, single `MainActivity` launcher.
- **Inherited Gallery, mostly still live**: `ui/` (114 files) + `customtasks/` (43 files) — `GalleryNavGraph`/`GalleryApp()` are dead, but `ModelManagerViewModel`/`BenchmarkScreen` are wired directly into `RelaisAppShell`, and `agentchat`/`mobileactions`/`tinygarden`/`llmchat`/`llmsingleturn` are Hilt `@IntoSet`-bound into it. Only 29 files are genuinely dead (see `frontend.md`).

## Data flow
```
LAN client (OpenAI SDK)
   │ HTTPS :8443 (bearer, self-signed TLS)        loopback HTTP 127.0.0.1:8080
   ▼
RelaisHttpServer (1876L, pure parse→gate→dispatch) ──► ~20 handleX(ctx: RequestContext) handlers
   │                                                    + core/ pure seams (Admission, ToolParsing,
   │                                                    StructuredOutput, SessionPolicy, Reasoning)
   ▼
RelaisEngine (782L) ──► litertlm 0.12.0 AAR — GPU_LITERTLM / NPU_AICORE / TPU_LITERTLM (Tensor G5)
   │         └─► native mid-decode cancel (conversation.cancelProcess(), off-thread, issue #165)
   ▼
side-systems: embed/ (EmbeddingGemma) rerank/ rag/ tts/ (sherpa-onnx+Piper) batch/ imagegen/ nodetools/
RelaisNodeService (FGS, START_STICKY): provision→engine init→bind HTTP/HTTPS→mDNS→kick workers
RelaisWatchdog (exact alarm, exp backoff) recovers · ThermalGovernor sheds/truncates
```

## Two consumer surfaces
- **HTTP** — LAN clients via `RelaisHttpServer` (:8443 TLS, :8080 loopback).
- **In-process** — `core/RelaisInference` lets tiles/widgets/share/nfc/triage/automation run inference with **no HTTP**, always **cold-start-guarded**.

## Inference model
GPU litertlm is the general-purpose resident path; TPU (Tensor G5, dispatcher-gated, requires an AOT-compiled `.litertlm`) is the fast lane on Pixel 10 — litertlm **pinned to 0.12.0** (0.14.0 tested + reverted: regresses the G5 TPU lane, issue #150). `BackendSelector` routes per modality (audio→GPU).

## Resilience & safety spine
FGS + watchdog + thermal governor + **native mid-decode cancel**; per-IP rate limit; semaphore admission (shared) + exclusive drain-all (image-gen); secrets in `EncryptedSharedPreferences`; metrics label-hygiene; HTTP loopback-only, HTTPS LAN bearer-gated.

## See also
`backend.md` · `frontend.md` · `data.md` · `dependencies.md`
