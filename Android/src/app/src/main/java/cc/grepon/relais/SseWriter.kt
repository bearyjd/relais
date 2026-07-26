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

import java.io.OutputStream
import org.json.JSONObject

/**
 * The ONE `text/event-stream` writer for both streaming paths in [RelaisHttpServer] (issue #173
 * item 4) — the 200 SSE header write and the post-header abort-catch were duplicated between the
 * plain-chat and tool-completion streaming handlers. Not thread-safe; one instance per request.
 *
 * Also backs the Anthropic Messages API's named-event SSE stream (issue #179): unlike OpenAI's bare
 * `data: <json>` framing terminated by a `data: [DONE]` sentinel, Anthropic requires an `event: <type>`
 * line before each `data:` line and has no `[DONE]` terminator (the stream just ends after a
 * `message_stop` event, or emits an `event: error` pair on failure). [send] (bare, OpenAI-shaped) and
 * [done] are UNCHANGED — the Anthropic handler uses [send] (event, json) and [sendError] instead and
 * never calls [done].
 */
class SseWriter(private val out: OutputStream) {

  /** Writes the 200 SSE response header. Call exactly once, before any [send]/[done]/[abort]. */
  fun commitHeader() {
    out.write(
      ("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\n" +
        "Connection: close\r\n\r\n").toByteArray()
    )
    out.flush()
  }

  /** Writes one bare `data: <json>` SSE event (OpenAI shape). */
  fun send(json: JSONObject) {
    out.write("data: $json\n\n".toByteArray())
    out.flush()
  }

  /** Writes one named `event: <event>` / `data: <json>` SSE event pair (Anthropic shape). */
  fun send(event: String, json: JSONObject) {
    out.write("event: $event\ndata: $json\n\n".toByteArray())
    out.flush()
  }

  /** Writes the terminal `data: [DONE]` SSE event (OpenAI shape only — Anthropic has no sentinel). */
  fun done() {
    out.write("data: [DONE]\n\n".toByteArray())
    out.flush()
  }

  /**
   * Best-effort SSE error event for a failure AFTER the 200 header is already committed (the outer
   * HTTP-status catch can't run at that point without double-writing a status/double-counting the
   * request). Swallows any write failure — the connection may already be gone.
   */
  fun abort(message: String = "stream aborted") {
    runCatching { out.write("data: {\"error\":\"$message\"}\n\n".toByteArray()); out.flush() }
  }

  /**
   * Anthropic-shaped best-effort SSE error event for a failure AFTER the 200 header is already
   * committed — the [abort] equivalent for the named-event stream. Swallows any write failure (mirrors
   * [abort]'s failure-swallowing; the connection may already be gone).
   */
  fun sendError(errorType: String, message: String) {
    runCatching {
      val payload = JSONObject()
        .put("type", "error")
        .put("error", JSONObject().put("type", errorType).put("message", message))
      out.write("event: error\ndata: $payload\n\n".toByteArray())
      out.flush()
    }
  }
}
