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

import java.io.ByteArrayOutputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SseWriterTest {

  private fun capture(block: (SseWriter) -> Unit): String {
    val buf = ByteArrayOutputStream()
    block(SseWriter(buf))
    return buf.toString(Charsets.UTF_8.name())
  }

  @Test fun `commitHeader writes a 200 SSE response header`() {
    val written = capture { it.commitHeader() }
    assertTrue(written.startsWith("HTTP/1.1 200 OK\r\n"))
    assertTrue(written.contains("Content-Type: text/event-stream\r\n"))
    assertTrue(written.endsWith("\r\n\r\n"))
  }

  @Test fun `send writes one data event with a trailing blank line`() {
    val written = capture { it.send(JSONObject().put("foo", "bar")) }
    assertEquals("data: {\"foo\":\"bar\"}\n\n", written)
  }

  @Test fun `done writes the terminal DONE event`() {
    val written = capture { it.done() }
    assertEquals("data: [DONE]\n\n", written)
  }

  @Test fun `abort writes a data error event with the given message`() {
    val written = capture { it.abort("stream aborted") }
    assertEquals("data: {\"error\":\"stream aborted\"}\n\n", written)
  }

  @Test fun `abort swallows a write failure instead of throwing`() {
    val poison = object : java.io.OutputStream() {
      override fun write(b: Int) = throw java.io.IOException("broken pipe")
    }
    SseWriter(poison).abort() // must not throw
  }

  @Test fun `header then multiple sends then done compose as one stream`() {
    val written = capture {
      it.commitHeader()
      it.send(JSONObject().put("i", 1))
      it.send(JSONObject().put("i", 2))
      it.done()
    }
    assertTrue(written.contains("data: {\"i\":1}\n\n"))
    assertTrue(written.contains("data: {\"i\":2}\n\n"))
    assertTrue(written.endsWith("data: [DONE]\n\n"))
  }
}
