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
 * SEPARATE runtime. Per the on-device verdict (`docs/images-generations-api.md`), MediaPipe Image
 * Generator is a DEAD END on this stack: its Java API needs full `protobuf-java`, while the app is
 * hard-locked to `protobuf-javalite` — an irreconcilable build-time clash. The viable path is sd.cpp
 * (Stable Diffusion via Vulkan) in a process-isolated `:imagegen` backend, a follow-up to feature
 * #16. This mirrors the #6 embeddings architecture: an interface + a process-wide provider singleton
 * + an honest 501 until a concrete impl registers.
 *
 * Until an image-gen backend registers, [RelaisImageGeneratorProvider.get] is null and
 * `POST /v1/images/generations` returns 501.
 */
interface RelaisImageGenerator {
  /** True once a model bundle is provisioned + loaded. Callers gate on this (→ 501 when false). */
  fun isAvailable(context: Context): Boolean

  /**
   * True iff the backend is otherwise ready (GPU present) but the model just isn't on disk yet, so a
   * background provision would make it available — mirrors the embedder's `canProvision`. Drives the
   * endpoint's 503-while-provisioning branch vs a permanent 501. Default false (no provisioner).
   */
  fun canProvision(context: Context): Boolean = false

  /**
   * Kick a one-time background download/verify of the selected model (idempotent; non-blocking). Called
   * by the route when [canProvision] is true so the request thread never blocks on the multi-GB fetch —
   * mirrors `EmbeddingGemmaEmbedder.ensureProvisioningStarted`. Default no-op.
   */
  fun ensureProvisioningStarted(context: Context) {}

  /**
   * The route's 501/503/200 decision for THIS generator, computed in a single call so a provision
   * completing between separate [isAvailable]/[canProvision] reads can't produce a spurious 501 (TOCTOU).
   * The default composes the two booleans via [imageGenAvailability]; a concrete impl should override it
   * to read ONE consistent snapshot (one Vulkan + one provisioned check).
   */
  fun availability(context: Context): ImageGenAvailability =
    imageGenAvailability(hasGenerator = true, isAvailable = isAvailable(context), canProvision = canProvision(context))

  /**
   * Generates ONE image for [prompt] and returns it as PNG bytes. [steps] is the diffusion iteration
   * count (quality/time knob); [seed] is optional (null → impl picks one). Blocking and heavy (the
   * heaviest decode on the device — ~60–90 s/image for sd.cpp on a Tensor GPU) — call off-main,
   * single-flight, behind the admission gate. The
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
 * exist before an image-gen backend registers. The concrete impl will call [register] once at init
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

/** What `POST /v1/images/generations` should do, given the registered generator's state. */
enum class ImageGenAvailability {
  /** A backend is registered + ready → generate (200). */
  READY,
  /** Ready-but-unprovisioned → kick a background download + reply 503 Retry-After. */
  PROVISIONING,
  /** No backend, or a backend with no GPU / nothing to provision → honest 501. */
  UNAVAILABLE,
}

/**
 * Pure routing decision for the images endpoint (mirrors the embeddings 501/503/200 gate). Kept here,
 * Context-free, so it is unit-testable without a device: the route reads the three booleans off the
 * registered [RelaisImageGenerator] and acts on the result.
 */
fun imageGenAvailability(hasGenerator: Boolean, isAvailable: Boolean, canProvision: Boolean): ImageGenAvailability =
  when {
    !hasGenerator -> ImageGenAvailability.UNAVAILABLE
    isAvailable -> ImageGenAvailability.READY
    canProvision -> ImageGenAvailability.PROVISIONING
    else -> ImageGenAvailability.UNAVAILABLE
  }
