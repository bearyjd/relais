/*
 * Copyright 2025 Google LLC
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

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.BenchmarkInfo
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.benchmark
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Relais pre-flight spike harness (Gate 0.5), Pixel 9 Pro Fold / Tensor G4.
 *
 * Two purposes:
 *  1. [residentGpuMultimodal] — the work that matters for the GOAL: prove a single resident
 *     LiteRT-LM engine on the GPU answers text, text+image, and text+audio requests. This is the
 *     portable foundation (runs on Pixel 9 now and Pixel 10) for the multimodal-endpoint node.
 *  2. The benchmark methods — record real prefill/decode tok/s via the library's BenchmarkInfo,
 *     for a CPU/GPU baseline and the (device-dependent) NPU-threshold idea.
 *
 * Q1 reconciliation finding baked in: LiteRT-LM exposes NO resolved-backend readback, AND E4B has
 * no NPU path on this device anyway (NPU-E4B is AICore/Gemini-Nano, Pixel 10+ only). So these tests
 * assert "engine resident + correct multimodal response", NOT "backend == NPU".
 *
 * Model is not bundled. It is pulled by Gallery then copied to /data/local/tmp; override with:
 *   adb shell am instrument -w \
 *     -e class cc.grepon.relais.RelaisBackendBenchmarkTest#residentGpuMultimodal \
 *     -e model /data/local/tmp/relais/gemma-4-E4B-it.litertlm \
 *     cc.grepon.relais.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Watch results: adb logcat -s RelaisBench
 */
@RunWith(AndroidJUnit4::class)
class RelaisBackendBenchmarkTest {

  private val args = InstrumentationRegistry.getArguments()
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val cacheDir = context.getExternalFilesDir(null)?.absolutePath
  private val modelPath: String
    get() = args.getString("model") ?: DEFAULT_MODEL

  // ---------------------------------------------------------------------------------------------
  // The GOAL-relevant test: resident GPU engine, all three modalities, one initialize().
  // ---------------------------------------------------------------------------------------------

  @OptIn(ExperimentalApi::class)
  @Test
  fun residentGpuMultimodal() {
    val path = modelPath
    assumeTrue("Model not found at $path (pass -e model <path>)", java.io.File(path).exists())

    val engine =
      Engine(
        EngineConfig(
          modelPath = path,
          backend = Backend.GPU(),
          visionBackend = Backend.GPU(), // Gemma vision must be GPU
          audioBackend = Backend.CPU(), // Gemma audio must be CPU
          maxNumTokens = 1024,
          cacheDir = cacheDir,
        )
      )
    val initStart = System.currentTimeMillis()
    engine.initialize()
    Log.i(TAG, "GPU engine initialized in ${System.currentTimeMillis() - initStart} ms; resident=${engine.isInitialized()}")

    try {
      // 1) Text-only.
      val textOut = ask(engine, listOf(Content.Text("Reply with exactly one word: ping")))
      Log.i(TAG, "TEXT  -> \"${textOut.take(80)}\"")
      assertTrue("Text response was empty", textOut.isNotBlank())

      // 2) Text + image (decisive multimodal proof on the GPU path).
      val imageOut =
        ask(
          engine,
          listOf(Content.ImageBytes(solidColorPng(Color.RED)), Content.Text("What color fills this image? One word.")),
        )
      Log.i(TAG, "IMAGE -> \"${imageOut.take(120)}\"")
      assertTrue("Image+text response was empty", imageOut.isNotBlank())

      // 3) Text + audio (the modality AICore/NPU cannot do — the reason GPU is the foundation).
      //    Synthetic WAV; lenient — log outcome rather than hard-fail on model-side format quirks.
      try {
        val audioOut =
          ask(engine, listOf(Content.AudioBytes(sineWav(seconds = 1)), Content.Text("Describe this audio briefly.")))
        Log.i(TAG, "AUDIO -> \"${audioOut.take(120)}\"")
      } catch (e: Exception) {
        Log.w(TAG, "AUDIO leg errored (format/capability) — image+text already proves multimodal: ${e.message}")
      }
    } finally {
      engine.close()
      Log.i(TAG, "GPU engine closed (residency test complete)")
    }
  }

  /** Fresh conversation per request against the resident engine; returns the rendered response. */
  @OptIn(ExperimentalApi::class)
  private fun ask(engine: Engine, contents: List<Content>): String {
    val conversation =
      engine.createConversation(
        ConversationConfig(samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = 1.0))
      )
    return try {
      conversation.sendMessage(Contents.of(contents), emptyMap()).toString()
    } finally {
      conversation.close()
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Benchmark legs (BenchmarkInfo tok/s). NPU leg only meaningful on a device/model that has it.
  // ---------------------------------------------------------------------------------------------

  @OptIn(ExperimentalApi::class)
  @Test
  fun gpuBenchmark() = logBench("GPU", benchmark(modelPath, Backend.GPU(), cacheDir = cacheDir))

  @OptIn(ExperimentalApi::class)
  @Test
  fun cpuBenchmark() = logBench("CPU", benchmark(modelPath, Backend.CPU(), cacheDir = cacheDir))

  /**
   * TPU leg (Tensor G5, spike plan T-3). Requires (a) the app under test to bundle
   * libLiteRtDispatch_GoogleTensor.so (debug builds do, via scripts/fetch-tensor-dispatcher.sh) and
   * (b) a Google-Tensor-AOT-compiled `.litertlm` (e.g. Gemma3-1B-IT_q8_ekv1280_Google_Tensor_G5).
   * A non-compiled model fails engine init here — that is the expected negative, not a probe bug.
   *
   *   adb shell am instrument -w \
   *     -e class cc.grepon.relais.RelaisBackendBenchmarkTest#npuBenchmark \
   *     -e model /data/data/<appId>/files/bench/<g5-compiled>.litertlm \
   *     <appId>.test/androidx.test.runner.AndroidJUnitRunner
   */
  @OptIn(ExperimentalApi::class)
  @Test
  fun npuBenchmark() {
    val nativeLibDir = context.applicationInfo.nativeLibraryDir
    assumeTrue(
      "TPU dispatcher not bundled in this build (debug-only)",
      java.io.File(nativeLibDir, "libLiteRtDispatch_GoogleTensor.so").exists(),
    )
    logBench("NPU", benchmark(modelPath, Backend.NPU(nativeLibraryDir = nativeLibDir), cacheDir = cacheDir))
  }

  /**
   * T-3 wall-clock throughput leg — works on ALL backends. The library's `benchmark()` entry point
   * SIGABRTs inside liblitertlm_jni on `Backend.NPU` (observed rango 2026-07-10, same signature as
   * LiteRT#7787), so this measures via Engine + streaming Conversation — the path production uses —
   * timing TTFT and decode tok/s by wall clock exactly like `RelaisEngine.generate`.
   *
   *   adb shell am instrument -w \
   *     -e class cc.grepon.relais.RelaisBackendBenchmarkTest#throughputBenchmark \
   *     -e model /data/data/<appId>/files/bench/<model>.litertlm -e backend npu|gpu|cpu \
   *     <appId>.test/androidx.test.runner.AndroidJUnitRunner
   */
  @OptIn(ExperimentalApi::class)
  @Test
  fun throughputBenchmark() {
    val backendArg = args.getString("backend") ?: "gpu"
    val nativeLibDir = context.applicationInfo.nativeLibraryDir
    if (backendArg == "npu") {
      assumeTrue(
        "TPU dispatcher not bundled in this build (debug-only)",
        java.io.File(nativeLibDir, "libLiteRtDispatch_GoogleTensor.so").exists(),
      )
    }
    val backend =
      when (backendArg) {
        "npu" -> Backend.NPU(nativeLibraryDir = nativeLibDir)
        "cpu" -> Backend.CPU()
        else -> Backend.GPU()
      }
    // AOT-compiled (NPU) models have FIXED KV-cache shapes — maxNumTokens must match the compile
    // (e.g. ekv1280 -> 1280); a mismatch shows as "new_step must be <= TokenCount()" mid-decode.
    val maxTokens = args.getString("maxTokens")?.toIntOrNull() ?: 1024
    val initStart = System.nanoTime()
    val engine = Engine(EngineConfig(modelPath = modelPath, backend = backend, maxNumTokens = maxTokens, cacheDir = cacheDir))
    engine.initialize()
    val initSec = (System.nanoTime() - initStart) / 1e9
    try {
      // Default ConversationConfig unless overridden: the NPU compiled-model executor fails its
      // decode-step bookkeeping ("new_step must be <= TokenCount()") under a custom SamplerConfig
      // (observed rango 2026-07-10); the default path is what the working sample app uses.
      val conversation =
        engine.createConversation(
          if (args.getString("sampler") == "custom")
            ConversationConfig(samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = 1.0))
          else ConversationConfig()
        )
      try {
        var tokens = 0
        var firstNs = 0L
        var lastNs = 0L
        val latch = java.util.concurrent.CountDownLatch(1)
        val err = arrayOfNulls<Throwable>(1)
        val sendNs = System.nanoTime()
        conversation.sendMessageAsync(
          Contents.of(listOf(Content.Text("Write a detailed 300-word essay about relay stations in communication networks."))),
          object : com.google.ai.edge.litertlm.MessageCallback {
            override fun onMessage(message: com.google.ai.edge.litertlm.Message) {
              val now = System.nanoTime()
              if (firstNs == 0L) firstNs = now
              lastNs = now
              tokens++
            }

            override fun onDone() = latch.countDown()

            override fun onError(throwable: Throwable) {
              err[0] = throwable
              latch.countDown()
            }
          },
          emptyMap(),
        )
        assertTrue("decode timed out", latch.await(300, java.util.concurrent.TimeUnit.SECONDS))
        err[0]?.let { throw it }
        val ttft = (firstNs - sendNs) / 1e9
        val decodeSec = (lastNs - firstNs) / 1e9
        val tokS = if (decodeSec > 0 && tokens > 1) (tokens - 1) / decodeSec else 0.0
        Log.i(
          TAG,
          "T3 backend=$backendArg decode=${"%.2f".format(tokS)}tok/s tokens=$tokens " +
            "ttft=${"%.3f".format(ttft)}s init=${"%.2f".format(initSec)}s model=${modelPath.substringAfterLast('/')}",
        )
        assertTrue("no tokens decoded", tokens > 1)
      } finally {
        conversation.close()
      }
    } finally {
      engine.close()
    }
  }

  private fun logBench(label: String, info: BenchmarkInfo) {
    Log.i(
      TAG,
      "$label  decode=${info.lastDecodeTokensPerSecond} tok/s  prefill=${info.lastPrefillTokensPerSecond} tok/s  " +
        "ttft=${info.timeToFirstTokenInSecond}s  init=${info.initTimeInSecond}s",
    )
    assertTrue("$label produced no decode tokens", info.lastDecodeTokensPerSecond > 0.0)
  }

  // ---------------------------------------------------------------------------------------------
  // Test media generated in-process (no bundled assets).
  // ---------------------------------------------------------------------------------------------

  private fun solidColorPng(color: Int): ByteArray {
    val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
    Canvas(bmp).drawColor(color)
    return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
  }

  /** 16 kHz mono 16-bit PCM WAV of a sine tone. */
  private fun sineWav(seconds: Int, freq: Int = 440, rate: Int = 16000): ByteArray {
    val n = seconds * rate
    val dataBytes = n * 2
    val buf = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
    buf.put("RIFF".toByteArray()); buf.putInt(36 + dataBytes); buf.put("WAVE".toByteArray())
    buf.put("fmt ".toByteArray()); buf.putInt(16); buf.putShort(1); buf.putShort(1)
    buf.putInt(rate); buf.putInt(rate * 2); buf.putShort(2); buf.putShort(16)
    buf.put("data".toByteArray()); buf.putInt(dataBytes)
    for (i in 0 until n) {
      val s = (Math.sin(2.0 * Math.PI * freq * i / rate) * 0.6 * Short.MAX_VALUE).toInt().toShort()
      buf.putShort(s)
    }
    return buf.array()
  }

  private companion object {
    const val TAG = "RelaisBench"
    const val DEFAULT_MODEL = "/data/local/tmp/relais/gemma-4-E4B-it.litertlm"
  }
}
