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
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device probe for PR #26 redesign: does seeding ConversationConfig.systemInstruction +
 * initialMessages give correct multi-turn behavior with a SINGLE decode (vs the replaySend
 * generate-and-discard path that does one full decode per history turn)?
 *
 * Run (rango / Pixel 10 / G5, E2B staged):
 *   adb -s <serial> shell am instrument -w \
 *     -e class cc.grepon.relais.MultiTurnReplayProbe \
 *     -e model /storage/emulated/0/Android/data/cc.grepon.relais/files/litert_community_gemma_4_E2B_it_litert_lm/361a4010ad6d88fc5c86e148e333c0342b99763d/gemma-4-E2B-it.litertlm \
 *     cc.grepon.relais.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Watch: adb logcat -s RelaisProbe
 */
@RunWith(AndroidJUnit4::class)
class MultiTurnReplayProbe {

  private val args = InstrumentationRegistry.getArguments()
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val cacheDir = context.getExternalFilesDir(null)?.absolutePath
  private val modelPath: String
    get() = args.getString("model") ?: DEFAULT_MODEL

  @OptIn(ExperimentalApi::class)
  @Test
  fun probeConversationConfigSeeding() {
    val path = modelPath
    assumeTrue("Model not found at $path (pass -e model <path>)", File(path).exists())

    val engine =
      Engine(
        EngineConfig(
          modelPath = path,
          backend = Backend.GPU(),
          visionBackend = Backend.GPU(),
          audioBackend = Backend.CPU(),
          maxNumTokens = 1024,
          cacheDir = cacheDir,
        )
      )
    val initStart = System.currentTimeMillis()
    engine.initialize()
    Log.i(TAG, "engine initialized in ${System.currentTimeMillis() - initStart} ms")

    try {
      // 0) Print the model's rendered chat template per role (informs any fallback design).
      runCatching {
        val c = engine.createConversation(ConversationConfig(samplerConfig = sampler()))
        try {
          Log.i(TAG, "RENDER system = >>>${c.renderMessageIntoString(Message.system("BE_TERSE"), emptyMap())}<<<")
          Log.i(TAG, "RENDER user   = >>>${c.renderMessageIntoString(Message.user("HELLO_USER"), emptyMap())}<<<")
          Log.i(TAG, "RENDER model  = >>>${c.renderMessageIntoString(Message.model("HI_MODEL"), emptyMap())}<<<")
        } finally {
          c.close()
        }
      }.onFailure { Log.w(TAG, "render probe failed: ${it.message}") }

      // 1) Baseline: no system, no history -> single decode.
      val (bReply, bMs) = timed { ask(engine, system = null, initial = emptyList(), live = "Reply with exactly one word: ping") }
      Log.i(TAG, "BASELINE  t=${bMs}ms  reply=\"${bReply.take(80)}\"")

      // 2) System instruction honored via ConversationConfig.systemInstruction?
      val (sysReply, sysMs) =
        timed {
          ask(
            engine,
            system = "You must always answer with exactly one word, in ALL CAPITAL LETTERS.",
            initial = emptyList(),
            live = "What color is a clear daytime sky?",
          )
        }
      Log.i(TAG, "SYSTEM    t=${sysMs}ms  reply=\"${sysReply.take(80)}\"  honored=${sysReply.uppercase() == sysReply && sysReply.trim().split(Regex("\\s+")).size == 1}")

      // 3) The 2-exchange history that 500'd under replaySend — seeded via initialMessages.
      val history =
        listOf(
          Message.user("I am planning a trip to Japan in spring."),
          Message.model("Spring is a great time to visit Japan for cherry blossoms."),
          Message.user("My budget is two thousand dollars."),
          Message.model("A two thousand dollar budget is workable for a short Japan trip."),
        )
      val (hReply, hMs) =
        timed {
          ask(
            engine,
            system = "You are concise.",
            initial = history,
            live = "In one short sentence, what did I say my budget was and where am I going?",
          )
        }
      Log.i(TAG, "SEEDED    t=${hMs}ms  reply=\"${hReply.take(200)}\"")
      val recallOk =
        hReply.contains("Japan", ignoreCase = true) &&
          (hReply.contains("2000") || hReply.contains("2,000") || hReply.contains("two thousand", ignoreCase = true))
      val oneShot = hMs < bMs * 3 // a single decode should be on the order of the baseline, not 4x+
      Log.i(TAG, "VERDICT   recall_ok=$recallOk  one_shot_decode=$oneShot  (baseline=${bMs}ms seeded=${hMs}ms)")
      Log.i(TAG, "VERDICT   system_first_class=ConversationConfig.systemInstruction  history_first_class=ConversationConfig.initialMessages")
    } finally {
      engine.close()
      Log.i(TAG, "engine closed")
    }
  }

  @OptIn(ExperimentalApi::class)
  private fun ask(engine: Engine, system: String?, initial: List<Message>, live: String): String {
    val config =
      if (system != null) {
        ConversationConfig(systemInstruction = Contents.of(system), initialMessages = initial, samplerConfig = sampler())
      } else {
        ConversationConfig(initialMessages = initial, samplerConfig = sampler())
      }
    val conversation = engine.createConversation(config)
    return try {
      conversation.sendMessage(Contents.of(listOf(Content.Text(live))), emptyMap()).toString()
    } finally {
      conversation.close()
    }
  }

  private fun sampler() = SamplerConfig(topK = 64, topP = 0.95, temperature = 1.0)

  private inline fun <T> timed(block: () -> T): Pair<T, Long> {
    val t0 = System.currentTimeMillis()
    val r = block()
    return r to (System.currentTimeMillis() - t0)
  }

  private companion object {
    const val TAG = "RelaisProbe"
    const val DEFAULT_MODEL = "/data/local/tmp/relais/gemma-4-E2B-it.litertlm"
  }
}
