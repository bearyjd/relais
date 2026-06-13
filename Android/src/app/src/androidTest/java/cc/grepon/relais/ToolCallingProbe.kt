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
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolManager
import com.google.ai.edge.litertlm.tool
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device probe for feature-04: does the bundled LiteRT-LM expose a WORKING native tool-calling
 * path, and does the resident model actually emit a structured tool call through it?
 *
 * Decides the engine path: native ToolManager (clean, library-parsed ToolCall objects) vs the
 * prompt-injection + output-scraping fallback the original plan assumed was the only option.
 *
 * Run (rango / Pixel 10 / G5, E2B staged):
 *   adb -s <serial> shell am instrument -w \
 *     -e class cc.grepon.relais.ToolCallingProbe \
 *     -e model /storage/emulated/0/Android/data/cc.grepon.relais/files/litert_community_gemma_4_E2B_it_litert_lm/361a4010ad6d88fc5c86e148e333c0342b99763d/gemma-4-E2B-it.litertlm \
 *     cc.grepon.relais.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Watch: adb logcat -s RelaisToolProbe
 */
@RunWith(AndroidJUnit4::class)
class ToolCallingProbe {

  private val args = InstrumentationRegistry.getArguments()
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val cacheDir = context.getExternalFilesDir(null)?.absolutePath
  private val modelPath: String
    get() = args.getString("model") ?: DEFAULT_MODEL

  /** An OpenAI-style function tool fed to LiteRT-LM via the OpenApiTool bridge. */
  private class WeatherTool : OpenApiTool {
    var executed = false
    override fun getToolDescriptionJsonString(): String =
      """
      {"name":"get_weather",
       "description":"Get the current weather for a city.",
       "parameters":{"type":"object",
         "properties":{"city":{"type":"string","description":"City name, e.g. London"}},
         "required":["city"]}}
      """.trimIndent()

    // Only invoked when automaticToolCalling = true. For OpenAI-compat we use false (client executes).
    override fun execute(params: String): String {
      executed = true
      Log.i(TAG, "execute() called with params=$params")
      return """{"temperature_c":12,"conditions":"cloudy"}"""
    }
  }

  @OptIn(ExperimentalApi::class)
  @Test
  fun probeNativeToolCalling() {
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
    val t0 = System.currentTimeMillis()
    engine.initialize()
    Log.i(TAG, "engine initialized in ${System.currentTimeMillis() - t0} ms")

    try {
      val weather = WeatherTool()
      val provider = tool(weather)

      // What does the library inject into the model prompt for this tool?
      runCatching {
        Log.i(TAG, "TOOLS_DESCRIPTION = ${ToolManager(listOf(provider)).getToolsDescription()}")
      }.onFailure { Log.w(TAG, "getToolsDescription failed: ${it.message}") }

      // --- Probe 1: manual mode (OpenAI-compat). Expect message.toolCalls populated, execute() NOT called.
      val sampler = SamplerConfig(topK = 64, topP = 0.95, temperature = 1.0)
      val conv1 =
        engine.createConversation(
          ConversationConfig(tools = listOf(provider), automaticToolCalling = false, samplerConfig = sampler)
        )
      val reply1 =
        try {
          conv1.sendMessage(Contents.of(listOf(Content.Text("What is the weather in London right now?"))), emptyMap())
        } finally {
          conv1.close()
        }
      Log.i(TAG, "MANUAL reply.role=${reply1.role} toolCalls.size=${reply1.toolCalls.size} executed=${weather.executed}")
      reply1.toolCalls.forEachIndexed { i, tc -> Log.i(TAG, "MANUAL toolCall[$i] name=${tc.name} args=${tc.arguments}") }
      Log.i(TAG, "MANUAL reply.contents=${reply1}")
      val manualEmitted = reply1.toolCalls.isNotEmpty()
      Log.i(TAG, "VERDICT manual_native_toolcall=$manualEmitted execute_not_called=${!weather.executed}")

      // --- Probe 2: manual round-trip. Feed the tool result back; expect a final NL reply citing it.
      if (manualEmitted) {
        val weather2 = WeatherTool()
        val provider2 = tool(weather2)
        val conv2 =
          engine.createConversation(
            ConversationConfig(tools = listOf(provider2), automaticToolCalling = false, samplerConfig = sampler)
          )
        val final =
          try {
            conv2.sendMessage(Contents.of(listOf(Content.Text("What is the weather in London right now?"))), emptyMap())
            // Send the tool result back as a TOOL turn.
            conv2.sendMessage(
              Message.tool(Contents.of(listOf(Content.ToolResponse("get_weather", """{"temperature_c":12,"conditions":"cloudy"}""")))),
              emptyMap(),
            )
          } finally {
            conv2.close()
          }
        Log.i(TAG, "ROUNDTRIP final.role=${final.role} text=${final}")
        val citesResult = final.toString().contains("12") || final.toString().contains("cloud", ignoreCase = true)
        Log.i(TAG, "VERDICT roundtrip_cites_tool_result=$citesResult")
      }

      // --- Probe 3: automatic mode. Library should call execute() and return a final NL answer.
      val weather3 = WeatherTool()
      val conv3 =
        engine.createConversation(
          ConversationConfig(tools = listOf(tool(weather3)), automaticToolCalling = true, samplerConfig = sampler)
        )
      val auto =
        try {
          conv3.sendMessage(Contents.of(listOf(Content.Text("What is the weather in London right now?"))), emptyMap())
        } finally {
          conv3.close()
        }
      Log.i(TAG, "VERDICT auto_execute_called=${weather3.executed} auto_reply=${auto}")
    } finally {
      engine.close()
      Log.i(TAG, "engine closed")
    }
  }

  private companion object {
    const val TAG = "RelaisToolProbe"
    const val DEFAULT_MODEL = "/data/local/tmp/relais/gemma-4-E2B-it.litertlm"
  }
}
