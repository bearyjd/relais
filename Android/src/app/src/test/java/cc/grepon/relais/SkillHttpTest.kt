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

import cc.grepon.relais.customtasks.agentchat.SkillHttp
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [SkillHttp] — the HTTP/1.1 GET exchange used by the pinned skill fetch. The
 * response is fed from a byte array (no socket), and the request is captured, so parsing is fully
 * deterministic + offline.
 */
class SkillHttpTest {

  private fun run(response: String, maxBytes: Int = SkillHttp.MAX_BODY_BYTES): Pair<SkillHttp.Outcome, String> {
    val out = ByteArrayOutputStream()
    val input = ByteArrayInputStream(response.toByteArray(Charsets.ISO_8859_1))
    val outcome = SkillHttp.fetch("example.com", "/myskill/SKILL.md", out, input, maxBytes)
    return outcome to out.toString("US-ASCII")
  }

  @Test fun `200 with content-length returns the body`() {
    val body = "# My Skill\nhello"
    val (outcome, _) = run("HTTP/1.1 200 OK\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body")
    assertEquals(SkillHttp.Outcome.Ok(body), outcome)
  }

  @Test fun `request line and Host header are written correctly`() {
    val (_, request) = run("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nhi")
    assertTrue(request, request.startsWith("GET /myskill/SKILL.md HTTP/1.1\r\n"))
    assertTrue(request, request.contains("Host: example.com\r\n"))
    assertTrue(request, request.contains("Connection: close\r\n"))
  }

  @Test fun `200 chunked body is de-chunked`() {
    val response =
      "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n" +
        "5\r\nHello\r\n" +
        "7\r\n, World\r\n" +
        "0\r\n\r\n"
    val (outcome, _) = run(response)
    assertEquals(SkillHttp.Outcome.Ok("Hello, World"), outcome)
  }

  @Test fun `200 close-delimited body reads to EOF`() {
    val body = "no length header here"
    val (outcome, _) = run("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n\r\n$body")
    assertEquals(SkillHttp.Outcome.Ok(body), outcome)
  }

  @Test fun `3xx is refused as Redirected with the Location`() {
    val (outcome, _) = run("HTTP/1.1 302 Found\r\nLocation: https://evil.example/x\r\nContent-Length: 0\r\n\r\n")
    assertEquals(SkillHttp.Outcome.Redirected("https://evil.example/x"), outcome)
  }

  @Test fun `non-2xx is an HttpError with the code`() {
    val (outcome, _) = run("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n")
    assertEquals(SkillHttp.Outcome.HttpError(404), outcome)
  }

  @Test fun `content-length over the cap is TooLarge`() {
    val (outcome, _) = run("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n" + "x".repeat(100), maxBytes = 8)
    assertEquals(SkillHttp.Outcome.TooLarge, outcome)
  }

  @Test fun `close-delimited body over the cap is TooLarge`() {
    val (outcome, _) = run("HTTP/1.1 200 OK\r\n\r\n" + "x".repeat(100), maxBytes = 8)
    assertEquals(SkillHttp.Outcome.TooLarge, outcome)
  }

  @Test fun `no header terminator is Malformed`() {
    val (outcome, _) = run("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n") // never closes the header block
    assertTrue(outcome is SkillHttp.Outcome.Malformed)
  }

  @Test fun `header matching is case-insensitive`() {
    val body = "ok"
    val (outcome, _) = run("HTTP/1.1 200 OK\r\ncontent-length: 2\r\n\r\n$body")
    assertEquals(SkillHttp.Outcome.Ok(body), outcome)
  }
}
