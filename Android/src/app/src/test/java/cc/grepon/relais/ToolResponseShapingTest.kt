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

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [buildToolAssistantMessage] envelope shaping.
 *
 * Exercises:
 *  - Tool-calls result (non-streaming): content==null, finish_reason=="tool_calls", each entry has
 *    id/type/function.name/function.arguments (a String), and NO `index` field.
 *  - Tool-calls result (streaming=true): same as above PLUS each entry has an integer `index`
 *    matching its 0-based position.
 *  - Text result (no tool calls): finish_reason=="stop", content equals the text.
 *
 * Constructs [RelaisResult] directly — no engine or Android Context required.
 */
class ToolResponseShapingTest {

  // A minimal RelaisResult carrying two tool calls.
  private val twoCallResult = RelaisResult(
    text = "",
    backend = RelaisBackend.GPU_LITERTLM,
    decodeTokensPerSec = 0.0,
    completionTokens = 0,
    toolCalls = listOf(
      ParsedToolCall(id = "call_aaa", name = "get_weather", argumentsJson = "{\"city\":\"Paris\"}"),
      ParsedToolCall(id = "call_bbb", name = "get_time",    argumentsJson = "{\"tz\":\"UTC\"}"),
    ),
  )

  // A minimal RelaisResult with plain text and no tool calls.
  private val textResult = RelaisResult(
    text = "The weather in Paris is sunny.",
    backend = RelaisBackend.GPU_LITERTLM,
    decodeTokensPerSec = 0.0,
    completionTokens = 4,
    toolCalls = emptyList(),
  )

  // Helper: pull the i-th tool_call entry from the message's tool_calls array.
  private fun toolCallAt(message: JSONObject, i: Int): JSONObject =
    message.getJSONArray("tool_calls").getJSONObject(i)

  // ---- non-streaming tool-calls envelope ----

  @Test
  fun `non-streaming tool calls - finish_reason is tool_calls`() {
    val (_, finishReason) = buildToolAssistantMessage(twoCallResult, streaming = false)
    assertEquals("tool_calls", finishReason)
  }

  @Test
  fun `non-streaming tool calls - content is null`() {
    val (message, _) = buildToolAssistantMessage(twoCallResult, streaming = false)
    assertEquals(JSONObject.NULL, message.get("content"))
  }

  @Test
  fun `non-streaming tool calls - tool_calls array has correct length`() {
    val (message, _) = buildToolAssistantMessage(twoCallResult, streaming = false)
    assertEquals(2, message.getJSONArray("tool_calls").length())
  }

  @Test
  fun `non-streaming tool calls - each entry has id type and function fields`() {
    val (message, _) = buildToolAssistantMessage(twoCallResult, streaming = false)
    listOf(0, 1).forEach { i ->
      val entry = toolCallAt(message, i)
      assertFalse("entry[$i] missing id",   entry.optString("id").isEmpty())
      assertEquals("function", entry.optString("type"))
      assertNotNull("entry[$i] missing function object", entry.optJSONObject("function"))
    }
  }

  @Test
  fun `non-streaming tool calls - function name and arguments are correct`() {
    val (message, _) = buildToolAssistantMessage(twoCallResult, streaming = false)
    val fn0 = toolCallAt(message, 0).getJSONObject("function")
    val fn1 = toolCallAt(message, 1).getJSONObject("function")
    assertEquals("get_weather", fn0.optString("name"))
    assertEquals("get_time",    fn1.optString("name"))
    // arguments must be a String (not a nested object)
    assertTrue("arguments[0] should be a String", fn0.get("arguments") is String)
    assertTrue("arguments[1] should be a String", fn1.get("arguments") is String)
    assertEquals("{\"city\":\"Paris\"}", fn0.optString("arguments"))
    assertEquals("{\"tz\":\"UTC\"}",    fn1.optString("arguments"))
  }

  @Test
  fun `non-streaming tool calls - entries must NOT have index`() {
    val (message, _) = buildToolAssistantMessage(twoCallResult, streaming = false)
    listOf(0, 1).forEach { i ->
      assertFalse(
        "entry[$i] must not have 'index' in non-streaming form",
        toolCallAt(message, i).has("index"),
      )
    }
  }

  // ---- streaming tool-calls envelope ----

  @Test
  fun `streaming tool calls - finish_reason is tool_calls`() {
    val (_, finishReason) = buildToolAssistantMessage(twoCallResult, streaming = true)
    assertEquals("tool_calls", finishReason)
  }

  @Test
  fun `streaming tool calls - content is null`() {
    val (message, _) = buildToolAssistantMessage(twoCallResult, streaming = true)
    assertEquals(JSONObject.NULL, message.get("content"))
  }

  @Test
  fun `streaming tool calls - each entry has index matching its position`() {
    val (message, _) = buildToolAssistantMessage(twoCallResult, streaming = true)
    listOf(0, 1).forEach { i ->
      val entry = toolCallAt(message, i)
      assertTrue("entry[$i] missing 'index'", entry.has("index"))
      assertEquals(i, entry.getInt("index"))
    }
  }

  @Test
  fun `streaming tool calls - id type and function fields present`() {
    val (message, _) = buildToolAssistantMessage(twoCallResult, streaming = true)
    listOf(0, 1).forEach { i ->
      val entry = toolCallAt(message, i)
      assertFalse("entry[$i] missing id", entry.optString("id").isEmpty())
      assertEquals("function", entry.optString("type"))
      assertNotNull(entry.optJSONObject("function"))
    }
  }

  // ---- text (no tool calls) envelope ----

  @Test
  fun `text result - finish_reason is stop`() {
    val (_, finishReason) = buildToolAssistantMessage(textResult)
    assertEquals("stop", finishReason)
  }

  @Test
  fun `text result - content equals the reply text`() {
    val (message, _) = buildToolAssistantMessage(textResult)
    assertEquals("The weather in Paris is sunny.", message.optString("content"))
  }

  @Test
  fun `text result - no tool_calls field`() {
    val (message, _) = buildToolAssistantMessage(textResult)
    assertFalse("text result must not have tool_calls", message.has("tool_calls"))
  }
}
