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

package cc.grepon.relais.embed

import android.content.Context
import android.util.Log
import cc.grepon.relais.RelaisConfig
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.tflite.java.TfLite
import java.io.File
import java.io.RandomAccessFile
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.tensorflow.lite.DataType
import org.tensorflow.lite.InterpreterApi

/**
 * [RelaisEmbedder] backed by the on-device EmbeddingGemma `.tflite` (run through the GMS
 * `InterpreterApi`) plus the pure-Kotlin SentencePiece tokenizer. Produces native 768-dim,
 * L2-normalized vectors.
 *
 * The graph's exact I/O is NOT hard-assumed — it is INTROSPECTED at load time and adapted: the
 * token-ids input is located by name/dtype, an attention-mask input is fed if the graph has one, and
 * an already-pooled `[1, dim]` output is used directly while a per-token `[1, seqLen, dim]` output is
 * mean-pooled over the mask. Anything outside that space fails loud rather than emitting garbage.
 *
 * Readiness is demand-driven and OFF the request thread:
 *  - [isAvailable] is true only once the model is actually loaded in memory, so the HTTP request
 *    thread never blocks on the (one-time) ~180 MB download or the interpreter load.
 *  - The endpoint, when not ready, asks [canProvision]; if so it calls [ensureProvisioningStarted]
 *    (a single background download+load) and answers 503-retry. A device with no Google Play Services
 *    runtime, or no HF token for the gated model, reports [canProvision] = false → a stable 501.
 *  - [warmIfProvisioned] re-loads an already-downloaded model at node startup (no token, no download).
 *
 * Failure handling distinguishes TRANSIENT from INTEGRITY faults so a flaky connection can't brick
 * embeddings: a download/network failure is retried freely (uncounted), while a downloaded-but-
 * unusable model (corrupt, incompatible shape, missing runtime) is counted toward a small budget AND
 * its on-disk copy is dropped so the next attempt re-downloads fresh; the budget resets after a
 * cooldown so even a persistent integrity fault degrades to a stable 501 rather than a permanent wedge.
 *
 * The `InterpreterApi` is not thread-safe, so each inference run is serialized.
 */
class EmbeddingGemmaEmbedder : RelaisEmbedder {

  /** EmbeddingGemma's native hidden size; validated against the loaded graph's output. */
  override val dim: Int get() = DIM

  @Volatile private var loaded: Loaded? = null
  @Volatile private var integrityFailures: Int = 0
  @Volatile private var lastIntegrityFailureAtMs: Long = 0L
  @Volatile private var gmsState: Boolean = false // cached only once true (a positive result is durable)
  private val loadLock = Any()
  private val provisioning = AtomicBoolean(false)

  private class Loaded(
    val interpreter: InterpreterApi,
    val tokenizer: SentencePieceTokenizer,
    val spModel: SentencePieceModel,
    val seqLen: Int,
    val idsInputIndex: Int,
    val maskInputIndex: Int,
    val maskIsFloat: Boolean,
    val outputRank: Int,
    val outputSeqLen: Int,
  )

  /** Ready to embed right now — the model is loaded in memory (implies GMS + provisioned). */
  override fun isAvailable(context: Context): Boolean = loaded != null

  /**
   * Whether a background provision+load could plausibly succeed: a GMS LiteRT runtime is present, an
   * HF token is set (the model is license-gated), and the integrity-failure budget isn't exhausted.
   * The endpoint uses this to decide 503-retry (provisioning) vs a stable 501 (genuinely unavailable).
   */
  fun canProvision(context: Context): Boolean =
    !integrityBudgetExhausted() &&
      gmsAvailable(context) &&
      !RelaisConfig.hfToken(context).isNullOrBlank()

  override fun embed(context: Context, texts: List<String>): List<FloatArray> =
    embed(context, texts, EmbeddingTask.DEFAULT)

  /**
   * Embeds [texts] under an explicit [task] (query vs document prefix). The OpenAI-style endpoint
   * uses [EmbeddingTask.DEFAULT]; RAG embeds passages as DOCUMENT and searches with QUERY.
   */
  fun embed(context: Context, texts: List<String>, task: EmbeddingTask): List<FloatArray> {
    val l = ensureLoaded(context)
    return texts.map { text -> embedOne(l, task.apply(text)) }
  }

  override fun countTokens(texts: List<String>): Int {
    val tok = loaded?.tokenizer ?: return texts.sumOf { maxOf(1, it.length / 4) }
    return tok.countTokens(texts)
  }

  /**
   * Starts a single background download+load when the model isn't ready, IF [canProvision]. Called by
   * the endpoint on a request that arrives before the model is on disk — so the ~180 MB fetch happens
   * because embeddings were actually requested, not merely because a (shared, LLM) HF token exists.
   */
  fun ensureProvisioningStarted(context: Context) {
    val app = context.applicationContext
    if (loaded != null || !canProvision(app)) return
    startBackgroundLoad(app)
  }

  /**
   * Re-loads an ALREADY-downloaded model in the background at node startup (no token, no download), so
   * a restart serves embeddings without a first-request 503. No-op if the model isn't on disk.
   */
  fun warmIfProvisioned(context: Context) {
    val app = context.applicationContext
    if (loaded != null || integrityBudgetExhausted()) return
    if (!gmsAvailable(app) || !EmbeddingModelProvisioner.isProvisioned(app)) return
    startBackgroundLoad(app)
  }

  private fun startBackgroundLoad(app: Context) {
    if (!provisioning.compareAndSet(false, true)) return // one at a time
    thread(name = "relais-embed-load") {
      try {
        ensureLoaded(app)
      } catch (e: Exception) {
        Log.e(TAG, "background embedder load failed", e)
      } finally {
        provisioning.set(false)
      }
    }
  }

  private fun embedOne(l: Loaded, promptedText: String): FloatArray {
    val contentIds = l.tokenizer.encode(promptedText)
    val input = buildEmbeddingInput(contentIds, l.seqLen, l.spModel.bosId, l.spModel.eosId, l.spModel.padId)
    val pooled = runInterpreter(l, input)
    return l2Normalize(pooled)
  }

  private fun runInterpreter(l: Loaded, input: EmbeddingModelInput): FloatArray {
    synchronized(l.interpreter) {
      val inputs = arrayOfNulls<Any>(l.interpreter.inputTensorCount)
      inputs[l.idsInputIndex] = arrayOf(input.ids) // [1, seqLen] int32
      if (l.maskInputIndex >= 0) {
        inputs[l.maskInputIndex] =
          if (l.maskIsFloat) arrayOf(FloatArray(input.mask.size) { input.mask[it].toFloat() })
          else arrayOf(input.mask)
      }
      @Suppress("UNCHECKED_CAST")
      val inputArray = inputs as Array<Any>

      return if (l.outputRank == 2) {
        val out = Array(1) { FloatArray(DIM) }
        l.interpreter.runForMultipleInputsOutputs(inputArray, mapOf(0 to out))
        out[0]
      } else {
        val out = Array(1) { Array(l.outputSeqLen) { FloatArray(DIM) } }
        l.interpreter.runForMultipleInputsOutputs(inputArray, mapOf(0 to out))
        val flat = FloatArray(l.outputSeqLen * DIM)
        for (t in 0 until l.outputSeqLen) {
          System.arraycopy(out[0][t], 0, flat, t * DIM, DIM)
        }
        meanPoolMasked(flat, input.mask, DIM)
      }
    }
  }

  private fun ensureLoaded(context: Context): Loaded {
    loaded?.let { return it }
    synchronized(loadLock) {
      loaded?.let { return it }
      val l = load(context)
      loaded = l
      integrityFailures = 0 // a clean load clears the integrity budget
      return l
    }
  }

  private fun load(context: Context): Loaded {
    // Download phase. A network/HTTP failure here is TRANSIENT — it is NOT counted toward the integrity
    // budget (so a flaky connection never permanently disables embeddings) and the provisioner finalizes
    // atomically (no poisoned partial). The next request simply retries.
    val assets = EmbeddingModelProvisioner.ensure(context)
    // GMS runtime init is gated by the gmsAvailable() preflight; a (rare) failure here is also treated
    // as transient and does not drop the model.
    Tasks.await(TfLite.initialize(context))
    // Build phase. A failure now means the on-disk bytes are present but UNUSABLE (corrupt-but-right-
    // size model, incompatible graph shape): drop the on-disk copy so the next attempt re-downloads
    // fresh, and count it toward the bounded, cooldown-reset integrity budget.
    return try {
      buildLoaded(assets)
    } catch (e: Exception) {
      integrityFailures++
      lastIntegrityFailureAtMs = System.currentTimeMillis()
      runCatching { EmbeddingModelProvisioner.clearModel(context) }
      Log.e(
        TAG,
        "embedder build failed on a downloaded model (integrity $integrityFailures/$MAX_INTEGRITY_FAILURES); dropped the on-disk copy",
        e,
      )
      throw e
    }
  }

  private fun buildLoaded(assets: EmbeddingAssets): Loaded {
    val options = InterpreterApi.Options().setRuntime(InterpreterApi.Options.TfLiteRuntime.FROM_SYSTEM_ONLY)
    val interpreter = InterpreterApi.create(mapModel(assets.modelFile), options)

    // --- introspect inputs ---
    val inCount = interpreter.inputTensorCount
    require(inCount in 1..2) { "unexpected embedding input count: $inCount" }
    var idsIndex = -1
    var maskIndex = -1
    var maskIsFloat = false
    var seqLen = -1
    for (i in 0 until inCount) {
      val t = interpreter.getInputTensor(i)
      val name = (t.name() ?: "").lowercase()
      val last = t.shape().lastOrNull() ?: -1
      if (name.contains("mask")) {
        maskIndex = i
        maskIsFloat = t.dataType() == DataType.FLOAT32
      } else {
        idsIndex = i
        seqLen = last
        require(t.dataType() == DataType.INT32) {
          "ids input must be INT32, was ${t.dataType()} (name='${t.name()}')"
        }
      }
    }
    if (idsIndex == -1) { // both inputs name-matched "mask" (shouldn't happen) — fall back to index 0
      idsIndex = 0; maskIndex = if (inCount == 2) 1 else -1
      seqLen = interpreter.getInputTensor(0).shape().lastOrNull() ?: -1
    } else if (inCount == 2 && maskIndex == -1) {
      maskIndex = if (idsIndex == 0) 1 else 0
      maskIsFloat = interpreter.getInputTensor(maskIndex).dataType() == DataType.FLOAT32
    }
    require(seqLen >= 2) { "introspected seqLen $seqLen is invalid" }
    require(assets.tokenizerFile.exists()) { "tokenizer file missing" }

    // --- introspect output ---
    val outT = interpreter.getOutputTensor(0)
    val outShape = outT.shape()
    val outRank = outShape.size
    require(outRank == 2 || outRank == 3) { "unexpected output rank $outRank (shape ${outShape.toList()})" }
    val outDim = outShape.last()
    require(outDim == DIM) { "unexpected embedding dim $outDim, expected $DIM (shape ${outShape.toList()})" }
    require(outT.dataType() == DataType.FLOAT32) { "output must be FLOAT32, was ${outT.dataType()}" }
    val outSeqLen = if (outRank == 3) outShape[outShape.size - 2] else 1
    if (outRank == 3) {
      require(outSeqLen == seqLen) { "per-token output seqLen $outSeqLen != input seqLen $seqLen" }
    }

    val spModel = SentencePieceModel.parse(assets.tokenizerFile.readBytes())
    require(spModel.padId >= 0) { "tokenizer has no pad id (${spModel.padId}); cannot pad the input" }
    val tokenizer = SentencePieceTokenizer(spModel)

    Log.i(
      TAG,
      "loaded embedder: inputs=$inCount idsIdx=$idsIndex maskIdx=$maskIndex maskFloat=$maskIsFloat " +
        "seqLen=$seqLen outRank=$outRank outDim=$outDim bos=${spModel.bosId} eos=${spModel.eosId} pad=${spModel.padId}",
    )
    return Loaded(
      interpreter = interpreter,
      tokenizer = tokenizer,
      spModel = spModel,
      seqLen = seqLen,
      idsInputIndex = idsIndex,
      maskInputIndex = maskIndex,
      maskIsFloat = maskIsFloat,
      outputRank = outRank,
      outputSeqLen = outSeqLen,
    )
  }

  /**
   * True once repeated INTEGRITY failures (corrupt/incompatible model) exhaust the bounded budget —
   * until a cooldown elapses, after which a fresh round of attempts is allowed. Transient download
   * failures never reach here, so a flaky network can't trip it.
   */
  private fun integrityBudgetExhausted(): Boolean {
    if (integrityFailures < MAX_INTEGRITY_FAILURES) return false
    if (System.currentTimeMillis() - lastIntegrityFailureAtMs > INTEGRITY_COOLDOWN_MS) {
      integrityFailures = 0
      return false
    }
    return true
  }

  /** True iff a Google Play Services LiteRT runtime is present. A positive result is cached (durable);
   *  a negative is re-probed each call (it may be transient — e.g. GMS mid-update). */
  private fun gmsAvailable(context: Context): Boolean {
    if (gmsState) return true
    val ok = runCatching {
      GoogleApiAvailability.getInstance()
        .isGooglePlayServicesAvailable(context.applicationContext) == ConnectionResult.SUCCESS
    }.getOrDefault(false)
    if (ok) gmsState = true
    return ok
  }

  private fun mapModel(file: File): MappedByteBuffer =
    RandomAccessFile(file, "r").use { raf ->
      // The mapping outlives the closed channel (standard TFLite pattern); reclaimed by the cleaner.
      raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.channel.size())
    }

  companion object {
    private const val TAG = "RelaisEmbedder"

    /** EmbeddingGemma-300M native embedding dimensionality. */
    const val DIM = 768

    /** Consecutive INTEGRITY failures before backing off; transient download failures don't count. */
    private const val MAX_INTEGRITY_FAILURES = 3

    /** After this long since the last integrity failure, allow a fresh round (no permanent wedge). */
    private const val INTEGRITY_COOLDOWN_MS = 5 * 60_000L

    /** Process-wide singleton registered into [RelaisEmbedderProvider]. */
    val INSTANCE: EmbeddingGemmaEmbedder by lazy { EmbeddingGemmaEmbedder() }

    /**
     * Registers [INSTANCE] so the endpoint can ask it for availability/provisioning. Cheap and
     * idempotent — does NOT download or load (that is demand-driven / [warmIfProvisioned]).
     */
    fun register() {
      RelaisEmbedderProvider.register(INSTANCE)
    }
  }
}
