# Plan: Wire audio input through the API

## ⚠️ Headline finding (probe-first, per CLAUDE.md)
**Audio input is ALREADY wired through the HTTP `/v1/chat/completions` API — end to end.** This plan does
NOT build that (it exists). It scopes only the genuine residuals: (1) optional internal-facade parity, (2)
audio-format documentation, (3) a fresh on-device re-verification. This is the repo's recurring
"a feature plan claimed X was unavailable — verify against the AAR/code first" trap; here X = audio, and
the prior session's offhand "audio may not be exposed" was wrong.

## Summary
The OpenAI-compatible chat endpoint already accepts `input_audio` content parts (base64 WAV), carries them
as `RelaisRequest.audioWav`, builds `Content.AudioBytes` for the resident LiteRT-LM engine, sets
`audioBackend = Backend.CPU()`, and forces the GPU backend when audio is present. A JVM parser test and an
on-device multimodal probe already cover it. Residual work is parity + docs + re-verification only.

## User Story
As a LAN client of a Relais node, I want to send audio in a chat request and get a text response, so that I
can do on-device speech/audio understanding — **already supported**; this plan hardens and documents it.

## Problem → Solution
"Is audio input exposed through the API?" (uncertain) → "Yes, verified in code + tests; documented + re-proven
on-device, with internal-facade parity for completeness."

## Metadata
- **Complexity**: Small (≤3 files; mostly docs + verification)
- **Source PRD**: N/A (free-form)
- **PRD Phase**: N/A
- **Estimated Files**: 2–3 (1 facade, 1 test, 1 doc)

---

## What already works (evidence — do NOT re-implement)

| Capability | File:Line | Snippet |
|---|---|---|
| Parse `input_audio` part (base64) | `RelaisOpenAiParser.kt:94-95` | `"input_audio" -> audio = part.optJSONObject("input_audio")?.optString("data")?.let { decode(it) }` |
| Request field | `RelaisEngine.kt:101` | `val audioWav: ByteArray? = null` |
| Modalities derive | `RelaisEngine.kt` (`RelaisRequest.modalities`) | `RequestModalities(hasImage = imagePng != null, hasAudio = audioWav != null)` |
| Content build (stream) | `RelaisEngine.kt:413` | `request.audioWav?.let { add(Content.AudioBytes(it)) }` |
| Content build (tools) | `RelaisEngine.kt:600` | same, in `generateWithTools` |
| Content build (history) | `RelaisEngine.kt:681` | `audioWav?.let { add(Content.AudioBytes(it)) }` in `ParsedTurn.toResidentMessage` |
| Engine config | `RelaisEngine.kt:319` | `audioBackend = Backend.CPU()` in the multimodal `EngineConfig` |
| Backend gate | `RelaisEngine.kt:206` | `modalities.hasAudio -> RelaisBackend.GPU_LITERTLM` (AICore can't do audio) |
| Body cap | `RelaisHttpServer.kt:70` | `MAX_BODY_BYTES = 32 * 1024 * 1024` (covers base64 WAV) |
| Endpoint label | `RelaisMetrics.kt:244` | `/v1/chat/completions` already whitelisted (audio reuses it) |
| JVM parser test | `OpenAiRequestParserTest.kt:100-118` | asserts `lastUserAudio` extracted from an `input_audio` part |
| On-device probe | `RelaisNodeTest.kt:184-187` | `audioWav = sineWav(1)` → `assertTrue(non-blank)` + `assertEquals(GPU_LITERTLM, audio.backend)` |

**Conclusion:** the request path, engine path, backend selection, size cap, metrics label, and both a unit
test and an on-device test are all present. The HTTP API feature is complete.

---

## Mandatory Reading (for the residual work)

| Priority | File | Lines | Why |
|---|---|---|---|
| P0 | `Android/src/app/src/main/java/cc/grepon/relais/core/RelaisInference.kt` | 53-99 | The facade that has `images` but no `audio` param — the one real gap |
| P0 | `Android/src/app/src/main/java/cc/grepon/relais/RelaisEngine.kt` | 98-151, 312-338, 411-415 | RelaisRequest, EngineConfig, Content build — the contract to honor |
| P1 | `Android/src/app/src/test/java/cc/grepon/relais/OpenAiRequestParserTest.kt` | 100-118 | Parser test style to extend |
| P1 | `Android/src/app/src/androidTest/java/cc/grepon/relais/RelaisNodeTest.kt` | 161-187 | The on-device audio assertion to re-run as verification |
| P2 | `docs/litertlm-native-api.md` | 25, 38 | Audio Content + EngineConfig surface; **silent on audio format/SR — to document** |

## External Documentation
| Topic | Source | Key Takeaway |
|---|---|---|
| OpenAI audio input shape | platform.openai.com chat `input_audio` | `{"type":"input_audio","input_audio":{"data":"<b64>","format":"wav"|"mp3"}}` — Relais reads `.data`, ignores `.format` (assumes WAV) |
| LiteRT-LM audio | `docs/litertlm-native-api.md` | `Content.AudioBytes(ByteArray)` / `AudioFile(path)`; `audioBackend`; **no documented sample-rate/format/duration constraints** — must be determined empirically |

---

## Patterns to Mirror

### REQUEST_CONTENT_BUILD
// SOURCE: RelaisEngine.kt:411-415
```kotlin
val contents = buildList {
  request.imagePng?.let { add(Content.ImageBytes(it)) }
  request.audioWav?.let { add(Content.AudioBytes(it)) }
  if (request.text.isNotBlank()) add(Content.Text(request.text))
}
```

### FACADE_IMAGE_PARAM (to extend with audio)
// SOURCE: core/RelaisInference.kt:53-63
```kotlin
fun complete(context: Context, prompt: String, system: String? = null,
             images: List<Uri> = emptyList()): Flow<String> {
  // ...
  imagePng = images.firstOrNull()?.let { readPng(context, it) },
}
```

### PARSER_TEST
// SOURCE: OpenAiRequestParserTest.kt:100-118 (extend for multi-turn history audio)

---

## Files to Change

| File | Action | Justification |
|---|---|---|
| `core/RelaisInference.kt` | UPDATE | Add optional `audio: Uri? = null` (or `ByteArray?`) param to `complete`/`completeText`; read bytes → `RelaisRequest.audioWav`. Parity with `images`. **Optional** — no internal caller needs it yet (share=image, triage/widget=text). |
| `Android/src/app/src/test/.../OpenAiRequestParserTest.kt` | UPDATE | Add a case: audio in a NON-last user turn lands in `history` (the parser already supports `audioWav` in `ParsedTurn`). |
| `docs/litertlm-native-api.md` (or new `docs/audio-input.md`) | UPDATE | Document the wire shape (`input_audio`/base64), that `format` is ignored (WAV assumed), the 32 MB body cap, GPU-forced routing, and the empirically-confirmed sample-rate/encoding the model accepts. |

## NOT Building
- HTTP request parsing for audio — **already done** (`RelaisOpenAiParser.kt:94`).
- `RelaisRequest.audioWav`, `Content.AudioBytes` plumbing, `audioBackend` — **already done**.
- Backend selection for audio — **already done** (`BackendSelector`).
- Audio *output* / TTS — out of scope (LiteRT-LM is text-out).
- Transcoding mp3→wav or resampling — out of scope (assume the client sends model-compatible WAV; document the requirement instead).

---

## Step-by-Step Tasks

### Task 1: Re-verify on-device FIRST (gate the rest)
- **ACTION**: With rango live (node serving E2B, GPU), confirm audio truly works on the *current* model before any code.
- **IMPLEMENT**: Two probes — (a) HTTP: `POST /v1/chat/completions` with a real base64 WAV `input_audio` part, assert a coherent non-error reply; (b) re-run the existing on-device assertion `RelaisNodeTest.g1_residentMultimodalViaService` (the `audioWav = sineWav(1)` path).
- **MIRROR**: `RelaisNodeTest.kt:184-187`.
- **GOTCHA**: `sineWav(1)` is a synthetic tone — the model may reply vaguely; assert *non-blank + GPU backend*, not specific content. A real spoken-word WAV is a better semantic check. Audio routes to `Backend.GPU()` engine but `audioBackend = Backend.CPU()` — both must be present in the resident `EngineConfig` or the model rebuilds text-only (then audio is silently dropped). Verify `isMultimodal == true` after warm.
- **VALIDATE**: HTTP returns 200 with a non-empty `choices[0].message.content`; metric `relais_requests_total{endpoint="/v1/chat/completions"}` increments; logcat shows GPU backend, no `isMissingEncoder` fallback.

### Task 2: Document audio format constraints
- **ACTION**: Capture what the resident model actually accepts (from Task 1 experiments: sample rate, mono/stereo, PCM WAV).
- **IMPLEMENT**: A short `## Audio input` section: wire shape, `format` ignored (WAV assumed), 32 MB cap, GPU routing, confirmed SR/encoding, "no resampling — client must send compatible WAV."
- **MIRROR**: existing doc style in `docs/litertlm-native-api.md`.
- **GOTCHA**: Don't claim constraints you didn't test — the AAR doc is silent, so state empirically-verified values only.
- **VALIDATE**: Doc matches Task 1 observations.

### Task 3 (optional parity): facade audio param
- **ACTION**: Add `audio: Uri? = null` to `RelaisInference.complete`/`completeText`; read bytes via a `readWav(context, uri)` helper mirroring `readPng`; set `RelaisRequest.audioWav`.
- **IMPLEMENT**: mirror the `images.firstOrNull()?.let { readPng(...) }` line.
- **MIRROR**: `core/RelaisInference.kt:63`.
- **IMPORTS**: none new.
- **GOTCHA**: Purely additive; default `null` keeps every existing caller unchanged. Only worth doing if a near-term internal caller (e.g., an audio share target) needs it — otherwise note as deferred.
- **VALIDATE**: `:app:testFullDebugUnitTest` + `:app:testDegoogledDebugUnitTest` green; no caller breakage.

### Task 4: parser history test
- **ACTION**: Add a JVM test that an `input_audio` part in a NON-last user turn is preserved into `history` with `audioWav` set.
- **MIRROR**: `OpenAiRequestParserTest.kt:100-118`.
- **VALIDATE**: green offline.

---

## Testing Strategy

### Unit Tests
| Test | Input | Expected | Edge? |
|---|---|---|---|
| audio in last user turn | `input_audio` part | `lastUserAudio == bytes` | (exists) |
| audio in history turn | `input_audio` in turn 1 of 3 | `history[0].audioWav == bytes` | yes |
| missing `data` field | `{"type":"input_audio","input_audio":{}}` | audio stays null, no crash | yes |
| oversized body | >32 MB | 413/400 at body cap | yes |

### Edge Cases Checklist
- [ ] Empty/missing `data` → null, no crash
- [ ] Body at 32 MB cap boundary
- [ ] Audio + image + text in one message (all three Content parts)
- [ ] Model lacks audio encoder → graceful text-only fallback (no crash), audio silently ignored (document this)
- [ ] Audio forces GPU even if AICore available

---

## Validation Commands
```bash
# Offline unit tests (both flavors; one invocation each — both at once OOMs the 2GB daemon)
cd Android/src && ./gradlew :app:testFullDebugUnitTest -x lint --offline --max-workers=2
cd Android/src && ./gradlew :app:testDegoogledDebugUnitTest -x lint --offline --max-workers=2
```
EXPECT: green.

On-device (rango live, port-forward 18080→8080):
```bash
# base64 a real WAV and POST it as input_audio
curl -s -X POST http://127.0.0.1:18080/v1/chat/completions \
  -H "Authorization: Bearer <KEY>" -H "Content-Type: application/json" \
  -d '{"model":"relais","messages":[{"role":"user","content":[
        {"type":"text","text":"What do you hear? One sentence."},
        {"type":"input_audio","input_audio":{"data":"<BASE64_WAV>","format":"wav"}}]}],
       "max_tokens":48}'
```
EXPECT: 200 with a non-empty `content`; logcat shows GPU + multimodal engine, no encoder fallback.

### Manual Validation
- [ ] HTTP audio request returns a coherent reply on rango
- [ ] `RelaisNodeTest.g1_residentMultimodalViaService` audio assertion passes
- [ ] Docs match observed audio constraints

---

## Acceptance Criteria
- [ ] On-device audio request verified working on the current resident model
- [ ] Audio format/constraints documented from real observation
- [ ] (optional) facade audio param added with green both-flavor units
- [ ] Parser history-audio test added + green

## Risks
| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Resident model lacks an audio encoder on this SoC → audio silently dropped (text-only fallback) | Medium | High (feature looks broken) | Task 1 asserts `isMultimodal` + GPU + non-blank; if it fails, the real work becomes *model selection* (a Gemma 3n build with the audio encoder), not API wiring — re-scope then |
| `sineWav` is too synthetic to prove semantic audio understanding | Medium | Low | Use a real spoken-word WAV for the manual check |
| Undocumented sample-rate/format mismatch → garbage output | Medium | Medium | Empirically pin SR/encoding in Task 1, document in Task 2 |

## Notes
- The substantive uncertainty is NOT the API (it's wired) — it's **whether the resident E2B/E4B `.litertlm` on a
  given SoC actually ships the audio encoder**. If Task 1 shows a text-only fallback, the feature is gated on a
  multimodal-with-audio model build, which is a separate model-provisioning effort.
- This plan is intentionally small because the probe-first investigation found the feature already implemented.
  Recommended next step is **verification, not implementation** — run Task 1 on rango now (it's connected).
