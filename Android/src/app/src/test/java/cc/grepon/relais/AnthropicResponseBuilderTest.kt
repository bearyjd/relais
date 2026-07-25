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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the Anthropic Messages API response/SSE-builder functions in
 * `RelaisAnthropicParser.kt` (issue #179): [buildAnthropicError], [buildAnthropicContentBlocks],
 * [buildAnthropicMessageResponse], [buildMessageStartEvent], [buildContentBlockStartEvent],
 * [buildThinkingDeltaEvent], [buildTextDeltaEvent], [buildContentBlockStopEvent],
 * [buildMessageDeltaEvent], [buildMessageStopEvent], [newAnthropicMessageId]. Mirrors
 * [AnthropicRequestParserTest]'s style/conventions. All device-free: no Context, no Robolectric.
 */
class AnthropicResponseBuilderTest {

  // ---------------------------------------------------------------------------
  // buildAnthropicError
  // ---------------------------------------------------------------------------

  @Test
  fun `buildAnthropicError shapes the type-error envelope`() {
    val json = buildAnthropicError("max_tokens: field required", "invalid_request_error")
    assertEquals("error", json.getString("type"))
    val err = json.getJSONObject("error")
    assertEquals("invalid_request_error", err.getString("type"))
    assertEquals("max_tokens: field required", err.getString("message"))
  }

  // ---------------------------------------------------------------------------
  // buildAnthropicContentBlocks
  // ---------------------------------------------------------------------------

  @Test
  fun `buildAnthropicContentBlocks for plain text only`() {
    val result = RelaisResult(text = "hi there", backend = RelaisBackend.GPU_LITERTLM, decodeTokensPerSec = 1.0, completionTokens = 2)
    val blocks = buildAnthropicContentBlocks(result)
    assertEquals(1, blocks.length())
    assertEquals("text", blocks.getJSONObject(0).getString("type"))
    assertEquals("hi there", blocks.getJSONObject(0).getString("text"))
  }

  @Test
  fun `buildAnthropicContentBlocks orders thinking then text then tool_use`() {
    val result = RelaisResult(
      text = "the answer",
      backend = RelaisBackend.GPU_LITERTLM,
      decodeTokensPerSec = 1.0,
      completionTokens = 5,
      reasoning = "let me think",
      toolCalls = listOf(ParsedToolCall("call_1", "get_weather", """{"city":"Boston"}""")),
    )
    val blocks = buildAnthropicContentBlocks(result)
    assertEquals(3, blocks.length())
    assertEquals("thinking", blocks.getJSONObject(0).getString("type"))
    assertEquals("let me think", blocks.getJSONObject(0).getString("thinking"))
    assertEquals("text", blocks.getJSONObject(1).getString("type"))
    assertEquals("the answer", blocks.getJSONObject(1).getString("text"))
    val toolBlock = blocks.getJSONObject(2)
    assertEquals("tool_use", toolBlock.getString("type"))
    assertEquals("call_1", toolBlock.getString("id"))
    assertEquals("get_weather", toolBlock.getString("name"))
    assertEquals("Boston", toolBlock.getJSONObject("input").getString("city"))
  }

  @Test
  fun `buildAnthropicContentBlocks omits blank reasoning and blank text`() {
    val result = RelaisResult(
      text = "",
      backend = RelaisBackend.GPU_LITERTLM,
      decodeTokensPerSec = 1.0,
      completionTokens = 0,
      reasoning = "   ",
      toolCalls = listOf(ParsedToolCall("call_1", "get_weather", "{}")),
    )
    val blocks = buildAnthropicContentBlocks(result)
    assertEquals(1, blocks.length())
    assertEquals("tool_use", blocks.getJSONObject(0).getString("type"))
  }

  @Test
  fun `buildAnthropicContentBlocks falls back to an empty object for unparseable tool arguments`() {
    val result = RelaisResult(
      text = "",
      backend = RelaisBackend.GPU_LITERTLM,
      decodeTokensPerSec = 1.0,
      completionTokens = 0,
      toolCalls = listOf(ParsedToolCall("call_1", "get_weather", "not json")),
    )
    val blocks = buildAnthropicContentBlocks(result)
    assertEquals(0, blocks.getJSONObject(0).getJSONObject("input").length())
  }

  // ---------------------------------------------------------------------------
  // buildAnthropicMessageResponse
  // ---------------------------------------------------------------------------

  @Test
  fun `buildAnthropicMessageResponse shapes the full non-streaming response`() {
    val result = RelaisResult(text = "hello", backend = RelaisBackend.GPU_LITERTLM, decodeTokensPerSec = 1.0, completionTokens = 3)
    val resp = buildAnthropicMessageResponse("msg_123", "gemma-4-e4b", result, inputTokens = 7)
    assertEquals("msg_123", resp.getString("id"))
    assertEquals("message", resp.getString("type"))
    assertEquals("assistant", resp.getString("role"))
    assertEquals("gemma-4-e4b", resp.getString("model"))
    assertEquals(1, resp.getJSONArray("content").length())
    assertEquals("end_turn", resp.getString("stop_reason"))
    assertTrue(resp.isNull("stop_sequence"))
    assertEquals(7, resp.getJSONObject("usage").getInt("input_tokens"))
    assertEquals(3, resp.getJSONObject("usage").getInt("output_tokens"))
  }

  // ---------------------------------------------------------------------------
  // streaming event builders
  // ---------------------------------------------------------------------------

  @Test
  fun `buildMessageStartEvent shapes the message_start payload`() {
    val event = buildMessageStartEvent("msg_1", "gemma-4-e4b", inputTokens = 10)
    assertEquals("message_start", event.getString("type"))
    val message = event.getJSONObject("message")
    assertEquals("msg_1", message.getString("id"))
    assertEquals("message", message.getString("type"))
    assertEquals("assistant", message.getString("role"))
    assertEquals("gemma-4-e4b", message.getString("model"))
    assertEquals(0, message.getJSONArray("content").length())
    assertTrue(message.isNull("stop_reason"))
    assertTrue(message.isNull("stop_sequence"))
    assertEquals(10, message.getJSONObject("usage").getInt("input_tokens"))
    assertEquals(0, message.getJSONObject("usage").getInt("output_tokens"))
  }

  @Test
  fun `buildContentBlockStartEvent for thinking and text carry an empty seed field`() {
    val thinking = buildContentBlockStartEvent(0, "thinking")
    assertEquals("content_block_start", thinking.getString("type"))
    assertEquals(0, thinking.getInt("index"))
    assertEquals("thinking", thinking.getJSONObject("content_block").getString("type"))
    assertEquals("", thinking.getJSONObject("content_block").getString("thinking"))

    val text = buildContentBlockStartEvent(1, "text")
    assertEquals("text", text.getJSONObject("content_block").getString("type"))
    assertEquals("", text.getJSONObject("content_block").getString("text"))
  }

  @Test
  fun `buildContentBlockStartEvent for tool_use carries id and name (Fix 1)`() {
    val event = buildContentBlockStartEvent(2, "tool_use", id = "toolu_abc", name = "get_weather")
    val block = event.getJSONObject("content_block")
    assertEquals("tool_use", block.getString("type"))
    assertEquals("toolu_abc", block.getString("id"))
    assertEquals("get_weather", block.getString("name"))
    assertEquals(0, block.getJSONObject("input").length())
  }

  @Test
  fun `buildContentBlockStartEvent for tool_use without id-name omits those fields`() {
    val event = buildContentBlockStartEvent(2, "tool_use")
    val block = event.getJSONObject("content_block")
    assertTrue("id" !in block.keys().asSequence().toList())
    assertTrue("name" !in block.keys().asSequence().toList())
  }

  @Test
  fun `buildThinkingDeltaEvent shapes the thinking_delta payload`() {
    val event = buildThinkingDeltaEvent(0, "pondering")
    assertEquals("content_block_delta", event.getString("type"))
    assertEquals(0, event.getInt("index"))
    val delta = event.getJSONObject("delta")
    assertEquals("thinking_delta", delta.getString("type"))
    assertEquals("pondering", delta.getString("thinking"))
  }

  @Test
  fun `buildTextDeltaEvent shapes the text_delta payload`() {
    val event = buildTextDeltaEvent(1, "hello")
    assertEquals("content_block_delta", event.getString("type"))
    assertEquals(1, event.getInt("index"))
    val delta = event.getJSONObject("delta")
    assertEquals("text_delta", delta.getString("type"))
    assertEquals("hello", delta.getString("text"))
  }

  @Test
  fun `buildContentBlockStopEvent shapes the stop payload`() {
    val event = buildContentBlockStopEvent(1)
    assertEquals("content_block_stop", event.getString("type"))
    assertEquals(1, event.getInt("index"))
  }

  @Test
  fun `buildMessageDeltaEvent shapes the terminal stop_reason and usage payload`() {
    val event = buildMessageDeltaEvent("end_turn", outputTokens = 42)
    assertEquals("message_delta", event.getString("type"))
    val delta = event.getJSONObject("delta")
    assertEquals("end_turn", delta.getString("stop_reason"))
    assertTrue(delta.isNull("stop_sequence"))
    assertEquals(42, event.getJSONObject("usage").getInt("output_tokens"))
  }

  @Test
  fun `buildMessageStopEvent shapes the message_stop payload`() {
    val event = buildMessageStopEvent()
    assertEquals("message_stop", event.getString("type"))
  }

  // ---------------------------------------------------------------------------
  // newAnthropicMessageId
  // ---------------------------------------------------------------------------

  @Test
  fun `newAnthropicMessageId is non-blank and prefixed`() {
    val id = newAnthropicMessageId()
    assertTrue(id.isNotBlank())
    assertTrue(id.startsWith("msg_"))
  }
}
