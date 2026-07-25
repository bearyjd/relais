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

import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [buildAnthropicPromptParts] / [parseAnthropicTools] / [parseAnthropicToolChoice] /
 * [anthropicStopReason] (issue #179). All tests are device-free: no Context, no Android SDK, no
 * Robolectric. Mirrors [OpenAiRequestParserTest]'s style.
 */
class AnthropicRequestParserTest {

  // ---------------------------------------------------------------------------
  // system field normalization
  // ---------------------------------------------------------------------------

  @Test
  fun `system as a plain string is used verbatim`() {
    val result = buildAnthropicPromptParts(
      system = "Be concise.",
      messages = JSONArray("""[{"role":"user","content":"hi"}]"""),
    )
    assertEquals("Be concise.", result.systemPrompt)
  }

  @Test
  fun `system as a content-block array is flattened to a string`() {
    val system = JSONArray("""[{"type":"text","text":"Be "},{"type":"text","text":"concise."}]""")
    val result = buildAnthropicPromptParts(
      system = system,
      messages = JSONArray("""[{"role":"user","content":"hi"}]"""),
    )
    assertEquals("Be concise.", result.systemPrompt)
  }

  @Test
  fun `absent system yields null systemPrompt`() {
    val result = buildAnthropicPromptParts(
      system = null,
      messages = JSONArray("""[{"role":"user","content":"hi"}]"""),
    )
    assertNull(result.systemPrompt)
  }

  // ---------------------------------------------------------------------------
  // single user message
  // ---------------------------------------------------------------------------

  @Test
  fun `single user text message extracted with empty history`() {
    val result = buildAnthropicPromptParts(
      system = null,
      messages = JSONArray("""[{"role":"user","content":"Ahoy!"}]"""),
    )
    assertEquals("Ahoy!", result.lastUserText)
    assertTrue(result.history.isEmpty())
    assertNull(result.lastUserImage)
    assertTrue(result.liveToolResults.isEmpty())
  }

  // ---------------------------------------------------------------------------
  // multi-turn history ordering
  // ---------------------------------------------------------------------------

  @Test
  fun `multi-turn history accumulated in order`() {
    val messages = JSONArray(
      """
      [{"role":"user","content":"Q1"},
       {"role":"assistant","content":"A1"},
       {"role":"user","content":"Q2"},
       {"role":"assistant","content":"A2"},
       {"role":"user","content":"Q3"}]
      """
    )
    val result = buildAnthropicPromptParts(system = null, messages = messages)
    assertEquals(4, result.history.size)
    assertEquals("Q1", result.history[0].text); assertEquals("user", result.history[0].role)
    assertEquals("A1", result.history[1].text); assertEquals("assistant", result.history[1].role)
    assertEquals("Q2", result.history[2].text); assertEquals("user", result.history[2].role)
    assertEquals("A2", result.history[3].text); assertEquals("assistant", result.history[3].role)
    assertEquals("Q3", result.lastUserText)
  }

  // ---------------------------------------------------------------------------
  // image content block; only the LAST user message's image wins
  // ---------------------------------------------------------------------------

  @Test
  fun `image content block decoded and only the last user message's image wins`() {
    val firstImage = Base64.getEncoder().encodeToString(byteArrayOf(9, 9, 9))
    val lastImage = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
    val messages = JSONArray(
      """
      [{"role":"user","content":[
          {"type":"text","text":"first"},
          {"type":"image","source":{"type":"base64","media_type":"image/png","data":"$firstImage"}}
       ]},
       {"role":"assistant","content":"ok"},
       {"role":"user","content":[
          {"type":"text","text":"describe this"},
          {"type":"image","source":{"type":"base64","media_type":"image/png","data":"$lastImage"}}
       ]}]
      """
    )
    val result = buildAnthropicPromptParts(
      system = null,
      messages = messages,
      decode = { b64 -> Base64.getDecoder().decode(b64) },
    )
    assertEquals("describe this", result.lastUserText)
    assertArrayEquals(byteArrayOf(1, 2, 3), result.lastUserImage)
    // The first (non-live) turn's image is preserved on its own history turn, not surfaced as "last".
    assertArrayEquals(byteArrayOf(9, 9, 9), result.history[0].imagePng)
  }

  // ---------------------------------------------------------------------------
  // tool_use -> tool_result round trip
  // ---------------------------------------------------------------------------

  @Test
  fun `tool_use assistant turn followed by tool_result user turn resolves tool name`() {
    val messages = JSONArray(
      """
      [{"role":"user","content":"what's the weather in Boston?"},
       {"role":"assistant","content":[
          {"type":"tool_use","id":"call_1","name":"get_weather","input":{"city":"Boston"}}
       ]},
       {"role":"user","content":[
          {"type":"tool_result","tool_use_id":"call_1","content":"58F and cloudy"}
       ]}]
      """
    )
    val result = buildAnthropicPromptParts(system = null, messages = messages)
    assertEquals(1, result.liveToolResults.size)
    assertEquals("get_weather", result.liveToolResults[0].name)
    assertEquals("58F and cloudy", result.liveToolResults[0].content)
    // The trailing tool-result turn is fully suppressed from lastUserText/history.
    assertEquals("", result.lastUserText)
    assertEquals(1, result.history.size) // the original user question only
    assertEquals("what's the weather in Boston?", result.history[0].text)
  }

  @Test
  fun `tool_result content as a text-blocks array is flattened`() {
    val messages = JSONArray(
      """
      [{"role":"user","content":"q"},
       {"role":"assistant","content":[{"type":"tool_use","id":"call_2","name":"calc","input":{}}]},
       {"role":"user","content":[
          {"type":"tool_result","tool_use_id":"call_2","content":[{"type":"text","text":"4"}]}
       ]}]
      """
    )
    val result = buildAnthropicPromptParts(system = null, messages = messages)
    assertEquals(1, result.liveToolResults.size)
    assertEquals("calc", result.liveToolResults[0].name)
    assertEquals("4", result.liveToolResults[0].content)
  }

  @Test
  fun `an earlier non-trailing tool_result becomes a role-tool history turn`() {
    val messages = JSONArray(
      """
      [{"role":"user","content":"what's the weather in Boston?"},
       {"role":"assistant","content":[
          {"type":"tool_use","id":"call_1","name":"get_weather","input":{"city":"Boston"}}
       ]},
       {"role":"user","content":[
          {"type":"tool_result","tool_use_id":"call_1","content":"58F and cloudy"}
       ]},
       {"role":"assistant","content":"It's 58F and cloudy in Boston."},
       {"role":"user","content":"and tomorrow?"}]
      """
    )
    val result = buildAnthropicPromptParts(system = null, messages = messages)
    assertEquals("and tomorrow?", result.lastUserText)
    assertTrue("this round-trip is fully resolved, no live tool results", result.liveToolResults.isEmpty())
    // history: original question, the tool result (as a role:"tool" turn), the follow-up answer.
    assertEquals(3, result.history.size)
    assertEquals("user", result.history[0].role)
    assertEquals("tool", result.history[1].role)
    assertEquals("get_weather", result.history[1].toolName)
    assertEquals("58F and cloudy", result.history[1].text)
    assertEquals("assistant", result.history[2].role)
  }

  // ---------------------------------------------------------------------------
  // parseAnthropicTools
  // ---------------------------------------------------------------------------

  @Test
  fun `parseAnthropicTools synthesizes OpenAI-shaped functionJson from input_schema`() {
    val body = JSONObject(
      """
      {"tools":[{"name":"get_weather","description":"Get weather","input_schema":{"type":"object","properties":{"city":{"type":"string"}}}}]}
      """
    )
    val specs = parseAnthropicTools(body)
    assertEquals(1, specs.size)
    assertEquals("get_weather", specs[0].name)
    val fn = JSONObject(specs[0].functionJson)
    assertEquals("get_weather", fn.getString("name"))
    assertEquals("Get weather", fn.getString("description"))
    assertEquals("object", fn.getJSONObject("parameters").getString("type"))
  }

  @Test
  fun `parseAnthropicTools returns empty list when tools absent`() {
    assertTrue(parseAnthropicTools(JSONObject("{}")).isEmpty())
  }

  // ---------------------------------------------------------------------------
  // parseAnthropicToolChoice
  // ---------------------------------------------------------------------------

  @Test
  fun `tool_choice auto maps to ToolChoice-Auto`() {
    val body = JSONObject("""{"tools":[{"name":"x"}],"tool_choice":{"type":"auto"}}""")
    assertEquals(ToolChoice.Auto, parseAnthropicToolChoice(body))
  }

  @Test
  fun `tool_choice any maps to ToolChoice-Required`() {
    val body = JSONObject("""{"tools":[{"name":"x"}],"tool_choice":{"type":"any"}}""")
    assertEquals(ToolChoice.Required, parseAnthropicToolChoice(body))
  }

  @Test
  fun `tool_choice tool with name maps to ToolChoice-Forced`() {
    val body = JSONObject("""{"tools":[{"name":"x"}],"tool_choice":{"type":"tool","name":"x"}}""")
    assertEquals(ToolChoice.Forced("x"), parseAnthropicToolChoice(body))
  }

  @Test
  fun `absent tools array maps to ToolChoice-None`() {
    assertEquals(ToolChoice.None, parseAnthropicToolChoice(JSONObject("{}")))
  }

  // ---------------------------------------------------------------------------
  // anthropicStopReason
  // ---------------------------------------------------------------------------

  @Test
  fun `anthropicStopReason for a plain stop`() {
    val result = RelaisResult(text = "hi", backend = RelaisBackend.GPU_LITERTLM, decodeTokensPerSec = 1.0, completionTokens = 2)
    assertEquals("end_turn", anthropicStopReason(result))
  }

  @Test
  fun `anthropicStopReason for a thermally-truncated decode`() {
    val result = RelaisResult(
      text = "hi", backend = RelaisBackend.GPU_LITERTLM, decodeTokensPerSec = 1.0,
      completionTokens = 2, finishReason = RelaisFinishReason.LENGTH,
    )
    assertEquals("max_tokens", anthropicStopReason(result))
  }

  @Test
  fun `anthropicStopReason for tool calls`() {
    val result = RelaisResult(
      text = "", backend = RelaisBackend.GPU_LITERTLM, decodeTokensPerSec = 1.0,
      completionTokens = 0, toolCalls = listOf(ParsedToolCall("id1", "get_weather", "{}")),
    )
    assertEquals("tool_use", anthropicStopReason(result))
  }

  // ---------------------------------------------------------------------------
  // multiple tool_use / tool_result round trip (Fix 4)
  // ---------------------------------------------------------------------------

  @Test
  fun `two tool_use blocks each resolve to their own tool_result name`() {
    val messages = JSONArray(
      """
      [{"role":"user","content":"weather and time in Boston?"},
       {"role":"assistant","content":[
          {"type":"tool_use","id":"call_1","name":"get_weather","input":{"city":"Boston"}},
          {"type":"tool_use","id":"call_2","name":"get_time","input":{"city":"Boston"}}
       ]},
       {"role":"user","content":[
          {"type":"tool_result","tool_use_id":"call_1","content":"58F and cloudy"},
          {"type":"tool_result","tool_use_id":"call_2","content":"3:15 PM"}
       ]}]
      """
    )
    val result = buildAnthropicPromptParts(system = null, messages = messages)
    assertEquals(2, result.liveToolResults.size)
    assertEquals("get_weather", result.liveToolResults[0].name)
    assertEquals("58F and cloudy", result.liveToolResults[0].content)
    assertEquals("get_time", result.liveToolResults[1].name)
    assertEquals("3:15 PM", result.liveToolResults[1].content)
  }

  @Test
  fun `tool_result with an unresolvable tool_use_id does not crash and resolves to empty name`() {
    val messages = JSONArray(
      """
      [{"role":"user","content":"q"},
       {"role":"user","content":[
          {"type":"tool_result","tool_use_id":"no_such_call","content":"orphan result"}
       ]}]
      """
    )
    val result = buildAnthropicPromptParts(system = null, messages = messages)
    assertEquals(1, result.liveToolResults.size)
    assertEquals("", result.liveToolResults[0].name)
    assertEquals("orphan result", result.liveToolResults[0].content)
  }

  // ---------------------------------------------------------------------------
  // parseAnthropicTools edge cases (Fix 4)
  // ---------------------------------------------------------------------------

  @Test
  fun `parseAnthropicTools entry missing input_schema yields empty parameters object`() {
    val body = JSONObject("""{"tools":[{"name":"ping","description":"Ping"}]}""")
    val specs = parseAnthropicTools(body)
    assertEquals(1, specs.size)
    val fn = JSONObject(specs[0].functionJson)
    assertTrue(fn.getJSONObject("parameters").length() == 0)
  }

  @Test
  fun `parseAnthropicTools skips an entry missing name but keeps the valid one`() {
    val body = JSONObject(
      """
      {"tools":[{"description":"no name here"},{"name":"valid_tool","description":"ok"}]}
      """
    )
    val specs = parseAnthropicTools(body)
    assertEquals(1, specs.size)
    assertEquals("valid_tool", specs[0].name)
  }

  // ---------------------------------------------------------------------------
  // parseAnthropicToolChoice edge cases (Fix 4)
  // ---------------------------------------------------------------------------

  @Test
  fun `tool_choice type tool missing name falls back to Auto`() {
    val body = JSONObject("""{"tools":[{"name":"x"}],"tool_choice":{"type":"tool"}}""")
    assertEquals(ToolChoice.Auto, parseAnthropicToolChoice(body))
  }

  @Test
  fun `tool_choice unrecognized type falls back to Auto`() {
    val body = JSONObject("""{"tools":[{"name":"x"}],"tool_choice":{"type":"bogus"}}""")
    assertEquals(ToolChoice.Auto, parseAnthropicToolChoice(body))
  }

  // ---------------------------------------------------------------------------
  // applyAnthropicHistoryTruncation (via buildAnthropicPromptParts' maxHistoryChars param)
  // ---------------------------------------------------------------------------

  @Test
  fun `history under the char budget is left untouched`() {
    val messages = JSONArray(
      """
      [{"role":"user","content":"Q1"},
       {"role":"assistant","content":"A1"},
       {"role":"user","content":"Q2"}]
      """
    )
    val result = buildAnthropicPromptParts(system = null, messages = messages, maxHistoryChars = 1000)
    assertEquals(2, result.history.size)
    assertEquals("Q1", result.history[0].text)
    assertEquals("A1", result.history[1].text)
  }

  @Test
  fun `history over budget drops oldest user-assistant pairs first`() {
    val messages = JSONArray(
      """
      [{"role":"user","content":"aaaaaaaaaa"},
       {"role":"assistant","content":"bbbbbbbbbb"},
       {"role":"user","content":"cccccccccc"},
       {"role":"assistant","content":"dddddddddd"},
       {"role":"user","content":"live"}]
      """
    )
    // Budget fits only the last user/assistant pair (20 chars) — the oldest pair must be dropped.
    val result = buildAnthropicPromptParts(system = null, messages = messages, maxHistoryChars = 20)
    assertEquals(2, result.history.size)
    assertEquals("cccccccccc", result.history[0].text)
    assertEquals("dddddddddd", result.history[1].text)
    assertEquals("live", result.lastUserText)
  }

  @Test
  fun `a dangling leading turn after truncation is dropped without crashing`() {
    // An assistant-first remainder after the pair-drop loop must be swept by the trailing
    // user-first guard rather than surfacing a dangling assistant turn or crashing.
    val messages = JSONArray(
      """
      [{"role":"user","content":"aaaaaaaaaa"},
       {"role":"assistant","content":"bbbbbbbbbb"},
       {"role":"assistant","content":"cccccccccc"},
       {"role":"user","content":"live"}]
      """
    )
    val result = buildAnthropicPromptParts(system = null, messages = messages, maxHistoryChars = 10)
    // Whatever survives must start with a user turn (or be empty) — never a leading assistant turn.
    assertTrue(result.history.isEmpty() || result.history[0].role == "user")
    assertEquals("live", result.lastUserText)
  }

  // ---------------------------------------------------------------------------
  // malformed/edge-case message shapes (Fix 4)
  // ---------------------------------------------------------------------------

  @Test
  fun `orphan leading assistant turn is dropped rather than crashing`() {
    val messages = JSONArray(
      """
      [{"role":"assistant","content":"stray reply with no prior user turn"},
       {"role":"user","content":"Q1"},
       {"role":"assistant","content":"A1"},
       {"role":"user","content":"Q2"}]
      """
    )
    val result = buildAnthropicPromptParts(system = null, messages = messages)
    assertEquals(2, result.history.size)
    assertEquals("user", result.history[0].role)
    assertEquals("Q1", result.history[0].text)
    assertEquals("assistant", result.history[1].role)
    assertEquals("Q2", result.lastUserText)
  }

  @Test
  fun `an unrecognized role value is skipped rather than crashing`() {
    val messages = JSONArray(
      """
      [{"role":"user","content":"Q1"},
       {"role":"system","content":"this is not user or assistant"},
       {"role":"assistant","content":"A1"},
       {"role":"user","content":"Q2"}]
      """
    )
    val result = buildAnthropicPromptParts(system = null, messages = messages)
    assertEquals(2, result.history.size)
    assertEquals("Q1", result.history[0].text)
    assertEquals("A1", result.history[1].text)
    assertEquals("Q2", result.lastUserText)
  }

  @Test
  fun `a content field that is neither String nor JSONArray is handled gracefully`() {
    val messages = JSONArray()
    messages.put(JSONObject().put("role", "user").put("content", "Q1"))
    messages.put(JSONObject().put("role", "assistant").put("content", JSONObject.NULL))
    messages.put(JSONObject().put("role", "user").put("content", "Q2"))
    val result = buildAnthropicPromptParts(system = null, messages = messages)
    assertEquals(2, result.history.size)
    assertEquals("user", result.history[0].role)
    assertEquals("Q1", result.history[0].text)
    assertEquals("assistant", result.history[1].role)
    assertEquals("", result.history[1].text)
    assertEquals("Q2", result.lastUserText)
  }

  @Test
  fun `an image decode failure preserves the existing image rather than crashing the whole parse`() {
    val goodImage = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
    val badImage = Base64.getEncoder().encodeToString(byteArrayOf(9, 9, 9))
    val messages = JSONArray(
      """
      [{"role":"user","content":[
          {"type":"text","text":"first"},
          {"type":"image","source":{"type":"base64","media_type":"image/png","data":"$goodImage"}},
          {"type":"image","source":{"type":"base64","media_type":"image/png","data":"$badImage"}}
       ]}]
      """
    )
    var calls = 0
    val result = buildAnthropicPromptParts(
      system = null,
      messages = messages,
      decode = { b64 ->
        calls++
        if (calls == 1) Base64.getDecoder().decode(b64) else throw RuntimeException("decode failed")
      },
    )
    // The second (throwing) decode must not clobber the first successfully-decoded image.
    assertArrayEquals(byteArrayOf(1, 2, 3), result.lastUserImage)
  }
}
