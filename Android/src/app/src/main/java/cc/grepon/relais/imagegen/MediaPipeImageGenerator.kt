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

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import com.google.mediapipe.framework.image.BitmapExtractor
import com.google.mediapipe.tasks.vision.imagegenerator.ImageGenerator
import com.google.mediapipe.tasks.vision.imagegenerator.ImageGenerator.ImageGeneratorOptions
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * [RelaisImageGenerator] backed by the MediaPipe Image Generator (Stable Diffusion v1.5).
 *
 * **NOT registered yet.** This is the verified-against-the-real-API implementation; the
 * [cc.grepon.relais.imagegen.RelaisImageGeneratorProvider.register] call + the on-disk provisioner
 * that populates [modelDir] land in the #16 backend follow-up, **after** the OOM/memory-coexistence
 * prototype on a device (the resident multi-GB LLM + SD 1.5 together is the plan's #1 risk). Until
 * then `POST /v1/images/generations` stays an honest 501.
 *
 * API shape verified 2026-06-20 (document-specialist research) against the official Android guide +
 * `google-ai-edge` sample/gallery helpers:
 * - model path is a **directory** of ~hundreds of float16 `.bin` weights + `bpe_simple_vocab_16e6.txt`
 *   (`setImageGeneratorModelDirectory`), ~1.9 GB, provisioned on demand (no Google-hosted bundle).
 * - `generate`/`execute` are **blocking** (call off-main) and **not thread-safe** → the HTTP route
 *   single-flights via the admission gate; one [generate] at a time.
 * - hard runtime gates ([deviceSupported]): arm64-v8a only (JNI `.so` is arm64), OpenCL GPU required
 *   (else `createFromOptions` throws), API ≥ 31, and ≥~7 GB RAM (OOMs at 512×512 on 6 GB — fail-safe
 *   to 501 rather than crash the node).
 */
class MediaPipeImageGenerator(
  /** Directory holding the converted SD-1.5 weights (a provisioner populates this; see class KDoc). */
  private val modelDir: File,
) : RelaisImageGenerator {

  @Volatile private var generator: ImageGenerator? = null
  private val loadLock = Any()

  override fun isAvailable(context: Context): Boolean = generator != null

  /**
   * Device-capability gate. `ImageGenerator.createFromOptions` throws on unsupported hardware, so
   * check before attempting a load — a false return keeps the endpoint at an honest 501 instead of a
   * runtime crash. The RAM floor is a conservative fail-safe (OOM with the resident LLM is the
   * device-only risk this whole feature is gated behind); tune it once the prototype has run.
   */
  fun deviceSupported(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false // API 31 for the OpenCL native lib
    if (Build.SUPPORTED_ABIS.none { it == "arm64-v8a" }) return false // JNI .so is arm64-v8a only
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
    val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
    return mem.totalMem >= MIN_RAM_BYTES
  }

  /** True once the converted model directory is present + non-empty (the provisioner's job). */
  fun isProvisioned(): Boolean = modelDir.isDirectory && (modelDir.list()?.isNotEmpty() == true)

  /**
   * Loads the generator on the **calling (background) thread**, single-flight. No-op once loaded.
   * Returns true when a generator is ready. Mirrors the demand-driven load of [EmbeddingGemmaEmbedder].
   */
  fun ensureLoaded(context: Context): Boolean {
    generator?.let { return true }
    if (!deviceSupported(context) || !isProvisioned()) return false
    // Double-checked lock (mirrors EmbeddingGemmaEmbedder.ensureLoaded): a contended caller waits and
    // then sees the generator the winner loaded, rather than getting a spurious "not loaded".
    synchronized(loadLock) {
      generator?.let { return true }
      return try {
        val options = ImageGeneratorOptions.builder()
          .setImageGeneratorModelDirectory(modelDir.absolutePath)
          .build()
        generator = ImageGenerator.createFromOptions(context, options)
        true
      } catch (e: Exception) {
        // OpenCL/GPU unavailable or a corrupt model dir — fail loud in the log, stay 501 to the caller.
        Log.e(TAG, "MediaPipe ImageGenerator init failed", e)
        generator = null
        false
      }
    }
  }

  /**
   * Generates ONE 512×512 image and returns PNG bytes. Blocking; caller must single-flight (admission
   * gate). Uses the stepwise `setInputs`/`execute` API so [shouldCancel] can bail between diffusion
   * steps under thermal pressure (only the final step renders, to avoid the per-step decode cost).
   */
  override fun generate(
    context: Context,
    prompt: String,
    steps: Int,
    seed: Long?,
    shouldCancel: () -> Boolean,
  ): ByteArray {
    val gen = generator ?: error("image generator not loaded")
    val iters = steps.coerceAtLeast(1)
    // MediaPipe's seed is a 32-bit Int. Narrow the optional 64-bit request seed EXPLICITLY (low 32
    // bits) so the mapping is stable and obvious rather than a silent sign-wrapping toInt(); derive a
    // varied seed when absent. (Two request seeds differing only above bit 32 collapse here — noted on
    // the interface's `seed` param; reject out-of-Int-range seeds at the route if exact 64-bit matters.)
    val mpSeed = seed?.let { (it and 0xFFFFFFFFL).toInt() } ?: (System.nanoTime() and 0x7fffffffL).toInt()
    gen.setInputs(prompt, iters, mpSeed)
    var last = gen.execute(/* showResult = */ iters == 1)
    for (i in 1 until iters) {
      // TODO(#16 route follow-up): a thermal-cancel bail currently surfaces as the outer-catch 500;
      // map it to 503 + Retry-After (the codebase's backpressure signal) when the route wires this on.
      if (shouldCancel()) error("image generation cancelled (thermal backpressure)")
      last = gen.execute(/* showResult = */ i == iters - 1) // render only the final frame
    }
    val image = last?.generatedImage() ?: error("image generation produced no image")
    val bitmap: Bitmap = BitmapExtractor.extract(image)
    return ByteArrayOutputStream().use { out ->
      bitmap.compress(Bitmap.CompressFormat.PNG, /* quality = */ 100, out)
      out.toByteArray()
    }
  }

  /**
   * Releases the native/GPU resources. Safe to call repeatedly; never throws. Synchronized on the same
   * [loadLock] as [ensureLoaded] so a close can't race a concurrent load (no orphaned generator).
   * Caller must still not overlap [close] with an in-flight [generate] (the route single-flights both).
   */
  fun close() {
    synchronized(loadLock) {
      runCatching { generator?.close() }
      generator = null
    }
  }

  private companion object {
    const val TAG = "MediaPipeImageGen"
    // ~7 GB: admits 8-GB-nominal devices (report ~7.4–7.9 GB) and excludes 6-GB devices (~5.6 GB),
    // which OOM at 512×512 even without a coexisting LLM. Conservative fail-safe pending the prototype.
    const val MIN_RAM_BYTES = 7_000_000_000L
  }
}
