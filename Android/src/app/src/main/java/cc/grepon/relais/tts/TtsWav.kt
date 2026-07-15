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

/**
 * Encodes mono float PCM (`[-1, 1]`) into the two container formats `POST /v1/audio/speech` serves.
 * Pure (no Android types) so the header layout + clamping are covered by fast JVM unit tests.
 *
 * `response_format`:
 *  - `wav`  → 16-bit little-endian PCM with a 44-byte RIFF/WAVE header (self-describing sample rate).
 *  - `pcm`  → the raw 16-bit little-endian samples, no header (OpenAI's `pcm` = 24 kHz s16le; here the
 *             rate is the voice's native rate, surfaced via the response `Content-Type` rate param).
 *
 * (mp3/opus/aac/flac are OpenAI options we don't encode on-device — the route maps them to `wav`.)
 */
object TtsWav {

  /** Clamp + convert one float sample to a little-endian 16-bit PCM pair. */
  private fun s16(sample: Float): Int {
    val clamped = if (sample > 1f) 1f else if (sample < -1f) -1f else sample
    // Symmetric scale to the 16-bit range; round-to-nearest.
    return (clamped * 32767f).let { if (it >= 0) (it + 0.5f).toInt() else (it - 0.5f).toInt() }
  }

  /** Raw 16-bit little-endian PCM bytes (no header). */
  fun pcm16(samples: FloatArray): ByteArray {
    val out = ByteArray(samples.size * 2)
    var i = 0
    for (s in samples) {
      val v = s16(s)
      out[i++] = (v and 0xFF).toByte()
      out[i++] = ((v shr 8) and 0xFF).toByte()
    }
    return out
  }

  /** A complete WAV file: 44-byte RIFF/WAVE header + 16-bit PCM data for [samples] at [sampleRate] Hz. */
  fun wav(samples: FloatArray, sampleRate: Int, channels: Int = 1): ByteArray {
    val data = pcm16(samples)
    val byteRate = sampleRate * channels * 2
    val blockAlign = channels * 2
    val header = ByteArray(44)
    fun putAscii(off: Int, s: String) { for (k in s.indices) header[off + k] = s[k].code.toByte() }
    fun putLE32(off: Int, v: Int) {
      header[off] = (v and 0xFF).toByte()
      header[off + 1] = ((v shr 8) and 0xFF).toByte()
      header[off + 2] = ((v shr 16) and 0xFF).toByte()
      header[off + 3] = ((v shr 24) and 0xFF).toByte()
    }
    fun putLE16(off: Int, v: Int) {
      header[off] = (v and 0xFF).toByte()
      header[off + 1] = ((v shr 8) and 0xFF).toByte()
    }
    putAscii(0, "RIFF")
    putLE32(4, 36 + data.size) // ChunkSize = 36 + Subchunk2Size
    putAscii(8, "WAVE")
    putAscii(12, "fmt ")
    putLE32(16, 16) // Subchunk1Size (PCM)
    putLE16(20, 1) // AudioFormat = 1 (PCM)
    putLE16(22, channels)
    putLE32(24, sampleRate)
    putLE32(28, byteRate)
    putLE16(32, blockAlign)
    putLE16(34, 16) // BitsPerSample
    putAscii(36, "data")
    putLE32(40, data.size) // Subchunk2Size
    return header + data
  }
}
