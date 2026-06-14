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
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device verification for the reasoning-channel feature (feature-10a / OpenAI `reasoning_content`).
 *
 * Proves the native mechanism Relais relies on: the bundled Gemma-4 E2B emits a SEPARABLE reasoning
 * stream on `message.channels["thought"]`, gated by `extraContext["enable_thinking"]="true"`, with the
 * visible answer left clean. This is what `RelaisEngine.generate` captures into
 * `RelaisResult.reasoning` when `RelaisRequest.enableThinking` is set (from `reasoning_effort`).
 *
 *  - Leg A1 (enable_thinking=true): `channels["thought"]` is non-empty and visible text has no
 *    `<think>` leakage.
 *  - Leg A2 (baseline emptyMap = thinking off): no `thought` channel — today's default behavior.
 *
 * (Benchmark-on-the-conversation-path was probed here too and proven a DEAD END in litertlm 0.11.0:
 * no public BenchmarkParams/EngineSettings, so `Conversation.getBenchmarkInfo()` throws
 * "Benchmark is not enabled." That leg was removed.)
 *
 * Run (rango / Pixel 10 / G5, E2B staged):
 *   adb -s 57211FDCG0023C shell am instrument -w -e class cc.grepon.relais.ReasoningChannelProbe \
 *     -e model /storage/emulated/0/Android/data/cc.grepon.relais/files/litert_community_gemma_4_E2B_it_litert_lm/361a4010ad6d88fc5c86e148e333c0342b99763d/gemma-4-E2B-it.litertlm \
 *     cc.grepon.relais.test/androidx.test.runner.AndroidJUnitRunner
 * Watch: adb logcat -s RelaisRCProbe
 */
@RunWith(AndroidJUnit4::class)
class ReasoningChannelProbe {

  private val args = InstrumentationRegistry.getArguments()
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val cacheDir = context.getExternalFilesDir(null)?.absolutePath
  private val modelPath: String
    get() = args.getString("model") ?: DEFAULT_MODEL

  private data class StreamOutcome(
    val visible: String,
    val reasoning: String,
    val tokenCallbacks: Int,
    val thoughtCallbacks: Int,
    val firstThoughtAtCallback: Int,
    val channelKeys: Set<String>,
    val completed: Boolean,
    val error: String?,
  )

  @OptIn(ExperimentalApi::class)
  @Test
  fun probeReasoningChannels() {
    val path = modelPath
    assumeTrue("Model not found at $path (pass -e model <path>)", File(path).exists())

    val initStart = System.currentTimeMillis()
    val engine =
      Engine(
        EngineConfig(
          modelPath = path,
          backend = Backend.GPU(),
          visionBackend = Backend.GPU(),
          audioBackend = Backend.CPU(),
          maxNumTokens = 256, // bound generation so a leg actually completes in-budget
          cacheDir = cacheDir,
        )
      )
    engine.initialize()
    Log.i(TAG, "engine ready in ${System.currentTimeMillis() - initStart} ms; resident=${engine.isInitialized()}")

    val prompt = "What is 17 plus 26? Think briefly, then give just the final number."

    try {
      logOutcome("A1 enable_thinking=true ", streamOnce(engine, prompt, mapOf("enable_thinking" to "true")))
      logOutcome("A2 baseline(emptyMap)   ", streamOnce(engine, prompt, emptyMap()))
    } finally {
      engine.close()
      Log.i(TAG, "engine closed")
    }
  }

  /** Stream one turn; NEVER throws — returns whatever was captured (partial on timeout/error). */
  @OptIn(ExperimentalApi::class)
  private fun streamOnce(engine: Engine, prompt: String, extraContext: Map<String, String>): StreamOutcome {
    val conv =
      engine.createConversation(
        ConversationConfig(samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = 0.0))
      )
    val visible = StringBuilder()
    val reasoning = StringBuilder() // accumulate the FULL thought (deltas), not just the longest
    var tokenCallbacks = 0
    var thoughtCallbacks = 0
    var firstThoughtAt = -1
    val keys = mutableSetOf<String>()
    val latch = CountDownLatch(1)
    val err = arrayOfNulls<Throwable>(1)
    var completed = false
    try {
      conv.sendMessageAsync(
        Contents.of(listOf(Content.Text(prompt))),
        object : MessageCallback {
          override fun onMessage(message: Message) {
            tokenCallbacks++
            visible.append(message.toString())
            val ch = message.channels
            keys.addAll(ch.keys)
            val thought = ch["thought"]
            if (!thought.isNullOrEmpty()) {
              thoughtCallbacks++
              reasoning.append(thought)
              if (firstThoughtAt < 0) {
                firstThoughtAt = tokenCallbacks
                Log.i(TAG, "  [first 'thought' channel at callback #$tokenCallbacks, keys=${ch.keys}]")
              }
            }
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
        extraContext,
      )
      if (!latch.await(180, TimeUnit.SECONDS)) err[0] = IllegalStateException("wait elapsed (no onDone)")
    } catch (t: Throwable) {
      err[0] = t
    } finally {
      runCatching { conv.close() }
    }
    return StreamOutcome(
      visible = visible.toString(),
      reasoning = reasoning.toString(),
      tokenCallbacks = tokenCallbacks,
      thoughtCallbacks = thoughtCallbacks,
      firstThoughtAtCallback = firstThoughtAt,
      channelKeys = keys,
      completed = completed,
      error = err[0]?.message,
    )
  }

  private fun logOutcome(label: String, o: StreamOutcome) {
    val leaks = o.visible.contains("<think", ignoreCase = true) || o.visible.contains("</think", ignoreCase = true)
    Log.i(
      TAG,
      "$label completed=${o.completed} error=${o.error} callbacks=${o.tokenCallbacks} " +
        "thoughtCallbacks=${o.thoughtCallbacks} firstThoughtAt=${o.firstThoughtAtCallback} " +
        "channelKeys=${o.channelKeys} visibleLen=${o.visible.length} reasoningLen=${o.reasoning.length} " +
        "visibleHasThinkTag=$leaks",
    )
    Log.i(TAG, "$label VISIBLE:   \"${o.visible.take(240).replace("\n", "\\n")}\"")
    if (o.reasoning.isNotEmpty()) Log.i(TAG, "$label REASONING: \"${o.reasoning.take(240).replace("\n", "\\n")}\"")
  }

  private companion object {
    const val TAG = "RelaisRCProbe"
    const val DEFAULT_MODEL = "/data/local/tmp/relais/gemma-4-E2B-it.litertlm"
  }
}
