# Sending Content to a Relais Node

What content a Relais node accepts, and through which channels. Relais is a headless on-device LLM
node — it takes text/image/audio **in** and returns **text** out. (Image *generation* is not shipped:
`POST /v1/images/generations` is an honest `501`.)

## Modalities

| Type | Format | Used as |
|---|---|---|
| **Text** | plain / chat messages | prompts, multi-turn conversation, system prompts, RAG documents |
| **Image** | OpenAI `image_url` data-URI (chat) or `image_b64` (`/generate`) | native **vision** — the multimodal model sees the image (`Content.ImageBytes`) |
| **Audio** | OpenAI `input_audio.data` (chat) or `audio_b64` (`/generate`), **WAV** bytes | native **audio-in** (`Content.AudioBytes`) — speech/audio understanding |

Notes:
- The parser extracts text + the **first** image + the **first** audio from a message's content parts.
- Image and audio require a **multimodal model** to be resident (gemma-4 E2B/E4B class). A text-only
  model ignores them. On **Tensor G5** the node is pinned to **E2B** (E4B first-inference crash,
  LiteRT-LM #2566).

## Channels

### 1. LAN HTTP API (OpenAI-compatible — the primary surface)
HTTPS `:8443` (bearer token) on the LAN; loopback HTTP `127.0.0.1:8080` for on-device clients.

| Endpoint | Accepts |
|---|---|
| `POST /v1/chat/completions` | text + image (`image_url`) + audio (`input_audio`); multi-turn `messages`; tools/function-calling; structured output (`response_format`); streaming |
| `POST /generate` | `{text, image_b64?, audio_b64?, system?, template?}` |
| `POST /v1/embeddings` | text (string or array) → vectors |
| `POST /v1/rag/documents` | text documents to ingest into the corpus |
| `POST /v1/rag/query` | text query against the ingested corpus |
| `POST /v1/batch` | chat jobs for async processing (+ HMAC-signed webhook callback) |

Example — text + image + audio in one chat turn:
```bash
curl -k https://<node-ip>:8443/v1/chat/completions \
  -H "Authorization: Bearer $RELAIS_API_KEY" -H "Content-Type: application/json" \
  -d '{"model":"gemma-4-E4B-it","messages":[{"role":"user","content":[
        {"type":"text","text":"Describe this and transcribe the audio."},
        {"type":"image_url","image_url":{"url":"data:image/png;base64,<...>"}},
        {"type":"input_audio","input_audio":{"data":"<base64-wav>","format":"wav"}}
      ]}]}'
```

### 2. On-device / from other apps (no API client needed)

| Channel | Send | Notes |
|---|---|---|
| **Android Share sheet** (`ACTION_SEND` / `SEND_MULTIPLE`) | text or images | ⚠️ shared images go through **OCR** → text → inference (**not** vision); OCR is **full** flavor only |
| **NFC tag tap** | a workflow URL + optional inline prompt | `…://workflow/<templateId>?q=<prompt>`; opt-in, default off |
| **Tasker / Automate intent** | a prompt (text) | `…action.INFER` ABI, token-gated; answer returned as a broadcast |
| **Quick Settings tile / home widget** | a canned-prompt template | a trigger, not free-form content |
| **Notification triage** | (ingests your notifications) | node summarizes/surfaces them; opt-in, default off, default-deny allowlist |

## Caveats
- **Output is text only.** No image generation (`/v1/images/generations` → 501; works on G3, deadlocks on G5).
- **Vision/audio** need a multimodal resident model; G5 is E2B-pinned.
- The **degoogled** flavor drops ML Kit, so the **share→image→OCR** path is unavailable there (the HTTP
  vision path still works on a multimodal model).

## See also
- `docs/CODEMAPS/backend.md` — full route → handler map
- `docs/node-tools-api.md`, `docs/rag-api.md`, `docs/embeddings-api.md`, `docs/batch-api.md` — per-endpoint detail
- `docs/tasker-intent-abi.md`, `docs/share-image-ocr.md`, `docs/nfc-workflows.md`, `docs/notification-triage.md` — on-device input ABIs
