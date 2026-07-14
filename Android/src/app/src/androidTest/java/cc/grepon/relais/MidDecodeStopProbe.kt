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

package cc.grepon.relais

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device verification for issue #125 — does the litertlm AAR expose a REAL mid-decode cancel?
 *
 * Static inspection of litertlm-android 0.12.0 (2026-07-14, `javap` on `classes.jar`) established that
 * the API surface exists:
 *   - `Conversation.cancelProcess()` -> `LiteRtLmJni.nativeConversationCancelProcess(handle)`
 *   - `Session.cancelProcess()`      -> `LiteRtLmJni.nativeCancelProcess(handle)`
 * and the bundled native `.so` carries `LiteRtSetCompiledModelCancellationFunction`,
 * "Client requested cancel during Invoke()", and `kLiteRtStatusCancelled` — i.e. a cooperative cancel
 * checked mid-`Invoke()` at the compiled-model level. So `RelaisEngine`'s "true mid-decode native stop
 * is a TODO" was stale w.r.t. the API. What static inspection CANNOT answer, and this probe does:
 *
 *   Does calling `cancelProcess()` during a long stream actually stop token callbacks PROMPTLY
 *   (within ~1 token-interval), and does the stream then terminate via `onDone`/`onError`?
 *
 * Design note (important): `cancelProcess()` is invoked from a SEPARATE watcher thread, never from
 * inside `onMessage`. `onMessage` runs on the native decode/callback thread; issuing the cancel from
 * that same thread would be reentrant against the in-flight `Invoke()`. The watcher spins until the
 * stream has produced `CANCEL_AFTER_TOKENS` callbacks, then stamps `cancelReqNs` and cancels.
 *
 * Evidence recorded either way (this task "cannot end in probably"):
 *   - tokensBeforeCancel / tokensAfterCancel  (how many callbacks slipped through post-cancel)
 *   - stopLatencyMs = last-callback-time - cancel-request-time
 *   - terminal = onDone | onError:<msg> | timeout
 * PASS shape: tokensAfterCancel small (a handful, in-flight slop) AND stopLatencyMs well under the
 * time it would take to reach maxNumTokens. FAIL/ABSENT shape: callbacks continue to maxNumTokens
 * regardless of the cancel — record that verdict in docs/litertlm-native-api.md.
 *
 * Backends: `-e backend gpu` (default) runs `Backend.GPU()` with a custom sampler; `-e backend npu`
 * runs the Tensor-G5 TPU lane — `Backend.NPU(nativeLibraryDir)` against a `…_Google_Tensor_G5`
 * AOT model, with the ENGINE-DEFAULT sampler (a custom `SamplerConfig` crashes the NPU compiled-model
 * executor mid-decode, "new_step must be <= TokenCount()" — see `RelaisTpuLane`). The TPU lane is the
 * always-on serving default, so it is the path where a real cancel matters most.
 *
 * Run (rango / Pixel 10 / G5 — adjust -e model to the staged path):
 *   # GPU path, generic model:
 *   adb shell am instrument -w -e class cc.grepon.relais.MidDecodeStopProbe \
 *     -e model /storage/emulated/0/Android/data/<pkg>/files/bench/gemma-4-E4B-it.litertlm \
 *     <pkg>.test/androidx.test.runner.AndroidJUnitRunner
 *   # TPU/NPU path, G5-AOT model:
 *   adb shell am instrument -w -e class cc.grepon.relais.MidDecodeStopProbe -e backend npu \
 *     -e model /storage/emulated/0/Android/data/<pkg>/files/bench/gemma-4-E2B-it_Google_Tensor_G5.litertlm \
 *     <pkg>.test/androidx.test.runner.AndroidJUnitRunner
 * Watch: adb logcat -s RelaisStopProbe
 */
@RunWith(AndroidJUnit4::class)
class MidDecodeStopProbe {

  private val args = InstrumentationRegistry.getArguments()
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val cacheDir = context.getExternalFilesDir(null)?.absolutePath
  private val nativeLibDir = context.applicationInfo.nativeLibraryDir
  private val useNpu: Boolean
    get() = args.getString("backend")?.equals("npu", ignoreCase = true) == true
  private val modelPath: String
    get() = args.getString("model") ?: DEFAULT_MODEL

  private data class StopOutcome(
    val tokensBeforeCancel: Int,
    val tokensAfterCancel: Int,
    val stopLatencyMs: Long,
    val meanTokenIntervalMs: Long,
    val cancelIssued: Boolean,
    val completed: Boolean,
    val error: String?,
  )

  @OptIn(ExperimentalApi::class)
  @Test
  fun probeMidDecodeStop() {
    val path = modelPath
    assumeTrue("Model not found at $path (pass -e model <path>)", File(path).exists())

    val backend = if (useNpu) Backend.NPU(nativeLibraryDir = nativeLibDir) else Backend.GPU()
    // AOT G5 models carry a fixed KV size; the `…_Google_Tensor_G5` E2B build has no `ekvNNNN` marker
    // so it uses the engine default (4096), matching RelaisEngine's TPU lane. GPU: a generous bound so
    // a real cancel halts well BEFORE it is reached (else cancel ~ ran-to-completion).
    val maxTokens = if (useNpu) 4096 else 1024
    val initStart = System.currentTimeMillis()
    val engine =
      Engine(
        EngineConfig(
          modelPath = path,
          backend = backend,
          visionBackend = if (useNpu) Backend.NPU(nativeLibraryDir = nativeLibDir) else Backend.GPU(),
          audioBackend = Backend.CPU(),
          maxNumTokens = maxTokens,
          cacheDir = cacheDir,
        )
      )
    engine.initialize()
    Log.i(
      TAG,
      "engine ready in ${System.currentTimeMillis() - initStart} ms; backend=${if (useNpu) "NPU" else "GPU"} resident=${engine.isInitialized()}",
    )

    // A prompt that reliably produces a long, steady stream so there is a decode to interrupt.
    val prompt =
      "Write a long, detailed story about a lighthouse keeper. Include many paragraphs of vivid " +
        "description. Do not stop until you have written at least a thousand words."

    try {
      logOutcome(streamAndCancel(engine, prompt))
    } finally {
      engine.close()
      Log.i(TAG, "engine closed")
    }
  }

  /** Stream a long turn, cancel mid-decode from a watcher thread, and time how fast tokens stop. */
  @OptIn(ExperimentalApi::class)
  private fun streamAndCancel(engine: Engine, prompt: String): StopOutcome {
    // NPU compiled-model executor crashes mid-decode under a custom SamplerConfig (RelaisTpuLane);
    // the TPU lane must run the engine-default sampler. GPU can take an explicit one.
    val conv =
      engine.createConversation(
        if (useNpu) ConversationConfig()
        else ConversationConfig(samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = 0.8))
      )

    val tokenCount = AtomicInteger(0)
    val lastTokenNs = AtomicLong(0L)
    val firstTokenNs = AtomicLong(0L)
    val cancelReqNs = AtomicLong(0L)
    val tokensAtCancel = AtomicInteger(-1)
    val latch = CountDownLatch(1)
    val err = arrayOfNulls<Throwable>(1)
    var completed = false

    // Watcher: waits for the stream to warm up, then cancels ONCE from off the callback thread.
    val watcher =
      Thread {
        try {
          val deadline = System.nanoTime() + WATCH_TIMEOUT_SEC * 1_000_000_000L
          while (tokenCount.get() < CANCEL_AFTER_TOKENS && latch.count > 0L) {
            if (System.nanoTime() > deadline) {
              Log.w(TAG, "watcher: stream never reached $CANCEL_AFTER_TOKENS tokens; not cancelling")
              return@Thread
            }
            Thread.sleep(2)
          }
          if (latch.count > 0L) {
            tokensAtCancel.set(tokenCount.get())
            cancelReqNs.set(System.nanoTime())
            Log.i(TAG, "watcher: issuing cancelProcess() after ${tokensAtCancel.get()} tokens")
            runCatching { conv.cancelProcess() }
              .onFailure { Log.e(TAG, "watcher: cancelProcess() threw: ${it.message}") }
          }
        } catch (t: Throwable) {
          Log.e(TAG, "watcher error: ${t.message}")
        }
      }

    try {
      watcher.start()
      conv.sendMessageAsync(
        Contents.of(listOf(Content.Text(prompt))),
        object : MessageCallback {
          override fun onMessage(message: Message) {
            val now = System.nanoTime()
            firstTokenNs.compareAndSet(0L, now)
            lastTokenNs.set(now)
            tokenCount.incrementAndGet()
          }

          override fun onDone() {
            completed = true
            latch.countDown()
          }

          override fun onError(throwable: Throwable) {
            err[0] = throwable
            latch.countDown()
          }
        },
        emptyMap(),
      )
      if (!latch.await(WATCH_TIMEOUT_SEC + 30, TimeUnit.SECONDS)) {
        err[0] = IllegalStateException("wait elapsed (no onDone after cancel)")
      }
    } catch (t: Throwable) {
      err[0] = t
    } finally {
      runCatching { watcher.join(2_000) }
      runCatching { conv.close() }
    }

    val total = tokenCount.get()
    val atCancel = tokensAtCancel.get()
    val cancelNs = cancelReqNs.get()
    val stopLatencyMs = if (cancelNs > 0L) (lastTokenNs.get() - cancelNs) / 1_000_000L else -1L
    val meanIntervalMs =
      if (total > 1 && firstTokenNs.get() > 0L) {
        (lastTokenNs.get() - firstTokenNs.get()) / 1_000_000L / (total - 1).coerceAtLeast(1)
      } else {
        -1L
      }
    return StopOutcome(
      tokensBeforeCancel = if (atCancel >= 0) atCancel else total,
      tokensAfterCancel = if (atCancel >= 0) (total - atCancel).coerceAtLeast(0) else 0,
      stopLatencyMs = stopLatencyMs,
      meanTokenIntervalMs = meanIntervalMs,
      cancelIssued = cancelNs > 0L,
      completed = completed,
      error = err[0]?.message,
    )
  }

  private fun logOutcome(o: StopOutcome) {
    Log.i(
      TAG,
      "RESULT cancelIssued=${o.cancelIssued} tokensBeforeCancel=${o.tokensBeforeCancel} " +
        "tokensAfterCancel=${o.tokensAfterCancel} stopLatencyMs=${o.stopLatencyMs} " +
        "meanTokenIntervalMs=${o.meanTokenIntervalMs} terminal=${o.error ?: if (o.completed) "onDone" else "none"}",
    )
    val verdict =
      when {
        !o.cancelIssued -> "INCONCLUSIVE (cancel never issued — stream too short/slow)"
        o.tokensAfterCancel <= MAX_INFLIGHT_SLOP && o.stopLatencyMs in 0..MAX_STOP_LATENCY_MS ->
          "PASS (native mid-decode cancel halts decode promptly)"
        else -> "FAIL/ABSENT (callbacks continued past cancel — record as unsupported in the AAR)"
      }
    Log.i(TAG, "VERDICT: $verdict")
  }

  private companion object {
    const val TAG = "RelaisStopProbe"
    const val DEFAULT_MODEL = "/data/local/tmp/relais/gemma-4-E2B-it.litertlm"
    const val CANCEL_AFTER_TOKENS = 24
    const val WATCH_TIMEOUT_SEC = 120L
    // A prompt-bound decode should keep producing tokens for hundreds of callbacks; a real cancel
    // lets only a few in-flight tokens slip through and stops within a couple token-intervals.
    const val MAX_INFLIGHT_SLOP = 8
    const val MAX_STOP_LATENCY_MS = 1_500L
  }
}
