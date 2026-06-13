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

package cc.grepon.relais

import android.content.Context
import android.util.Log
import cc.grepon.relais.common.isPixel10
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "RelaisEngine"
private const val MAX_NUM_TOKENS = 1024

/**
 * Models that initialize cleanly but then SIGSEGV natively on the FIRST inference on Tensor G5
 * (Pixel 10) — a LiteRT-LM model×SoC bug reproduced on litertlm 0.11.0 AND 0.13.1, on both CPU and
 * GPU and with a text-only engine config, using a byte-identical model that serves fine on Tensor
 * G4. A native SIGSEGV can't be caught, so these are refused pre-flight on Pixel 10 instead of
 * crash-looping. Text-only models (e.g. Qwen3-0.6B) run fine on G5. See SPIKE-FINDINGS.md.
 *
 * The id set references the canonical [RelaisConfig.DEFAULT_MODEL_ID] so a model rename can't
 * silently make the gate inert; the file set is the defense-in-depth twin so the gate still fires if
 * the selected id and the on-disk file ever diverge (e.g. an interrupted re-provision).
 */
private val G5_INCOMPATIBLE_MODEL_IDS = setOf(RelaisConfig.DEFAULT_MODEL_ID)
private val G5_INCOMPATIBLE_FILES = setOf("gemma-4-E4B-it.litertlm")

/** True when the model about to load is known to crash natively on this SoC (Tensor G5 / Pixel 10). */
internal fun isG5Incompatible(modelId: String, modelPath: String, isPixel10: Boolean): Boolean =
  isPixel10 &&
    (modelId in G5_INCOMPATIBLE_MODEL_IDS || modelPath.substringAfterLast('/') in G5_INCOMPATIBLE_FILES)

private val MISSING_ENCODER_REGEX = Regex("\\bTF_LITE_[A-Z0-9_]*ENCODER", RegexOption.IGNORE_CASE)

/**
 * Whether [t] is LiteRT-LM rejecting a model for lacking an optional image/audio encoder — the
 * signal that a model is text-only and the engine must be rebuilt without the vision/audio backends.
 * Anchored to the `NOT_FOUND` + `TF_LITE_*_ENCODER` token shape (not a bare "ENCODER" substring) so
 * an unrelated failure that merely mentions an encoder does NOT trigger a silent downgrade of a
 * multimodal model. Both known phrasings match: litertlm 0.11 "Failed to create engine: NOT_FOUND:
 * TF_LITE_VISION_ENCODER…" (at initialize) and 0.13 "…NOT_FOUND: TF_LITE_AUDIO_ENCODER_HW…" (at
 * createConversation).
 */
internal fun isMissingEncoder(t: Throwable): Boolean {
  val m = t.message ?: return false
  return m.contains("NOT_FOUND", ignoreCase = true) && MISSING_ENCODER_REGEX.containsMatchIn(m)
}

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
   * Whether the AICore/Gemini-Nano NPU path is usable on this device, via a real ML Kit
   * `checkStatus()` probe ([RelaisAicore.available]). Returns false on the Pixel 9 (not in the
   * AICore device groups), so the GPU path always wins here; lights up on a Pixel 10.
   */
  fun aicoreAvailable(context: Context): Boolean = RelaisAicore.available(context)
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

  /**
   * True while the node is provisioning/initializing in this process (e.g. a first-run multi-GB
   * model download). The watchdog uses this to distinguish "still coming up" from "dead", so a slow
   * first start is not mistaken for a failure (no backoff escalation, no premature alarm).
   */
  @Volatile var startupInProgress: Boolean = false

  /**
   * Legacy hardcoded model location from the spike (manually side-loaded; see SPIKE-FINDINGS.md).
   * Now only a fallback — the node self-provisions to [Model.getPath]'s layout via
   * [RelaisModelProvisioner], and [ensureInitialized] defaults to that resolved path.
   */
  fun defaultModelPath(context: Context): String =
    File(context.getExternalFilesDir(null), "relais/gemma-4-E4B-it.litertlm").absolutePath

  val isReady: Boolean
    get() = engine?.isInitialized() == true

  /**
   * Idempotent. Initializes the resident GPU multimodal engine if not already up. Defaults to the
   * path resolved by [RelaisModelProvisioner] (falling back to [defaultModelPath] pre-provision).
   */
  @OptIn(ExperimentalApi::class)
  fun ensureInitialized(
    context: Context,
    modelPath: String = RelaisModelProvisioner.cachedPathOrDefault(context),
  ) {
    if (isReady) return
    synchronized(lock) {
      if (isReady) return
      require(File(modelPath).exists()) { "Model not found: $modelPath" }
      // Pixel-10 / Tensor-G5 pre-flight gate: gemma-4-E4B initializes but SIGSEGVs natively on the
      // first inference on G5 (a LiteRT-LM bug). A native crash can't be caught, so refuse before
      // loading rather than crash-loop; the operator can pick a G5-compatible model (e.g. Qwen3).
      val modelId = RelaisConfig.modelId(context)
      check(!isG5Incompatible(modelId, modelPath, isPixel10())) {
        "Model '$modelId' isn't supported on this device's Tensor G5 — LiteRT-LM crashes natively " +
          "during inference. Open the Relais control panel and select a G5-compatible model " +
          "(e.g. search \"Qwen3\" in the model picker)."
      }
      val cacheDir = context.getExternalFilesDir(null)?.absolutePath
      // Speculative decoding is SUPPORTED by E4B (Capabilities.hasSpeculativeDecodingSupport()=true)
      // but MEASURED A REGRESSION on this E4B/GPU/Tensor-G4 config: ~2.56 tok/s with it on vs
      // ~5.63 tok/s off (draft overhead > gains, no draft model bundled). Left OFF deliberately.
      ExperimentalFlags.enableSpeculativeDecoding = false
      engine = buildResidentEngine(modelPath, cacheDir)
    }
  }

  /**
   * Builds the resident engine, adapting the config to the model's actual modalities. Tries the
   * full multimodal config first (vision=GPU, audio=CPU); if the model exposes no image/audio
   * encoder ([createConversation] -> NOT_FOUND ..._ENCODER), rebuilds text-only. This lets the node
   * serve text-only models (e.g. Qwen3) as well as multimodal ones (gemma-4-E4B) without the
   * operator declaring modalities, and a non-encoder failure propagates (no silent downgrade of a
   * multimodal model). The probe creates+closes a conversation but runs NO inference, so it does not
   * trigger the gemma-4-E4B/G5 decode crash (guarded separately above).
   *
   * Two-rung ladder only (full multimodal | text-only): a single-modality model (vision-only or
   * audio-only) is requested both encoders, fails the missing one, and degrades to text-only. That is
   * acceptable today (gemma-4-E4B has both, Qwen3 has neither); add intermediate rungs if a
   * single-encoder model ever ships.
   */
  @OptIn(ExperimentalApi::class)
  private fun buildResidentEngine(modelPath: String, cacheDir: String?): Engine {
    val multimodal =
      EngineConfig(
        modelPath = modelPath,
        backend = Backend.GPU(),
        visionBackend = Backend.GPU(),
        audioBackend = Backend.CPU(),
        maxNumTokens = MAX_NUM_TOKENS,
        cacheDir = cacheDir,
      )
    buildIfModelAccepts(multimodal, "multimodal")?.let { return it }
    Log.i(TAG, "Model has no image/audio encoder; rebuilding a text-only engine")
    val textOnly =
      EngineConfig(
        modelPath = modelPath,
        backend = Backend.GPU(),
        maxNumTokens = MAX_NUM_TOKENS,
        cacheDir = cacheDir,
      )
    return buildIfModelAccepts(textOnly, "text-only")
      ?: error("Engine init failed for $modelPath")
  }

  /**
   * Initializes an [Engine] for [config] and probes it with a throwaway conversation. Returns the
   * ready engine, or null iff the model rejects this config for a missing image/audio encoder
   * ([isMissingEncoder]) — the caller then retries with fewer backends. Any other error is rethrown.
   */
  @OptIn(ExperimentalApi::class)
  private fun buildIfModelAccepts(config: EngineConfig, label: String): Engine? {
    Log.i(TAG, "Initializing resident $label engine from ${config.modelPath}")
    val e = Engine(config)
    return try {
      // A model lacking a requested image/audio encoder is rejected either at initialize() (litertlm
      // 0.11 — "Failed to create engine: NOT_FOUND: TF_LITE_VISION_ENCODER") or at createConversation
      // (0.13 — audio encoder), so wrap both. The probe runs NO inference, so it cannot trigger the
      // gemma-4-E4B/Tensor-G5 decode crash (gated separately in ensureInitialized).
      e.initialize()
      // Sampler values are immaterial — the probe runs no decode; it only forces litertlm to resolve
      // the requested encoders so a text-only model is detected before the config is committed.
      e.createConversation(
          ConversationConfig(samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = 1.0))
        )
        .close()
      Log.i(TAG, "Resident $label engine ready: ${e.isInitialized()}")
      e
    } catch (t: Throwable) {
      runCatching { e.close() }.onFailure { Log.w(TAG, "Failed to close rejected $label probe engine", it) }
      if (isMissingEncoder(t)) {
        Log.w(TAG, "$label config rejected (model lacks an encoder): ${t.message}")
        null
      } else {
        throw t
      }
    }
  }

  /**
   * Runs one request against the resident engine. Routes via [BackendSelector]. If [onToken] is
   * provided, each decoded delta is delivered as it streams (used for SSE / chunked HTTP).
   */
  @OptIn(ExperimentalApi::class)
  fun generate(
    context: Context,
    request: RelaisRequest,
    onToken: ((String) -> Unit)? = null,
    shouldCancel: (() -> Boolean)? = null,
  ): RelaisResult {
    RelaisMetrics.incInFlight() // counts both queued (waiting on lock) and running -> queue_depth
    val reqStartNs = System.nanoTime()
    try {
      val backend = BackendSelector.select(request.modalities, BackendSelector.aicoreAvailable(context))

      // NPU path (Pixel 10+): Gemini Nano via AICore, image/text only. UNVERIFIED on Pixel 9 —
      // never selected here because aicoreAvailable() is false.
      if (backend == RelaisBackend.NPU_AICORE) {
        val text = RelaisAicore.generate(request)
        onToken?.invoke(text)
        return RelaisResult(text = text, backend = backend, decodeTokensPerSec = 0.0)
      }

      ensureInitialized(context)
      val e = engine ?: error("Engine not initialized")

      val contents = buildList {
        request.imagePng?.let { add(Content.ImageBytes(it)) }
        request.audioWav?.let { add(Content.AudioBytes(it)) }
        if (request.text.isNotBlank()) add(Content.Text(request.text))
      }

      synchronized(lock) {
        // Thermal cool-down spaces *actual* decode runs: applied under the lock, not per-request on
        // the worker pool, so concurrent requests don't all sleep in parallel and then serialize.
        ThermalGovernor.cooldownMs().takeIf { it > 0 }?.let { runCatching { Thread.sleep(it) } }
        val conversation =
          e.createConversation(
            ConversationConfig(samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = 1.0))
          )
        return try {
          // Stream so we can measure decode throughput by wall clock: BenchmarkInfo only populates
          // via the library's benchmark() path, not normal conversations (SPIKE-FINDINGS.md / Q1).
          val sb = StringBuilder()
          var tokens = 0
          var firstTokenNs = 0L
          var lastTokenNs = 0L
          val canceled = AtomicBoolean(false)
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
                val delta = message.toString()
                sb.append(delta)
                if (canceled.get()) return
                // Cooperative cancel: thermal-truncate (device-protective) or client disconnect
                // (onToken throws on a broken pipe). Best-effort — it stops streaming to the client;
                // native decode is bounded by maxNumTokens. True mid-decode native stop is a TODO
                // pending a litertlm cancellation API (see SPIKE-FINDINGS / Gate 2 stopResponse).
                if (shouldCancel?.invoke() == true) {
                  canceled.set(true)
                  return
                }
                try {
                  onToken?.invoke(delta)
                } catch (t: Throwable) {
                  canceled.set(true)
                }
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
          RelaisMetrics.recordThroughput(tokens, tokS, backend.name)
          ThermalGovernor.onDecodeThroughput(tokS)
          RelaisResult(text = sb.toString(), backend = backend, decodeTokensPerSec = tokS)
        } finally {
          conversation.close()
        }
      }
    } finally {
      RelaisMetrics.recordLatency((System.nanoTime() - reqStartNs) / 1e9) // every outcome (HIGH-2)
      RelaisMetrics.decInFlight()
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
