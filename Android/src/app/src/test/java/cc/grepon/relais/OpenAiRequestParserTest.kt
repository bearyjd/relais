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

import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [buildPromptParts] (Feature 03 — multi-turn messages).
 *
 * All tests are device-free: no Context, no Android SDK, no Robolectric.
 * [buildPromptParts] is an internal top-level function in RelaisOpenAiParser.kt.
 */
class OpenAiRequestParserTest {

  // ---------------------------------------------------------------------------
  // Case 1 — system-only (no user turns)
  // ---------------------------------------------------------------------------

  @Test
  fun `system-only messages returns system prompt and empty history`() {
    val messages = JSONArray("""[{"role":"system","content":"Be concise."}]""")
    val result = buildPromptParts(messages)
    assertEquals("Be concise.", result.systemPrompt)
    assertTrue(result.history.isEmpty())
    assertEquals("", result.lastUserText)
    assertNull(result.lastUserImage)
    assertNull(result.lastUserAudio)
  }

  // ---------------------------------------------------------------------------
  // Case 2 — system + single user (canonical first request)
  // ---------------------------------------------------------------------------

  @Test
  fun `system prompt and single user turn extracted correctly`() {
    val messages = JSONArray("""
      [{"role":"system","content":"You are a pirate."},
       {"role":"user","content":"Ahoy!"}]
    """)
    val result = buildPromptParts(messages)
    assertEquals("You are a pirate.", result.systemPrompt)
    assertTrue("single user turn must not appear in history", result.history.isEmpty())
    assertEquals("Ahoy!", result.lastUserText)
    assertNull(result.lastUserImage)
    assertNull(result.lastUserAudio)
  }

  // ---------------------------------------------------------------------------
  // Case 3 — multi-turn history preserved
  // ---------------------------------------------------------------------------

  @Test
  fun `multi-turn history accumulated in order`() {
    val messages = JSONArray("""
      [{"role":"system","content":"SYS"},
       {"role":"user","content":"Q1"},
       {"role":"assistant","content":"A1"},
       {"role":"user","content":"Q2"},
       {"role":"assistant","content":"A2"},
       {"role":"user","content":"Q3"}]
    """)
    val result = buildPromptParts(messages)
    assertEquals("SYS", result.systemPrompt)
    assertEquals(4, result.history.size)
    assertEquals("Q1", result.history[0].text); assertEquals("user",      result.history[0].role)
    assertEquals("A1", result.history[1].text); assertEquals("assistant", result.history[1].role)
    assertEquals("Q2", result.history[2].text); assertEquals("user",      result.history[2].role)
    assertEquals("A2", result.history[3].text); assertEquals("assistant", result.history[3].role)
    assertEquals("Q3", result.lastUserText)
    assertNull(result.lastUserImage)
    assertNull(result.lastUserAudio)
  }

  // ---------------------------------------------------------------------------
  // Case 4 — multimodal parts preserved in last user message
  // ---------------------------------------------------------------------------

  @Test
  fun `image and audio extracted from last user message parts array`() {
    val fakeB64 = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
    val messages = JSONArray("""
      [{"role":"user","content":[
         {"type":"text","text":"describe this"},
         {"type":"image_url","image_url":{"url":"data:image/png;base64,$fakeB64"}},
         {"type":"input_audio","input_audio":{"data":"$fakeB64","format":"wav"}}
      ]}]
    """)
    val result = buildPromptParts(messages)
    assertEquals("describe this", result.lastUserText)
    assertNotNull(result.lastUserImage)
    assertNotNull(result.lastUserAudio)
    assertArrayEquals(byteArrayOf(1, 2, 3), result.lastUserImage)
    assertArrayEquals(byteArrayOf(1, 2, 3), result.lastUserAudio)
    // Multimodal content must NOT appear in history
    assertTrue(result.history.isEmpty())
  }

  // ---------------------------------------------------------------------------
  // Case 5 — overflow truncation drops oldest turns, preserves system
  // ---------------------------------------------------------------------------

  @Test
  fun `overflow truncation drops oldest turns and always keeps system prompt`() {
    // Build a history that exceeds MAX_HISTORY_CHARS.
    val longText = "x".repeat(MAX_HISTORY_CHARS / 2 + 1)
    val messages = JSONArray().apply {
      put(JSONObject().put("role", "system").put("content", "SYS"))
      put(JSONObject().put("role", "user").put("content", longText))
      put(JSONObject().put("role", "assistant").put("content", longText))
      put(JSONObject().put("role", "user").put("content", longText))
      put(JSONObject().put("role", "assistant").put("content", longText))
      put(JSONObject().put("role", "user").put("content", "live"))
    }
    val result = buildPromptParts(messages)
    assertEquals("SYS", result.systemPrompt)  // never dropped
    // history is truncated: total chars must fit in budget
    val historyChars = result.history.sumOf { it.text.length }
    assertTrue(
      "history must fit budget: $historyChars > $MAX_HISTORY_CHARS",
      historyChars <= MAX_HISTORY_CHARS,
    )
    assertEquals("live", result.lastUserText)
    // Remaining history must have even count (no dangling assistant turn).
    assertEquals(0, result.history.size % 2)
    // Surviving history must start with a user turn and alternate correctly.
    if (result.history.isNotEmpty()) {
      assertEquals("history[0] must be a user turn after truncation", "user", result.history[0].role)
      for (i in result.history.indices) {
        val expectedRole = if (i % 2 == 0) "user" else "assistant"
        assertEquals("history[$i] must alternate: expected $expectedRole", expectedRole, result.history[i].role)
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Case 6 — assistant-last (trailing assistant, no trailing user)
  // ---------------------------------------------------------------------------

  @Test
  fun `assistant-last array promotes last user to live turn, assistant goes to history`() {
    // [system, user, assistant] — the last user message is still promoted to lastUserText
    // (it is the only user turn, so lastUserIndex points to it). The trailing assistant
    // goes into history. This pins the parser's actual contract for this edge case.
    val messages = JSONArray("""
      [{"role":"system","content":"SYS"},
       {"role":"user","content":"Q1"},
       {"role":"assistant","content":"A1"}]
    """)
    val result = buildPromptParts(messages)
    assertEquals("SYS", result.systemPrompt)
    // The sole user turn is the "live" turn — it goes to lastUserText, not history.
    assertEquals("Q1", result.lastUserText)
    assertNull(result.lastUserImage)
    assertNull(result.lastUserAudio)
    // The trailing assistant turn, which has no paired user turn after it, goes to history.
    assertEquals(1, result.history.size)
    assertEquals("assistant", result.history[0].role)
    assertEquals("A1",        result.history[0].text)
  }

  // ---------------------------------------------------------------------------
  // Case 7 — empty messages array
  // ---------------------------------------------------------------------------

  @Test
  fun `empty messages array yields empty result`() {
    val result = buildPromptParts(JSONArray())
    assertNull(result.systemPrompt)
    assertTrue(result.history.isEmpty())
    assertEquals("", result.lastUserText)
    assertNull(result.lastUserImage)
    assertNull(result.lastUserAudio)
  }
}
