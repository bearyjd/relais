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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [parseTools] and [parseToolChoice] (Feature 04 — tool calling).
 *
 * Device-free: no Context, no Android SDK, no Robolectric. Both functions are top-level in
 * RelaisToolParsing.kt and operate on org.json.JSONObject.
 */
class RelaisToolParsingTest {

  // ---------------------------------------------------------------------------
  // parseTools
  // ---------------------------------------------------------------------------

  @Test
  fun `parseTools returns empty when tools field absent`() {
    val body = JSONObject("""{"model":"x","messages":[]}""")
    assertTrue(parseTools(body).isEmpty())
  }

  @Test
  fun `parseTools returns empty for an empty tools array`() {
    val body = JSONObject("""{"tools":[]}""")
    assertTrue(parseTools(body).isEmpty())
  }

  @Test
  fun `parseTools parses a single function tool with verbatim function json`() {
    val body = JSONObject("""
      {"tools":[
        {"type":"function","function":{
          "name":"get_weather",
          "description":"Get weather",
          "parameters":{"type":"object","properties":{"city":{"type":"string"}}}
        }}
      ]}
    """)
    val tools = parseTools(body)
    assertEquals(1, tools.size)
    assertEquals("get_weather", tools[0].name)
    // functionJson must be the function object verbatim (re-parse to compare structurally).
    val parsed = JSONObject(tools[0].functionJson)
    assertEquals("get_weather", parsed.optString("name"))
    assertEquals("Get weather", parsed.optString("description"))
    assertTrue(parsed.has("parameters"))
  }

  @Test
  fun `parseTools parses two function tools in order`() {
    val body = JSONObject("""
      {"tools":[
        {"type":"function","function":{"name":"a","parameters":{}}},
        {"type":"function","function":{"name":"b","parameters":{}}}
      ]}
    """)
    val tools = parseTools(body)
    assertEquals(2, tools.size)
    assertEquals("a", tools[0].name)
    assertEquals("b", tools[1].name)
  }

  @Test
  fun `parseTools skips non-function tool types`() {
    val body = JSONObject("""
      {"tools":[
        {"type":"retrieval"},
        {"type":"function","function":{"name":"keep"}},
        {"type":"code_interpreter"}
      ]}
    """)
    val tools = parseTools(body)
    assertEquals(1, tools.size)
    assertEquals("keep", tools[0].name)
  }

  @Test
  fun `parseTools skips function entries missing a name`() {
    val body = JSONObject("""
      {"tools":[
        {"type":"function","function":{"description":"no name here"}},
        {"type":"function"},
        {"type":"function","function":{"name":"valid"}}
      ]}
    """)
    val tools = parseTools(body)
    assertEquals(1, tools.size)
    assertEquals("valid", tools[0].name)
  }

  // ---------------------------------------------------------------------------
  // parseToolChoice
  // ---------------------------------------------------------------------------

  @Test
  fun `parseToolChoice absent defaults to Auto`() {
    val body = JSONObject("""{"messages":[]}""")
    assertEquals(ToolChoice.Auto, parseToolChoice(body))
  }

  @Test
  fun `parseToolChoice none maps to None`() {
    assertEquals(ToolChoice.None, parseToolChoice(JSONObject("""{"tool_choice":"none"}""")))
  }

  @Test
  fun `parseToolChoice auto maps to Auto`() {
    assertEquals(ToolChoice.Auto, parseToolChoice(JSONObject("""{"tool_choice":"auto"}""")))
  }

  @Test
  fun `parseToolChoice required maps to Required`() {
    assertEquals(ToolChoice.Required, parseToolChoice(JSONObject("""{"tool_choice":"required"}""")))
  }

  @Test
  fun `parseToolChoice forced object maps to Forced with name`() {
    val body = JSONObject("""{"tool_choice":{"type":"function","function":{"name":"get_weather"}}}""")
    assertEquals(ToolChoice.Forced("get_weather"), parseToolChoice(body))
  }
}
