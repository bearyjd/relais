# Relais — Architecture

<!-- Generated: 2026-06-26 | Files scanned: 289 main + 87 test .kt | main @ 44879e6 -->

## What it is
Headless on-device LLM node: runs a model on the phone, serves an **OpenAI-compatible API over the LAN**. Fork of `google-ai-edge/gallery`. Relais-authored node code (AGPL) lives under `Android/src/app/src/main/java/cc/grepon/relais/`; the `ui/` + `customtasks/` trees are **inherited Gallery** (re-namespaced, largely un-redesigned — off `DESIGN.md`).

## Layer boundaries
- **Node core** (Relais): HTTP API, engine adapter, headless FGS host, in-process inference seam.
- **Feature subsystems** (Relais): embed, rag, nodetools, batch, triage/notifications, share, nfc, tile, widget, templates, automation, imagegen.
- **Inherited Gallery**: `ui/` (chat + model UI), `customtasks/` (agentchat / mobileactions / tinygarden).

## Data flow
```
LAN client (OpenAI SDK)
   │ HTTPS :8443 (bearer, self-signed TLS)        loopback HTTP 127.0.0.1:8080
   ▼
RelaisHttpServer ──► core/ pure seams (Admission, ToolParsing,
   │                  StructuredOutput, SessionPolicy, Reasoning, FinishReason, ClientConfig)
   │                         │
   ▼                         ▼
RelaisEngine ──► litertlm 0.11.0 AAR (GPU-resident Engine, never reloaded)   [primary]
   ▲         └─► RelaisAicore (NPU / ML Kit GenAI, full flavor, Pixel text+img)
   │
RelaisNodeService (FGS, START_STICKY): provision→engine init→bind HTTP/HTTPS→mDNS→kick workers
RelaisWatchdog (exact alarm, exp backoff) recovers · ThermalGovernor sheds/truncates
```

## Two consumer surfaces
- **HTTP** — LAN clients via `RelaisHttpServer` (:8443 TLS, :8080 loopback).
- **In-process** — `core/RelaisInference` lets tiles/widgets/share/nfc/triage/automation run inference with **no HTTP**, always **cold-start-guarded** (never boots the engine itself).

## Inference model
GPU litertlm is the resident path; `BackendSelector` routes per modality (audio→GPU). Default model `gemma-4-E4B-it`; **Tensor G5 pins E2B** (E4B first-inference SIGSEGV, upstream LiteRT-LM #2566).

## Resilience & safety spine
FGS + watchdog + thermal governor; per-IP rate limit; semaphore admission (queue cap 16); secrets in `EncryptedSharedPreferences`; metrics label-hygiene; HTTP loopback-only, HTTPS LAN bearer-gated.

## See also
`backend.md` · `frontend.md` · `data.md` · `dependencies.md`
