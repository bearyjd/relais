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
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolCall
import com.google.ai.edge.litertlm.tool
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

private const val TAG = "RelaisEngine"
private const val MAX_NUM_TOKENS = 1024

// Default sampler params (used when a request omits them). topK has no OpenAI equivalent, so it is
// fixed; temperature/top_p/seed mirror the OpenAI request fields. Previous behavior was the hardcoded
// (64, 0.95, 1.0); these preserve it as the default while letting callers override per request.
private const val DEFAULT_TOP_K = 64
private const val DEFAULT_TOP_P = 0.95
private const val DEFAULT_TEMPERATURE = 1.0
private const val DEFAULT_SEED = 0

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
  /** System prompt extracted from the OpenAI messages[] array. Null for the /generate path. */
  val systemPrompt: String? = null,
  /**
   * Prior conversation turns (oldest first) extracted from the OpenAI messages[] array.
   * Empty for the /generate path. Seeded into the LiteRT-LM conversation as
   * [ConversationConfig.initialMessages] in [RelaisEngine.generate] — prefilled as context, not
   * replayed turn-by-turn, so a multi-turn request costs one decode rather than one per turn.
   */
  val history: List<ParsedTurn> = emptyList(),
  /** OpenAI `tools` to advertise to the model. Empty for the non-tool path. */
  val tools: List<ToolSpec> = emptyList(),
  /** OpenAI `tool_choice`. Carried through; only [ToolChoice.None] suppresses tools (HTTP layer). */
  val toolChoice: ToolChoice = ToolChoice.Auto,
  /** Trailing tool-result turn (from a `role:"tool"` run) that drives a tool round-trip. */
  val toolResults: List<ToolResult> = emptyList(),
  /** OpenAI `temperature` (0.0–2.0). Null = unspecified -> engine default ([DEFAULT_TEMPERATURE]). */
  val temperature: Double? = null,
  /** OpenAI `top_p` (0.0–1.0). Null = unspecified -> engine default ([DEFAULT_TOP_P]). */
  val topP: Double? = null,
  /** Sampling seed for reproducibility. Null = unspecified -> [DEFAULT_SEED]. */
  val seed: Int? = null,
  /**
   * When true, request the model's reasoning ("thinking") channel: the engine passes
   * `extraContext["enable_thinking"]="true"` and captures the separate `message.channels["thought"]`
   * stream into [RelaisResult.reasoning] (surfaced as OpenAI `reasoning_content`). Derived from the
   * OpenAI `reasoning_effort` field (absent/"none" -> false). Default false = today's behavior (no
   * thinking, no latency tax). Honored on the streaming text path only; the tool and
   * structured-output paths ignore it (v1).
   */
  val enableThinking: Boolean = false,
) {
  val modalities: RequestModalities
    get() = RequestModalities(hasImage = imagePng != null, hasAudio = audioWav != null)

  /** Resolves the per-request [SamplerConfig], clamping to valid ranges and applying defaults. */
  @OptIn(ExperimentalApi::class)
  fun samplerConfig(): SamplerConfig =
    SamplerConfig(
      topK = DEFAULT_TOP_K,
      topP = (topP ?: DEFAULT_TOP_P).coerceIn(0.0, 1.0),
      temperature = (temperature ?: DEFAULT_TEMPERATURE).coerceIn(0.0, 2.0),
      seed = seed ?: DEFAULT_SEED,
    )
}

/** Result of one inference. */
data class RelaisResult(
  val text: String,
  val backend: RelaisBackend,
  val decodeTokensPerSec: Double,
  /**
   * Raw decode token count from the onMessage callback loop (completion_tokens in OpenAI usage).
   * Zero for the NPU/AICore path which does not expose per-token callbacks (UNVERIFIED path).
   *
   * NOTE: on a cooperative cancel (thermal truncate via [ThermalGovernor.shouldTruncate] or broken
   * pipe in the onToken lambda) this counter reflects all tokens the engine DECODED — which may
   * exceed the tokens the client actually received in its SSE stream. A future
   * finish_reason="length" signal will make this visible to callers; deferred (needs
   * RelaisResult.finishReason + cancel-state threading across both HTTP paths).
   */
  val completionTokens: Int,
  /**
   * Tool calls the model emitted on the blocking tool path (empty on the streaming/text paths).
   * Populated only when [RelaisRequest.tools]/[RelaisRequest.toolResults] route through the
   * dedicated blocking tool branch in [RelaisEngine.generate].
   */
  val toolCalls: List<ParsedToolCall> = emptyList(),
  /**
   * Accumulated model reasoning from the `message.channels["thought"]` side-channel, when the request
   * opted in via [RelaisRequest.enableThinking]. Null when thinking was off or the model emitted no
   * reasoning. Surfaced as OpenAI `reasoning_content`; NOT included in [completionTokens] (reasoning
   * tokens are decoded but are not visible-answer tokens).
   */
  val reasoning: String? = null,
)

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
    onReasoning: ((String) -> Unit)? = null,
  ): RelaisResult {
    RelaisMetrics.incInFlight() // counts both queued (waiting on lock) and running -> queue_depth
    val reqStartNs = System.nanoTime()
    try {
      val backend = BackendSelector.select(request.modalities, BackendSelector.aicoreAvailable(context))

      // NPU path (Pixel 10+): Gemini Nano via AICore, image/text only. UNVERIFIED on Pixel 9 —
      // never selected here because aicoreAvailable() is false.
      // completionTokens = 0: AICore does not expose per-token callbacks; usage block will show
      // completion_tokens = 0 for this path until a token-counting API is available.
      if (backend == RelaisBackend.NPU_AICORE) {
        val text = RelaisAicore.generate(request)
        onToken?.invoke(text)
        return RelaisResult(text = text, backend = backend, decodeTokensPerSec = 0.0, completionTokens = 0)
      }

      ensureInitialized(context)
      val e = engine ?: error("Engine not initialized")

      // Tool path: a request advertising tools OR carrying tool results round-trips through the
      // BLOCKING sendMessage API (not streaming) so reply.toolCalls is populated. Same lock +
      // thermal-cooldown + RelaisMetrics scaffolding as the streaming path below.
      if (request.tools.isNotEmpty() || request.toolResults.isNotEmpty()) {
        return generateWithTools(e, request, backend)
      }

      val contents = buildList {
        request.imagePng?.let { add(Content.ImageBytes(it)) }
        request.audioWav?.let { add(Content.AudioBytes(it)) }
        if (request.text.isNotBlank()) add(Content.Text(request.text))
      }

      synchronized(lock) {
        // Thermal cool-down spaces *actual* decode runs: applied under the lock, not per-request on
        // the worker pool, so concurrent requests don't all sleep in parallel and then serialize.
        ThermalGovernor.cooldownMs().takeIf { it > 0 }?.let { runCatching { Thread.sleep(it) } }
        // Seed the system prompt + prior history into the conversation at creation via
        // ConversationConfig (systemInstruction + initialMessages). LiteRT-LM prefills these as
        // context; only the live user message below triggers a decode — so a multi-turn request
        // costs ONE generation, not one per history turn. (The prior replaySend path ran a full
        // generate-and-discard per turn — ~22 s/turn; a 2-exchange history hit the per-turn timeout
        // and 500'd. Validated on rango / Tensor-G5 / E2B: system honored, history recalled, one decode.)
        val sampler = request.samplerConfig()
        val initialMessages = request.history.mapNotNull { it.toResidentMessage() }
        val conversationConfig =
          if (request.systemPrompt != null) {
            ConversationConfig(
              systemInstruction = Contents.of(request.systemPrompt),
              initialMessages = initialMessages,
              samplerConfig = sampler,
            )
          } else {
            ConversationConfig(initialMessages = initialMessages, samplerConfig = sampler)
          }
        val conversation = e.createConversation(conversationConfig)
        return try {
          // Stream so we can measure decode throughput by wall clock: BenchmarkInfo only populates
          // via the library's benchmark() path, not normal conversations (SPIKE-FINDINGS.md / Q1).
          val sb = StringBuilder()
          val reasoningSb = StringBuilder()
          var tokens = 0
          var firstTokenNs = 0L
          var lastTokenNs = 0L
          val canceled = AtomicBoolean(false)
          val latch = CountDownLatch(1)
          val error = arrayOfNulls<Throwable>(1)
          // Request the reasoning channel only when the client opted in (OpenAI reasoning_effort).
          // "enable_thinking" is the extraContext key the Gemma chat template routes its <think>
          // content through; the default (off) passes emptyMap() — byte-for-byte the prior behavior.
          val extraContext =
            if (request.enableThinking) mapOf("enable_thinking" to "true") else emptyMap()
          conversation.sendMessageAsync(
            Contents.of(contents),
            object : MessageCallback {
              override fun onMessage(message: Message) {
                val now = System.nanoTime()
                // Split this callback into its reasoning ("thinking") side-channel and its visible
                // answer delta. Reasoning is streamed + accumulated separately and is NEVER counted
                // as a completion token; only when thinking is off-or-absent does this collapse to
                // "emit the visible delta verbatim" (byte-for-byte the prior behavior).
                val thoughtDelta = if (request.enableThinking) message.channels["thought"] else null
                val step =
                  RelaisReasoning.classifyStreamDelta(request.enableThinking, message.toString(), thoughtDelta)

                step.reasoningToEmit?.let { reasoning ->
                  reasoningSb.append(reasoning)
                  if (!canceled.get()) {
                    try {
                      onReasoning?.invoke(reasoning)
                    } catch (t: Throwable) {
                      canceled.set(true)
                    }
                  }
                }

                val delta = step.visibleToEmit
                if (delta == null) {
                  // Reasoning-only callback: no visible token to count/emit. Still honor cooperative
                  // cancel so a long thinking phase can be thermally truncated / pipe-aborted.
                  if (shouldCancel?.invoke() == true) canceled.set(true)
                  return
                }

                // Decode-throughput clock advances on VISIBLE tokens only — reasoning callbacks must
                // not stretch the window (they would understate decode_tok_s fed to ThermalGovernor).
                if (firstTokenNs == 0L) firstTokenNs = now
                lastTokenNs = now
                tokens++
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
            extraContext,
          )
          if (!latch.await(120, TimeUnit.SECONDS)) error("inference timed out")
          error[0]?.let { throw it }
          val decodeSec = if (lastTokenNs > firstTokenNs) (lastTokenNs - firstTokenNs) / 1e9 else 0.0
          val tokS = if (decodeSec > 0 && tokens > 1) (tokens - 1) / decodeSec else 0.0
          RelaisMetrics.recordThroughput(tokens, tokS, backend.name)
          ThermalGovernor.onDecodeThroughput(tokS)
          RelaisResult(
            text = sb.toString(),
            backend = backend,
            decodeTokensPerSec = tokS,
            completionTokens = tokens,
            reasoning = reasoningSb.toString().takeIf { it.isNotEmpty() },
          )
        } finally {
          conversation.close()
        }
      }
    } finally {
      RelaisMetrics.recordLatency((System.nanoTime() - reqStartNs) / 1e9) // every outcome (HIGH-2)
      RelaisMetrics.decInFlight()
    }
  }

  /**
   * Blocking tool round-trip against the resident engine. Advertises [RelaisRequest.tools] to the
   * model (automaticToolCalling=false so the node — not the engine — runs tools), seeds system +
   * history, then sends ONE live message:
   *  - tool results present -> a TOOL message carrying each [Content.ToolResponse] (the round-trip
   *    where the model integrates tool output into a final answer);
   *  - otherwise -> the live USER message (the first turn, where the model may request a tool).
   *
   * Returns the reply text plus any [ParsedToolCall]s the model emitted. Uses the same lock +
   * thermal-cooldown discipline as the streaming path; runs no per-token callback (the blocking
   * sendMessage returns the full reply Message at once). Throughput is not measured here (no
   * per-token wall clock), so decodeTokensPerSec/completionTokens are 0.
   */
  @OptIn(ExperimentalApi::class)
  private fun generateWithTools(
    e: Engine,
    request: RelaisRequest,
    backend: RelaisBackend,
  ): RelaisResult {
    synchronized(lock) {
      ThermalGovernor.cooldownMs().takeIf { it > 0 }?.let { runCatching { Thread.sleep(it) } }
      val sampler = request.samplerConfig()
      val providers = request.tools.map { tool(openApiToolOf(it.functionJson)) }
      val initialMessages = request.history.mapNotNull { it.toResidentMessage() }
      val config =
        if (request.systemPrompt != null) {
          ConversationConfig(
            systemInstruction = Contents.of(request.systemPrompt),
            initialMessages = initialMessages,
            tools = providers,
            automaticToolCalling = false,
            samplerConfig = sampler,
          )
        } else {
          ConversationConfig(
            initialMessages = initialMessages,
            tools = providers,
            automaticToolCalling = false,
            samplerConfig = sampler,
          )
        }
      val conversation = e.createConversation(config)
      return try {
        val liveMessage =
          if (request.toolResults.isNotEmpty()) {
            Message.tool(Contents.of(request.toolResults.map { Content.ToolResponse(it.name, it.content) }))
          } else {
            val items = buildList {
              request.imagePng?.let { add(Content.ImageBytes(it)) }
              request.audioWav?.let { add(Content.AudioBytes(it)) }
              if (request.text.isNotBlank()) add(Content.Text(request.text))
            }
            Message.user(Contents.of(items))
          }
        val reply = conversation.sendMessage(liveMessage, emptyMap())
        val mapped = reply.toolCalls.mapIndexed { index, tc ->
          val argumentsJson = runCatching { JSONObject(tc.arguments).toString() }.getOrElse { ex ->
            Log.w(TAG, "Failed to serialize arguments for tool '${tc.name}'; using {}: $ex")
            "{}"
          }
          ParsedToolCall(
            id = "call_" + java.util.UUID.randomUUID().toString().replace("-", "").take(24),
            name = tc.name,
            argumentsJson = argumentsJson,
          )
        }
        val text = reply.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
        RelaisResult(
          text = text,
          backend = backend,
          decodeTokensPerSec = 0.0,
          completionTokens = 0,
          toolCalls = mapped,
        )
      } finally {
        conversation.close()
      }
    }
  }

  /**
   * Wraps an OpenAI `function` object ([functionJson]) as an [OpenApiTool] for the LiteRT-LM
   * `tool(...)` bridge. [OpenApiTool.execute] is only invoked when automaticToolCalling=true; the
   * node always runs with it OFF (tools execute client-side), so execute() should never fire — it
   * logs a warning and returns an empty object defensively.
   */
  private fun openApiToolOf(functionJson: String): OpenApiTool =
    object : OpenApiTool {
      override fun getToolDescriptionJsonString(): String = functionJson

      override fun execute(paramsJsonString: String): String {
        Log.w(TAG, "OpenApiTool.execute() called unexpectedly (automaticToolCalling is off); returning {}")
        return "{}"
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

  /**
   * Maps a parsed OpenAI history turn to a LiteRT-LM [Message] with the correct role, for seeding a
   * conversation via [ConversationConfig.initialMessages]. Roles map as:
   *  - "tool": a TOOL message carrying the result text as a [Content.ToolResponse] (null if the
   *    function name couldn't be resolved — a nameless tool response can't be addressed).
   *  - "assistant" with tool calls: a MODEL message carrying the calls (so the model sees its own
   *    prior tool requests); content is empty when the assistant turn had no text.
   *  - "assistant" (no calls): a MODEL message.
   *  - everything else: a USER message.
   * Returns null for an empty turn (no text and no media and no tool calls) so no blank turn seeds.
   */
  @OptIn(ExperimentalApi::class)
  private fun ParsedTurn.toResidentMessage(): Message? {
    if (role == "tool") {
      return toolName?.let { Message.tool(Contents.of(listOf(Content.ToolResponse(it, text)))) }
    }
    if (role == "assistant" && toolCalls.isNotEmpty()) {
      val contents = if (text.isBlank()) Contents.of(emptyList()) else Contents.of(text)
      return Message.model(contents, toolCalls.map { ToolCall(it.name, jsonToMap(it.argumentsJson)) }, emptyMap())
    }
    val items = buildList {
      imagePng?.let { add(Content.ImageBytes(it)) }
      audioWav?.let { add(Content.AudioBytes(it)) }
      if (text.isNotBlank()) add(Content.Text(text))
    }
    if (items.isEmpty()) return null
    val contents = Contents.of(items)
    return if (role == "assistant") Message.model(contents) else Message.user(contents)
  }

  /** Parses a JSON-object string into a `Map<String, Any?>` for [ToolCall.arguments]; {} on failure. */
  private fun jsonToMap(json: String): Map<String, Any?> =
    runCatching {
      val obj = JSONObject(json)
      buildMap {
        val keys = obj.keys()
        while (keys.hasNext()) {
          val key = keys.next()
          put(key, obj.get(key))
        }
      }
    }.getOrDefault(emptyMap())
}
