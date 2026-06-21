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

package cc.grepon.relais.imagegen

import android.content.Context

/**
 * Text-to-image generation facade. LiteRT-LM is text-OUT only, so on-device image generation is a
 * SEPARATE runtime — the only viable path is MediaPipe Image Generator (Stable Diffusion v1.5),
 * delivered as a follow-up to feature #16 (`com.google.mediapipe:tasks-vision-image-generator`,
 * verified GMS-free → ships in both flavors). This mirrors the #6 embeddings architecture exactly:
 * an interface + a process-wide provider singleton + an honest 501 until a concrete impl registers.
 *
 * Until the MediaPipe backend lands, [RelaisImageGeneratorProvider.get] is null and
 * `POST /v1/images/generations` returns 501.
 */
interface RelaisImageGenerator {
  /** True once a model bundle is provisioned + loaded. Callers gate on this (→ 501 when false). */
  fun isAvailable(context: Context): Boolean

  /**
   * Generates ONE image for [prompt] and returns it as PNG bytes. [steps] is the diffusion iteration
   * count (quality/time knob); [seed] is optional (null → impl picks one). Blocking and heavy
   * (~15 s/image on a high-end GPU) — call off-main, single-flight, behind the admission gate. The
   * route loops this for `n > 1`, bounding total wall-clock + heat between images.
   *
   * [shouldCancel] lets the heaviest decode on the device bail mid-flight under thermal pressure, the
   * same seam [cc.grepon.relais.RelaisEngine.generate] exposes. The param exists from the start (with
   * a no-op default) so wiring it into a concrete impl later is not a breaking interface change.
   */
  fun generate(
    context: Context,
    prompt: String,
    steps: Int,
    seed: Long?,
    shouldCancel: () -> Boolean = { false },
  ): ByteArray
}

/**
 * Process-wide registration seam — the single source of truth `POST /v1/images/generations` reads to
 * decide 200 vs 501. Keeping it null until a real impl registers means no half-wired image path can
 * exist before the MediaPipe backend lands. The concrete impl will call [register] once at init
 * (mirroring how feature #6 registers its [cc.grepon.relais.embed.RelaisEmbedder]).
 */
object RelaisImageGeneratorProvider {
  @Volatile private var impl: RelaisImageGenerator? = null

  /** Register (or clear, with null) the image-generator implementation. */
  fun register(generator: RelaisImageGenerator?) {
    impl = generator
  }

  /** The registered generator, or null if none. Callers still check [RelaisImageGenerator.isAvailable]. */
  fun get(): RelaisImageGenerator? = impl
}
