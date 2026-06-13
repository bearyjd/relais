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
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.tool
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Feature-05 probe: does `ExperimentalFlags.enableConversationConstrainedDecoding` make a
 * schema-typed tool emit clean, schema-conforming arguments on Gemma-4 E2B — i.e. can structured
 * output be implemented as a single forced "tool" whose parameters ARE the requested JSON schema,
 * with the engine grammar-constraining the output to that schema?
 *
 * Compares constrained=OFF vs ON on the same schema/prompt, twice each (to gauge run-to-run noise),
 * looking for: (1) the model reliably emits the tool call, (2) args match the schema shape/types
 * cleanly (no `{name:{type:STRING,value:...}}` nesting), (3) `age` is an integer, not a string.
 *
 * Run (rango / Pixel 10 / G5, E2B staged):
 *   adb -s <serial> shell am instrument -w -e class cc.grepon.relais.StructuredOutputProbe \
 *     -e model /storage/emulated/0/Android/data/cc.grepon.relais/files/litert_community_gemma_4_E2B_it_litert_lm/361a4010ad6d88fc5c86e148e333c0342b99763d/gemma-4-E2B-it.litertlm \
 *     cc.grepon.relais.test/androidx.test.runner.AndroidJUnitRunner
 * Watch: adb logcat -s RelaisSOProbe
 */
@RunWith(AndroidJUnit4::class)
class StructuredOutputProbe {

  private val args = InstrumentationRegistry.getArguments()
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val cacheDir = context.getExternalFilesDir(null)?.absolutePath
  private val modelPath: String
    get() = args.getString("model") ?: DEFAULT_MODEL

  private class SchemaTool : OpenApiTool {
    override fun getToolDescriptionJsonString(): String =
      """
      {"name":"emit_person",
       "description":"Return the person extracted from the text.",
       "parameters":{"type":"object",
         "properties":{
           "name":{"type":"string","description":"Full name"},
           "age":{"type":"integer","description":"Age in years"}},
         "required":["name","age"]}}
      """.trimIndent()
    override fun execute(paramsJsonString: String): String = "{}"
  }

  @OptIn(ExperimentalApi::class)
  @Test
  fun probeConstrainedStructuredOutput() {
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
    engine.initialize()
    Log.i(TAG, "engine ready")

    val prompt = "Extract the person from this text and call emit_person: 'John Smith is 42 years old.'"

    fun run(constrained: Boolean, label: String) {
      ExperimentalFlags.enableConversationConstrainedDecoding = constrained
      val conv =
        engine.createConversation(
          ConversationConfig(
            tools = listOf(tool(SchemaTool())),
            automaticToolCalling = false,
            samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = 1.0),
          )
        )
      try {
        val reply = conv.sendMessage(Contents.of(listOf(Content.Text(prompt))), emptyMap())
        if (reply.toolCalls.isEmpty()) {
          Log.i(TAG, "$label: NO tool call. text=${reply}")
        } else {
          reply.toolCalls.forEach { Log.i(TAG, "$label: toolCall name=${it.name} args=${it.arguments}") }
        }
      } catch (t: Throwable) {
        Log.w(TAG, "$label: threw ${t.message}")
      } finally {
        conv.close()
      }
    }

    try {
      run(false, "OFF#1")
      run(false, "OFF#2")
      run(true, "ON#1")
      run(true, "ON#2")
    } finally {
      ExperimentalFlags.enableConversationConstrainedDecoding = false
      engine.close()
      Log.i(TAG, "engine closed")
    }
  }

  private companion object {
    const val TAG = "RelaisSOProbe"
    const val DEFAULT_MODEL = "/data/local/tmp/relais/gemma-4-E2B-it.litertlm"
  }
}
