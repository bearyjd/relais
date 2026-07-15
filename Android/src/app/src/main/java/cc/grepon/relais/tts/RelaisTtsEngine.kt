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

import android.content.Context

/**
 * On-device text-to-speech facade (issue #168 — north-star pillar 2: audio generation). LiteRT-LM is
 * text-OUT only, so speech synthesis is a SEPARATE runtime: **sherpa-onnx** wrapping a **Piper** voice
 * (measured RTF 0.12 on rango/G5, `TtsProbe`). Unlike image-gen and OCR (GMS-bound → `full`-only), the
 * TTS runtime has no Google dependency, so it registers in `main` and works on ALL flavors incl.
 * `degoogled`.
 *
 * Mirrors the #6 embeddings / #16 image-gen architecture: an interface + a process-wide provider
 * singleton + an honest 501 until a concrete impl registers. Until then [RelaisTtsEngineProvider.get]
 * is null and `POST /v1/audio/speech` returns 501.
 */
interface RelaisTtsEngine {
  /** True once a voice is provisioned + the engine is loaded. Callers gate on this (→ 501 when false). */
  fun isAvailable(context: Context): Boolean

  /**
   * True iff the runtime is present but the selected voice just isn't on disk yet, so a background
   * provision would make it available — mirrors the embedder's `canProvision`. Drives the endpoint's
   * 503-while-provisioning branch vs a permanent 501. Default false.
   */
  fun canProvision(context: Context): Boolean = false

  /**
   * Kick a one-time background download/verify/extract of the selected voice (idempotent; non-blocking).
   * Called by the route when [canProvision] is true so the request thread never blocks on the fetch.
   */
  fun ensureProvisioningStarted(context: Context) {}

  /**
   * The route's 501/503/200 decision computed in ONE call so a provision completing between separate
   * [isAvailable]/[canProvision] reads can't produce a spurious 501 (TOCTOU). A concrete impl should
   * override to read a single consistent snapshot.
   */
  fun availability(context: Context): TtsAvailability =
    ttsAvailability(hasEngine = true, isAvailable = isAvailable(context), canProvision = canProvision(context))

  /**
   * Synthesize [text] to 16-bit-PCM audio. [voice] selects a voice/speaker (impl-defined; null = the
   * configured default). [speed] is a tempo multiplier (1.0 = natural; OpenAI's `speed`). Blocking and
   * CPU-heavy — call off the request's hot path behind the admission gate. Returns raw float samples +
   * the sample rate; the HTTP layer encodes the requested container (WAV/PCM).
   */
  fun synthesize(context: Context, text: String, voice: String? = null, speed: Float = 1.0f): TtsAudio
}

/** Synthesized audio: mono float PCM in [-1, 1] plus its sample rate (Hz). */
data class TtsAudio(val samples: FloatArray, val sampleRate: Int) {
  // Value-ish semantics for a FloatArray-bearing data class (satisfies equals/hashCode contracts).
  override fun equals(other: Any?): Boolean =
    this === other ||
      (other is TtsAudio && sampleRate == other.sampleRate && samples.contentEquals(other.samples))

  override fun hashCode(): Int = 31 * samples.contentHashCode() + sampleRate

  /** Duration of the produced audio in milliseconds. */
  val durationMs: Long
    get() = if (sampleRate > 0) samples.size * 1000L / sampleRate else 0L
}

/**
 * Process-wide registration seam — the single source of truth `POST /v1/audio/speech` reads to decide
 * 200 vs 501. Null until a real impl registers (mirrors [cc.grepon.relais.embed.RelaisEmbedderProvider]
 * / [cc.grepon.relais.imagegen.RelaisImageGeneratorProvider]).
 */
object RelaisTtsEngineProvider {
  @Volatile private var impl: RelaisTtsEngine? = null

  /** Register (or clear, with null) the TTS engine implementation. */
  fun register(engine: RelaisTtsEngine?) {
    impl = engine
  }

  /** The registered engine, or null if none. Callers still check [RelaisTtsEngine.availability]. */
  fun get(): RelaisTtsEngine? = impl
}

/** What `POST /v1/audio/speech` should do, given the registered engine's state. */
enum class TtsAvailability {
  /** An engine is registered + a voice is provisioned → synthesize (200). */
  READY,

  /** Registered but the voice isn't on disk yet → kick a background provision + reply 503 Retry-After. */
  PROVISIONING,

  /** No engine registered, or nothing to provision → honest 501. */
  UNAVAILABLE,
}

/** Pure decision function (unit-testable): folds the engine's booleans into a [TtsAvailability]. */
fun ttsAvailability(hasEngine: Boolean, isAvailable: Boolean, canProvision: Boolean): TtsAvailability =
  when {
    !hasEngine -> TtsAvailability.UNAVAILABLE
    isAvailable -> TtsAvailability.READY
    canProvision -> TtsAvailability.PROVISIONING
    else -> TtsAvailability.UNAVAILABLE
  }
