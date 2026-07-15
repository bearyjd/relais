/*
 * Copyright (C) 2026 Entrevoix / grepon.cc
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Affero General Public License for more details.
 */

package cc.grepon.relais.tts

import org.json.JSONObject

// OpenAI POST /v1/audio/speech helpers (issue #168). Pure (no Android types) so parsing + limits are
// unit-tested on the JVM (RelaisTtsEndpointTest); the HTTP route is a thin shell over these.

/**
 * Audio container the node can encode on-device. OpenAI's mp3/opus/aac/flac map to [WAV] (no encoder).
 * [PCM] is raw signed 16-bit **little-endian** samples, matching OpenAI's `pcm` — NOT RFC-2586
 * `audio/L16` (which is big-endian), so it is labeled `audio/pcm` (with a `rate=` param) to avoid
 * telling a spec-honoring client to byte-swap.
 */
enum class TtsFormat(val wire: String, val contentType: String) {
  WAV("wav", "audio/wav"),
  PCM("pcm", "audio/pcm"),
}

/** Bounds for one speech request. [maxInputChars] caps synthesis work (OpenAI's limit is 4096). */
data class TtsLimits(val maxInputChars: Int = 4096, val minSpeed: Float = 0.25f, val maxSpeed: Float = 4.0f)

val TTS_LIMITS = TtsLimits()

/** A validated speech request. [format] is always resolved (unknown/absent → WAV). */
data class SpeechRequest(val input: String, val voice: String?, val format: TtsFormat, val speed: Float)

sealed interface SpeechRequestResult {
  data class Valid(val request: SpeechRequest) : SpeechRequestResult
  data class Invalid(val message: String) : SpeechRequestResult
}

/**
 * Resolve an OpenAI `response_format` string to a [TtsFormat] the node can produce. Unknown or absent
 * (incl. the mp3/opus/aac/flac we don't encode on-device) → [TtsFormat.WAV], the self-describing default.
 */
fun resolveTtsFormat(raw: String?): TtsFormat =
  when (raw?.trim()?.lowercase()) {
    "pcm" -> TtsFormat.PCM
    else -> TtsFormat.WAV
  }

/**
 * Parse + validate an OpenAI `/v1/audio/speech` body. Rules (the route maps [SpeechRequestResult.Invalid]
 * → 400): `input` is required and non-blank and ≤ [TtsLimits.maxInputChars]; `voice` is optional
 * (null = the node's configured default); `speed` is clamped to `[minSpeed, maxSpeed]`; `response_format`
 * resolves via [resolveTtsFormat]. `model` is accepted but ignored (the node serves its provisioned voice).
 */
fun parseSpeechRequest(body: JSONObject, limits: TtsLimits = TTS_LIMITS): SpeechRequestResult {
  val input = body.optString("input", "")
  if (input.isBlank()) return SpeechRequestResult.Invalid("'input' is required")
  if (input.length > limits.maxInputChars) {
    return SpeechRequestResult.Invalid("'input' exceeds ${limits.maxInputChars} characters")
  }
  val voice = body.optString("voice", "").takeIf { it.isNotBlank() }
  val format = resolveTtsFormat(body.optString("response_format", null))
  val rawSpeed = if (body.has("speed")) body.optDouble("speed", 1.0).toFloat() else 1.0f
  val speed = rawSpeed.coerceIn(limits.minSpeed, limits.maxSpeed)
  return SpeechRequestResult.Valid(SpeechRequest(input = input, voice = voice, format = format, speed = speed))
}

/** OpenAI-style error envelope for the speech route's 400/501/503 paths. */
fun buildTtsError(message: String, type: String): String =
  JSONObject().put("error", JSONObject().put("message", message).put("type", type)).toString()

/** Content-Type header value for a produced audio [format] at [sampleRate] Hz (rate param aids PCM clients). */
fun ttsContentType(format: TtsFormat, sampleRate: Int): String =
  when (format) {
    TtsFormat.WAV -> format.contentType
    TtsFormat.PCM -> "${format.contentType};rate=$sampleRate"
  }
