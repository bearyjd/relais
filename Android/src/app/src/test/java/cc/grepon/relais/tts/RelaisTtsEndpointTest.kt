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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests for the `/v1/audio/speech` request parsing, availability fold, and WAV/PCM encoding (#168). */
class RelaisTtsEndpointTest {

  private fun body(json: String) = JSONObject(json)

  // ---- request parsing ----

  @Test fun `valid input parses with defaults`() {
    val r = parseSpeechRequest(body("""{"input":"hello world"}""")) as SpeechRequestResult.Valid
    assertEquals("hello world", r.request.input)
    assertNull(r.request.voice)
    assertEquals(TtsFormat.WAV, r.request.format) // default when response_format absent
    assertEquals(1.0f, r.request.speed, 0.0001f)
  }

  @Test fun `blank or missing input is rejected`() {
    assertTrue(parseSpeechRequest(body("""{"input":""}""")) is SpeechRequestResult.Invalid)
    assertTrue(parseSpeechRequest(body("""{"input":"   "}""")) is SpeechRequestResult.Invalid)
    assertTrue(parseSpeechRequest(body("""{}""")) is SpeechRequestResult.Invalid)
  }

  @Test fun `input over the char limit is rejected`() {
    val long = "a".repeat(TTS_LIMITS.maxInputChars + 1)
    assertTrue(parseSpeechRequest(body("""{"input":"$long"}""")) is SpeechRequestResult.Invalid)
  }

  @Test fun `pcm response_format resolves to PCM and unknown formats fall back to WAV`() {
    assertEquals(TtsFormat.PCM, (parseSpeechRequest(body("""{"input":"x","response_format":"pcm"}""")) as SpeechRequestResult.Valid).request.format)
    // mp3/opus/aac/flac aren't encoded on-device → mapped to WAV, not rejected.
    assertEquals(TtsFormat.WAV, (parseSpeechRequest(body("""{"input":"x","response_format":"mp3"}""")) as SpeechRequestResult.Valid).request.format)
  }

  @Test fun `speed is clamped into range`() {
    val slow = (parseSpeechRequest(body("""{"input":"x","speed":0.01}""")) as SpeechRequestResult.Valid).request.speed
    val fast = (parseSpeechRequest(body("""{"input":"x","speed":99}""")) as SpeechRequestResult.Valid).request.speed
    assertEquals(TTS_LIMITS.minSpeed, slow, 0.0001f)
    assertEquals(TTS_LIMITS.maxSpeed, fast, 0.0001f)
  }

  @Test fun `voice passes through when present`() {
    val r = parseSpeechRequest(body("""{"input":"x","voice":"alloy"}""")) as SpeechRequestResult.Valid
    assertEquals("alloy", r.request.voice)
  }

  // ---- availability fold ----

  @Test fun `availability fold matches the route's 501 or 503 or 200 decision`() {
    assertEquals(TtsAvailability.UNAVAILABLE, ttsAvailability(hasEngine = false, isAvailable = false, canProvision = true))
    assertEquals(TtsAvailability.READY, ttsAvailability(hasEngine = true, isAvailable = true, canProvision = false))
    assertEquals(TtsAvailability.PROVISIONING, ttsAvailability(hasEngine = true, isAvailable = false, canProvision = true))
    assertEquals(TtsAvailability.UNAVAILABLE, ttsAvailability(hasEngine = true, isAvailable = false, canProvision = false))
  }

  // ---- content type ----

  @Test fun `content type carries the sample rate for pcm`() {
    assertEquals("audio/wav", ttsContentType(TtsFormat.WAV, 22050))
    assertEquals("audio/L16;rate=22050", ttsContentType(TtsFormat.PCM, 22050))
  }

  // ---- voice registry ----

  @Test fun `default voice id resolves and unknown id does not`() {
    assertEquals(DEFAULT_TTS_VOICE_ID, ttsVoiceById(DEFAULT_TTS_VOICE_ID)?.id)
    assertNull(ttsVoiceById("no-such-voice"))
  }

  // ---- WAV / PCM encoding ----

  @Test fun `pcm16 has two bytes per sample and clamps out-of-range`() {
    val pcm = TtsWav.pcm16(floatArrayOf(0f, 1f, -1f, 2f, -2f))
    assertEquals(10, pcm.size)
    // sample 1 (=1.0) → +32767 = 0x7FFF little-endian [0xFF, 0x7F]
    assertEquals(0xFF.toByte(), pcm[2])
    assertEquals(0x7F.toByte(), pcm[3])
    // sample 3 (=2.0, clamped to 1.0) → also 0x7FFF
    assertEquals(0xFF.toByte(), pcm[6])
    assertEquals(0x7F.toByte(), pcm[7])
  }

  @Test fun `wav has a 44 byte header and correct RIFF or WAVE tags and size`() {
    val samples = FloatArray(100) { 0f }
    val wav = TtsWav.wav(samples, 22050)
    assertEquals(44 + samples.size * 2, wav.size)
    assertEquals("RIFF", String(wav.copyOfRange(0, 4), Charsets.US_ASCII))
    assertEquals("WAVE", String(wav.copyOfRange(8, 12), Charsets.US_ASCII))
    assertEquals("data", String(wav.copyOfRange(36, 40), Charsets.US_ASCII))
    // sample rate at offset 24, little-endian
    val sr = (wav[24].toInt() and 0xFF) or ((wav[25].toInt() and 0xFF) shl 8) or
      ((wav[26].toInt() and 0xFF) shl 16) or ((wav[27].toInt() and 0xFF) shl 24)
    assertEquals(22050, sr)
  }
}
