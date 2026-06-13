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

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the tool-aware behavior of [buildPromptParts] (Feature 04 — tool calling).
 *
 * Device-free: no Context, no Android SDK, no Robolectric. These cover assistant tool_calls
 * parsing, trailing-tool-run -> liveToolResults, tool-name resolution + fallback, and the emission
 * (model-may-call-a-tool) case where the last message is a user turn.
 */
class ToolConversationParseTest {

  // ---------------------------------------------------------------------------
  // assistant tool_calls parsed into history ParsedTurn.toolCalls
  // ---------------------------------------------------------------------------

  @Test
  fun `assistant tool_calls are parsed into history turn toolCalls`() {
    val messages = JSONArray("""
      [{"role":"user","content":"What's the weather in Paris?"},
       {"role":"assistant","content":null,"tool_calls":[
         {"id":"call_1","type":"function",
          "function":{"name":"get_weather","arguments":"{\"city\":\"Paris\"}"}}]},
       {"role":"tool","tool_call_id":"call_1","content":"sunny, 21C"},
       {"role":"user","content":"And tomorrow?"}]
    """)
    val result = buildPromptParts(messages)
    // History: user(Q1), assistant(tool_calls), tool(result). Last user is the live turn.
    assertEquals("And tomorrow?", result.lastUserText)
    val assistantTurn = result.history.first { it.role == "assistant" }
    assertEquals(1, assistantTurn.toolCalls.size)
    assertEquals("call_1", assistantTurn.toolCalls[0].id)
    assertEquals("get_weather", assistantTurn.toolCalls[0].name)
    assertEquals("""{"city":"Paris"}""", assistantTurn.toolCalls[0].argumentsJson)
    // The earlier (non-trailing) tool result must be in history, name resolved via the id map.
    val toolTurn = result.history.first { it.role == "tool" }
    assertEquals("get_weather", toolTurn.toolName)
    assertEquals("sunny, 21C", toolTurn.text)
    // No trailing tool run here -> liveToolResults empty.
    assertTrue(result.liveToolResults.isEmpty())
  }

  // ---------------------------------------------------------------------------
  // trailing tool message -> liveToolResults with name resolved from id map
  // ---------------------------------------------------------------------------

  @Test
  fun `trailing tool message becomes liveToolResult with name resolved from preceding tool_calls id`() {
    val messages = JSONArray("""
      [{"role":"user","content":"Weather in Paris?"},
       {"role":"assistant","content":null,"tool_calls":[
         {"id":"call_abc","type":"function",
          "function":{"name":"get_weather","arguments":"{\"city\":\"Paris\"}"}}]},
       {"role":"tool","tool_call_id":"call_abc","content":"sunny, 21C"}]
    """)
    val result = buildPromptParts(messages)
    // Trailing tool run present -> NO user promoted to live turn.
    assertEquals("", result.lastUserText)
    assertEquals(1, result.liveToolResults.size)
    assertEquals("get_weather", result.liveToolResults[0].name)
    assertEquals("sunny, 21C", result.liveToolResults[0].content)
    // The user + assistant(tool_calls) precede the live turn -> they're in history.
    assertEquals("user", result.history[0].role)
    assertEquals("assistant", result.history[1].role)
    assertEquals(1, result.history[1].toolCalls.size)
    assertEquals("call_abc", result.history[1].toolCalls[0].id)
  }

  // ---------------------------------------------------------------------------
  // tool result name fallback to the "name" field when id map can't resolve
  // ---------------------------------------------------------------------------

  @Test
  fun `trailing tool message name falls back to name field when id not in map`() {
    val messages = JSONArray("""
      [{"role":"user","content":"Weather?"},
       {"role":"assistant","content":null,"tool_calls":[
         {"id":"call_known","type":"function","function":{"name":"get_weather","arguments":"{}"}}]},
       {"role":"tool","tool_call_id":"call_unknown","name":"get_weather","content":"sunny"}]
    """)
    val result = buildPromptParts(messages)
    assertEquals(1, result.liveToolResults.size)
    // tool_call_id "call_unknown" is absent from the id map, so resolution falls back to "name".
    assertEquals("get_weather", result.liveToolResults[0].name)
    assertEquals("sunny", result.liveToolResults[0].content)
  }

  // ---------------------------------------------------------------------------
  // emission case: tools present, last message is a user turn (model may call a tool)
  // ---------------------------------------------------------------------------

  @Test
  fun `last message user still promotes to live turn and liveToolResults stays empty`() {
    val messages = JSONArray("""
      [{"role":"system","content":"You can call tools."},
       {"role":"user","content":"What's the weather in Paris?"}]
    """)
    val result = buildPromptParts(messages)
    assertEquals("You can call tools.", result.systemPrompt)
    assertEquals("What's the weather in Paris?", result.lastUserText)
    assertTrue("no trailing tool run -> no live tool results", result.liveToolResults.isEmpty())
    assertTrue("single user turn must not appear in history", result.history.isEmpty())
  }

  // ---------------------------------------------------------------------------
  // assistant tool_calls with arguments sent as a JSON object (not a string)
  // ---------------------------------------------------------------------------

  @Test
  fun `assistant tool_call arguments object is stringified`() {
    val messages = JSONArray("""
      [{"role":"user","content":"Weather?"},
       {"role":"assistant","content":null,"tool_calls":[
         {"id":"call_1","type":"function",
          "function":{"name":"get_weather","arguments":{"city":"Paris"}}}]},
       {"role":"user","content":"thanks"}]
    """)
    val result = buildPromptParts(messages)
    val assistantTurn = result.history.first { it.role == "assistant" }
    assertEquals(1, assistantTurn.toolCalls.size)
    // arguments was a JSON object -> stringified; re-parse to assert structurally.
    val args = org.json.JSONObject(assistantTurn.toolCalls[0].argumentsJson)
    assertEquals("Paris", args.optString("city"))
  }
}
