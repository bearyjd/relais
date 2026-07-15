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
 * A provisionable sherpa-onnx TTS voice: the `.tar.bz2` bundle to download + verify + extract, plus the
 * on-disk layout the [SherpaTtsEngine] loads. Plain data (no Android types) so it is unit-testable.
 *
 * A Piper (VITS) voice bundle extracts to a directory containing `<onnxName>`, `tokens.txt`, and an
 * `espeak-ng-data/` dir (the phonemizer tables). sherpa-onnx compiles espeak-ng in; the data ships with
 * the voice. espeak-ng is GPLv3 — compatible with Relais's AGPL-3.0 license (unlike a closed app).
 */
data class TtsVoice(
  val id: String,
  /** Directory name the bundle extracts to (the top-level dir inside the tarball). */
  val dirName: String,
  /** The acoustic model file inside [dirName]. */
  val onnxName: String,
  val sha256: String,
  val sizeBytes: Long,
  val url: String,
  /** Native sample rate (Hz) of the voice — Piper medium voices are 22.05 kHz. */
  val sampleRate: Int,
)

/** GitHub-release download URL for a sherpa-onnx TTS model bundle (public, no token). */
private fun sherpaTtsModel(fileName: String): String =
  "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$fileName"

/**
 * The default English Piper voice — `en_US-lessac-medium` (VITS, 22.05 kHz). Fast (measured **RTF 0.12
 * on rango / Tensor G5**, `TtsProbe`), ~63 MB acoustic model, permissive voice license (per its
 * `MODEL_CARD`). This is the always-on-node default; a higher-quality Kokoro tier is a follow-up.
 */
val TTS_VOICE_PIPER_LESSAC =
  TtsVoice(
    id = "piper-en-us-lessac-medium",
    dirName = "vits-piper-en_US-lessac-medium",
    onnxName = "en_US-lessac-medium.onnx",
    sha256 = "9e3febfacf0abf4270172d2958bcec246032b7e88efc2720840cc80c93de334e",
    sizeBytes = 67_230_653L,
    url = sherpaTtsModel("vits-piper-en_US-lessac-medium.tar.bz2"),
    sampleRate = 22_050,
  )

/** The built-in, SHA-pinned voice set. Operator selects one via [cc.grepon.relais.RelaisConfig.ttsVoiceId]. */
val TTS_VOICES: List<TtsVoice> = listOf(TTS_VOICE_PIPER_LESSAC)

/** The default voice id used when the operator hasn't chosen one. */
const val DEFAULT_TTS_VOICE_ID: String = "piper-en-us-lessac-medium"

/** Resolves [id] to a built-in [TtsVoice], or null for an unknown id. Pure + unit-testable. */
fun ttsVoiceById(id: String?): TtsVoice? = TTS_VOICES.firstOrNull { it.id == id }
