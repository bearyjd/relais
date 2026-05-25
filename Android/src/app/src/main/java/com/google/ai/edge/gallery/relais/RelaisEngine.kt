/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.relais

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val TAG = "RelaisEngine"

/** Which runtime/accelerator serves a request. See [BackendSelector]. */
enum class RelaisBackend {
  GPU_LITERTLM, // resident litertlm Engine on the GPU — full multimodal (text+image+audio)
  NPU_AICORE, // AICore/Gemini Nano on the NPU — image+text only, Pixel 10+ only (UNVERIFIED here)
}

/** Modalities present in an inbound request. */
data class RequestModalities(val hasImage: Boolean, val hasAudio: Boolean)

/** A multimodal request: text plus optional image (PNG bytes) and audio (WAV bytes). */
data class RelaisRequest(
  val text: String,
  val imagePng: ByteArray? = null,
  val audioWav: ByteArray? = null,
) {
  val modalities: RequestModalities
    get() = RequestModalities(hasImage = imagePng != null, hasAudio = audioWav != null)
}

/** Result of one inference. */
data class RelaisResult(val text: String, val backend: RelaisBackend, val decodeTokensPerSec: Double)

/**
 * Modality-aware backend selector (Gate 4).
 *
 * Rule, from SPIKE-FINDINGS.md:
 *  - audio present            -> GPU_LITERTLM (AICore/Nano cannot do audio)
 *  - image/text + AICore avail -> NPU_AICORE (Pixel 10+ only)
 *  - otherwise                -> GPU_LITERTLM
 *
 * On the Pixel 9 the AICore branch is UNVERIFIED: [aicoreAvailable] is gated to false until a
 * Pixel 10 is in hand to wire and validate the real ML Kit `checkStatus()` probe.
 */
object BackendSelector {
  fun select(modalities: RequestModalities, aicoreAvailable: Boolean): RelaisBackend =
    when {
      modalities.hasAudio -> RelaisBackend.GPU_LITERTLM
      aicoreAvailable -> RelaisBackend.NPU_AICORE
      else -> RelaisBackend.GPU_LITERTLM
    }

  /**
   * Whether the AICore/Gemini-Nano NPU path is usable on this device.
   *
   * UNVERIFIED / Pixel-10 TODO: the production probe is
   * `AICoreModelHelper.isModelDownloaded(model)` (ML Kit `checkStatus() == AVAILABLE`) AND the
   * allowlist device gate `Utils.isAICoreSupported(...)`. The Pixel 9 Pro Fold is excluded from
   * the AICore device groups, so this returns false here and the GPU path always wins.
   */
  fun aicoreAvailable(@Suppress("UNUSED_PARAMETER") context: Context): Boolean {
    // TODO(Pixel 10): wire AICoreModelHelper.checkStatus()==AVAILABLE + Utils.isAICoreSupported().
    return false
  }
}

/**
 * Process-wide holder for the single resident multimodal engine (Gate 1).
 *
 * One [Engine] is initialized once on the GPU (vision=GPU, audio=CPU) and kept alive for the
 * lifetime of [RelaisNodeService]. [generate] creates a short-lived conversation per request
 * against that resident engine, so the model is never re-loaded.
 */
object RelaisEngine {
  @Volatile private var engine: Engine? = null
  private val lock = Any()

  /** Default on-device model location (populated by the spike; see SPIKE-FINDINGS.md). */
  fun defaultModelPath(context: Context): String =
    File(context.getExternalFilesDir(null), "relais/gemma-4-E4B-it.litertlm").absolutePath

  val isReady: Boolean
    get() = engine?.isInitialized() == true

  /** Idempotent. Initializes the resident GPU multimodal engine if not already up. */
  @OptIn(ExperimentalApi::class)
  fun ensureInitialized(context: Context, modelPath: String = defaultModelPath(context)) {
    if (isReady) return
    synchronized(lock) {
      if (isReady) return
      require(File(modelPath).exists()) { "Model not found: $modelPath" }
      val cacheDir = context.getExternalFilesDir(null)?.absolutePath
      Log.i(TAG, "Initializing resident GPU multimodal engine from $modelPath")
      val e =
        Engine(
          EngineConfig(
            modelPath = modelPath,
            backend = Backend.GPU(),
            visionBackend = Backend.GPU(),
            audioBackend = Backend.CPU(),
            maxNumTokens = 1024,
            cacheDir = cacheDir,
          )
        )
      e.initialize()
      engine = e
      Log.i(TAG, "Resident engine ready: ${e.isInitialized()}")
    }
  }

  /** Runs one request against the resident engine. Routes via [BackendSelector]. */
  @OptIn(ExperimentalApi::class)
  fun generate(context: Context, request: RelaisRequest): RelaisResult {
    val backend = BackendSelector.select(request.modalities, BackendSelector.aicoreAvailable(context))
    // The AICore branch is not wired on this device; everything resolves to the resident GPU engine.
    ensureInitialized(context)
    val e = engine ?: error("Engine not initialized")

    val contents = buildList {
      request.imagePng?.let { add(Content.ImageBytes(it)) }
      request.audioWav?.let { add(Content.AudioBytes(it)) }
      if (request.text.isNotBlank()) add(Content.Text(request.text))
    }

    synchronized(lock) {
      val conversation =
        e.createConversation(
          ConversationConfig(samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = 1.0))
        )
      return try {
        // Stream so we can measure decode throughput by wall clock: BenchmarkInfo only populates
        // via the library's benchmark() path, not normal conversations (see SPIKE-FINDINGS.md / Q1).
        val sb = StringBuilder()
        var tokens = 0
        var firstTokenNs = 0L
        var lastTokenNs = 0L
        val latch = CountDownLatch(1)
        val error = arrayOfNulls<Throwable>(1)
        conversation.sendMessageAsync(
          Contents.of(contents),
          object : MessageCallback {
            override fun onMessage(message: Message) {
              val now = System.nanoTime()
              if (firstTokenNs == 0L) firstTokenNs = now
              lastTokenNs = now
              tokens++
              sb.append(message.toString())
            }

            override fun onDone() = latch.countDown()

            override fun onError(throwable: Throwable) {
              error[0] = throwable
              latch.countDown()
            }
          },
          emptyMap(),
        )
        if (!latch.await(120, TimeUnit.SECONDS)) error("inference timed out")
        error[0]?.let { throw it }
        val decodeSec = if (lastTokenNs > firstTokenNs) (lastTokenNs - firstTokenNs) / 1e9 else 0.0
        val tokS = if (decodeSec > 0 && tokens > 1) (tokens - 1) / decodeSec else 0.0
        RelaisResult(text = sb.toString(), backend = backend, decodeTokensPerSec = tokS)
      } finally {
        conversation.close()
      }
    }
  }

  fun shutdown() {
    synchronized(lock) {
      try {
        engine?.close()
      } catch (e: Exception) {
        Log.e(TAG, "Error closing engine", e)
      } finally {
        engine = null
      }
    }
  }
}
