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

package cc.grepon.relais.chat

import cc.grepon.relais.ParsedTurn
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the OpenAI `/v1/chat/completions` request body JSON string: prior [history] as text-only
 * turns (`{role, content}`), plus the new user turn. When the user turn carries [imagePng]/
 * [audioWav], its `content` is emitted as an array of OpenAI content-parts (`text`, `image_url`,
 * `input_audio`); otherwise `content` stays a plain string, matching the server's text-only fast
 * path. Extracted from `HttpChatTransport.buildChatRequestBody` so it is pure (`java.util.Base64`,
 * minSdk 31 — not `android.util.Base64`) and JVM-testable without Robolectric.
 */
fun buildChatRequestJson(
  history: List<ParsedTurn>,
  userText: String,
  imagePng: ByteArray?,
  audioWav: ByteArray?,
  stream: Boolean = true,
): String {
  val messages = JSONArray()
  history.forEach { turn -> messages.put(JSONObject().put("role", turn.role).put("content", turn.text)) }
  val hasMedia = imagePng != null || audioWav != null
  val userContent: Any =
    if (!hasMedia) {
      userText
    } else {
      val parts = JSONArray()
      parts.put(JSONObject().put("type", "text").put("text", userText))
      imagePng?.let { png ->
        val dataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(png)
        parts.put(
          JSONObject()
            .put("type", "image_url")
            .put("image_url", JSONObject().put("url", dataUri)),
        )
      }
      audioWav?.let { wav ->
        parts.put(
          JSONObject()
            .put("type", "input_audio")
            .put(
              "input_audio",
              JSONObject()
                .put("data", Base64.getEncoder().encodeToString(wav))
                .put("format", "wav"),
            ),
        )
      }
      parts
    }
  messages.put(JSONObject().put("role", "user").put("content", userContent))
  return JSONObject().put("messages", messages).put("stream", stream).toString()
}
