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

package cc.grepon.relais.batch

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure parsing/shaping for batch chat jobs (Feature #14, v1). A batch body is a chat-completions
 * request; the worker runs the last user turn with an optional system prompt. v1 supports TEXT content
 * only (a string, or an array's `text` parts) — multimodal/tools batch is a follow-up.
 */
object BatchChat {

  data class Request(
    val text: String,
    val system: String?,
    val temperature: Double?,
    val topP: Double?,
    val seed: Int?,
  )

  /** Extracts the text chat job from a stored `/v1/batch` body, or null if there's no usable user turn. */
  fun extract(body: JSONObject): Request? {
    val messages = body.optJSONArray("messages") ?: return null
    var system: String? = null
    var userText: String? = null
    for (i in 0 until messages.length()) {
      val m = messages.optJSONObject(i) ?: continue
      when (m.optString("role")) {
        "system" -> system = contentText(m.opt("content"))
        "user" -> userText = contentText(m.opt("content")) // last user turn wins
      }
    }
    val text = userText?.takeIf { it.isNotBlank() } ?: return null
    return Request(
      text = text,
      system = system?.takeIf { it.isNotBlank() },
      temperature = optDoubleOrNull(body, "temperature"),
      topP = optDoubleOrNull(body, "top_p"),
      seed = if (body.has("seed") && !body.isNull("seed")) body.optInt("seed") else null,
    )
  }

  /** The OpenAI-shaped completion envelope stored as the job result. */
  fun envelope(jobId: String, text: String, completionTokens: Int, finishReason: String): JSONObject =
    JSONObject()
      .put("id", "batch-$jobId")
      .put("object", "chat.completion")
      .put(
        "choices",
        JSONArray().put(
          JSONObject()
            .put("index", 0)
            .put("message", JSONObject().put("role", "assistant").put("content", text))
            .put("finish_reason", finishReason),
        ),
      )
      .put("usage", JSONObject().put("completion_tokens", completionTokens).put("total_tokens", completionTokens))

  private fun contentText(content: Any?): String? = when (content) {
    is String -> content
    is JSONArray ->
      (0 until content.length())
        .mapNotNull { content.optJSONObject(it)?.takeIf { o -> o.optString("type") == "text" }?.optString("text") }
        .joinToString(" ")
        .takeIf { it.isNotBlank() }
    else -> null
  }

  private fun optDoubleOrNull(body: JSONObject, key: String): Double? =
    if (body.has(key) && !body.isNull(key)) body.optDouble(key).takeUnless { it.isNaN() } else null
}
