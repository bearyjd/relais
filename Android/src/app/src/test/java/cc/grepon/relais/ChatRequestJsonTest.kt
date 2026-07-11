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

import cc.grepon.relais.chat.buildChatRequestJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Device-free JVM tests for [buildChatRequestJson] (extracted from `HttpChatTransport`). */
class ChatRequestJsonTest {
  private fun lastMessage(json: String): JSONObject {
    val root = JSONObject(json)
    val messages = root.getJSONArray("messages")
    return messages.getJSONObject(messages.length() - 1)
  }

  @Test
  fun `text-only user turn emits plain string content`() {
    val json =
      buildChatRequestJson(
        history = emptyList(),
        userText = "hello there",
        imagePng = null,
        audioWav = null,
        stream = true,
      )
    val root = JSONObject(json)
    assertEquals(true, root.getBoolean("stream"))
    val last = lastMessage(json)
    assertEquals("user", last.getString("role"))
    assertEquals("hello there", last.getString("content"))
  }

  @Test
  fun `image-only user turn emits text and image_url parts`() {
    val json =
      buildChatRequestJson(
        history = emptyList(),
        userText = "what is this",
        imagePng = byteArrayOf(1, 2, 3),
        audioWav = null,
      )
    val content = lastMessage(json).getJSONArray("content")
    var sawText = false
    var sawImage = false
    for (i in 0 until content.length()) {
      val part = content.getJSONObject(i)
      when (part.getString("type")) {
        "text" -> sawText = true
        "image_url" -> {
          sawImage = true
          val url = part.getJSONObject("image_url").getString("url")
          assertTrue(url.startsWith("data:image/png;base64,"))
        }
      }
    }
    assertTrue(sawText)
    assertTrue(sawImage)
  }

  @Test
  fun `audio-only user turn emits input_audio part`() {
    val json =
      buildChatRequestJson(
        history = emptyList(),
        userText = "transcribe this",
        imagePng = null,
        audioWav = byteArrayOf(4, 5, 6, 7),
      )
    val content = lastMessage(json).getJSONArray("content")
    var sawAudio = false
    for (i in 0 until content.length()) {
      val part = content.getJSONObject(i)
      if (part.getString("type") == "input_audio") {
        sawAudio = true
        val inputAudio = part.getJSONObject("input_audio")
        assertTrue(inputAudio.getString("data").isNotEmpty())
        assertEquals("wav", inputAudio.getString("format"))
      }
    }
    assertTrue(sawAudio)
  }

  @Test
  fun `image and audio user turn emits text, image_url, and input_audio parts`() {
    val json =
      buildChatRequestJson(
        history = emptyList(),
        userText = "describe and transcribe",
        imagePng = byteArrayOf(1, 2, 3),
        audioWav = byteArrayOf(4, 5, 6, 7),
      )
    val content = lastMessage(json).getJSONArray("content")
    val types = (0 until content.length()).map { content.getJSONObject(it).getString("type") }.toSet()
    assertEquals(setOf("text", "image_url", "input_audio"), types)
  }

  @Test
  fun `history turns serialize as role and content string`() {
    val history =
      listOf(
        ParsedTurn(role = "user", text = "hi"),
        ParsedTurn(role = "assistant", text = "hello back"),
      )
    val json = buildChatRequestJson(history = history, userText = "next", imagePng = null, audioWav = null)
    val messages = JSONObject(json).getJSONArray("messages")
    val first = messages.getJSONObject(0)
    assertEquals("user", first.getString("role"))
    assertEquals("hi", first.getString("content"))
    val second = messages.getJSONObject(1)
    assertEquals("assistant", second.getString("role"))
    assertEquals("hello back", second.getString("content"))
  }
}
