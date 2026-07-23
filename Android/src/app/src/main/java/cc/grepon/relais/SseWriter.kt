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

  /** Writes one `data: <json>` SSE event. */
  fun send(json: JSONObject) {
    out.write("data: $json\n\n".toByteArray())
    out.flush()
  }

  /** Writes the terminal `data: [DONE]` SSE event. */
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
}
