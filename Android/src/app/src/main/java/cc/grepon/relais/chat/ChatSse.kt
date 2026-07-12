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

import org.json.JSONObject

private const val SSE_DATA_PREFIX = "data: "
private const val SSE_DONE_SENTINEL = "[DONE]"

/**
 * Parses one line of an OpenAI-compatible `/v1/chat/completions` SSE stream and returns the
 * assistant-visible content delta, if any.
 *
 * Returns null for: non-`data:` lines (e.g. `: comment` heartbeats), the `[DONE]` sentinel,
 * chunks with no `delta.content` (e.g. a `reasoning_content`-only delta, or a role-only chunk),
 * and any line that fails to parse as JSON.
 */
fun parseSseContentDelta(line: String): String? {
  if (!line.startsWith(SSE_DATA_PREFIX)) return null
  val remainder = line.removePrefix(SSE_DATA_PREFIX)
  if (remainder == SSE_DONE_SENTINEL) return null
  return try {
    val delta =
      JSONObject(remainder)
        .optJSONArray("choices")
        ?.optJSONObject(0)
        ?.optJSONObject("delta")
    if (delta != null && delta.has("content")) delta.optString("content") else null
  } catch (e: Exception) {
    null
  }
}

/**
 * Parses one line of an OpenAI-compatible `/v1/chat/completions` SSE stream and returns
 * `choices[0].finish_reason` (e.g. `"stop"`, `"length"`), if present and non-null.
 *
 * Returns null for: non-`data:` lines, the `[DONE]` sentinel, chunks with no (or a null)
 * `finish_reason` (e.g. an in-progress content delta), and any line that fails to parse as JSON.
 */
fun parseSseFinishReason(line: String): String? {
  if (!line.startsWith(SSE_DATA_PREFIX)) return null
  val remainder = line.removePrefix(SSE_DATA_PREFIX)
  if (remainder == SSE_DONE_SENTINEL) return null
  return try {
    val choice = JSONObject(remainder).optJSONArray("choices")?.optJSONObject(0)
    if (choice != null && choice.has("finish_reason") && !choice.isNull("finish_reason")) {
      choice.optString("finish_reason")
    } else {
      null
    }
  } catch (e: Exception) {
    null
  }
}
