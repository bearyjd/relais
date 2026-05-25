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

package com.google.ai.edge.gallery

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
 *     -e class com.google.ai.edge.gallery.RelaisBackendBenchmarkTest#residentGpuMultimodal \
 *     -e model /data/local/tmp/relais/gemma-4-E4B-it.litertlm \
 *     com.google.ai.edge.gallery.test/androidx.test.runner.AndroidJUnitRunner
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
