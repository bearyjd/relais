/*
 * Copyright (C) 2026 Entrevoix / grepon.cc
 *
 * This file is part of Relais.
 *
 * Relais is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * Relais is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along
 * with Relais. If not, see <https://www.gnu.org/licenses/>.
 */

package cc.grepon.relais

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic JVM tests for the byte-safe HTTP request reader + multipart parser ([RelaisHttpIo]).
 * These are the load-bearing correctness guarantees for the binary `POST /v1/audio/transcriptions`
 * upload path: header lines stay ASCII/line-oriented while the body survives as raw bytes.
 */
class RelaisHttpIoTest {

  private fun reader(bytes: ByteArray) = HttpRequestReader(ByteArrayInputStream(bytes))

  /** Concatenates ISO-8859-1 strings, raw byte arrays, and single byte ints into one ByteArray. */
  private fun buf(vararg parts: Any): ByteArray {
    val out = ByteArrayOutputStream()
    for (p in parts) when (p) {
      is String -> out.write(p.toByteArray(Charsets.ISO_8859_1))
      is ByteArray -> out.write(p)
      is Int -> out.write(p)
      else -> error("unsupported part: $p")
    }
    return out.toByteArray()
  }

  // ---- HttpRequestReader.readLine ----

  @Test
  fun `readLine splits on CRLF and strips the CR`() {
    val r = reader("GET / HTTP/1.1\r\nHost: relais\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
    assertEquals("GET / HTTP/1.1", r.readLine())
    assertEquals("Host: relais", r.readLine())
    assertEquals("", r.readLine()) // blank line terminating the header block
    assertNull("stream is exhausted after the blank line", r.readLine())
  }

  @Test
  fun `readLine splits on a bare LF too`() {
    val r = reader("a\nb\nc\n".toByteArray(Charsets.ISO_8859_1))
    assertEquals("a", r.readLine())
    assertEquals("b", r.readLine())
    assertEquals("c", r.readLine())
    assertNull(r.readLine())
  }

  @Test
  fun `readLine returns a final line with no trailing newline then null at EOF`() {
    val r = reader("first\r\nlast-no-newline".toByteArray(Charsets.ISO_8859_1))
    assertEquals("first", r.readLine())
    assertEquals("last-no-newline", r.readLine())
    assertNull(r.readLine())
  }

  @Test
  fun `readLine returns null immediately on an empty stream`() {
    assertNull(reader(ByteArray(0)).readLine())
  }

  // ---- HttpRequestReader.readBodyBytes ----

  @Test
  fun `readBodyBytes preserves a full 0 to 255 byte sweep verbatim`() {
    val body = ByteArray(256) { it.toByte() } // every byte value 0x00..0xFF
    val r = reader(body)
    assertArrayEquals(body, r.readBodyBytes(256))
  }

  @Test
  fun `readBodyBytes survives NUL FF LF CR after ASCII header lines`() {
    val body = byteArrayOf(0x00, 0xFF.toByte(), 0x0A, 0x0D, 0x2D, 0x2D, 0x7F, 0x80.toByte())
    val r = reader(buf("Content-Length: 8\r\n", "X-Header: v\r\n", "\r\n", body))
    assertEquals("Content-Length: 8", r.readLine())
    assertEquals("X-Header: v", r.readLine())
    assertEquals("", r.readLine())
    assertArrayEquals(body, r.readBodyBytes(body.size))
  }

  @Test
  fun `readBodyBytes reads exactly length so successive reads split the stream`() {
    val r = reader("HELLOWORLD".toByteArray(Charsets.ISO_8859_1))
    assertArrayEquals("HELLO".toByteArray(), r.readBodyBytes(5))
    assertArrayEquals("WORLD".toByteArray(), r.readBodyBytes(5))
  }

  @Test
  fun `readBodyBytes returns what is available when the stream ends early`() {
    val r = reader("abc".toByteArray(Charsets.ISO_8859_1))
    assertArrayEquals("abc".toByteArray(), r.readBodyBytes(100))
  }

  @Test
  fun `readBodyBytes returns empty for a non-positive length`() {
    val r = reader("abc".toByteArray(Charsets.ISO_8859_1))
    assertArrayEquals(ByteArray(0), r.readBodyBytes(0))
    assertArrayEquals(ByteArray(0), r.readBodyBytes(-5))
  }

  // ---- parseMultipartBoundary ----

  @Test
  fun `parseMultipartBoundary extracts an unquoted boundary`() {
    assertEquals("abc123", parseMultipartBoundary("multipart/form-data; boundary=abc123"))
  }

  @Test
  fun `parseMultipartBoundary strips surrounding quotes`() {
    assertEquals("abc 123", parseMultipartBoundary("multipart/form-data; boundary=\"abc 123\""))
  }

  @Test
  fun `parseMultipartBoundary stops at a trailing parameter`() {
    assertEquals("XYZ", parseMultipartBoundary("multipart/form-data; boundary=XYZ; charset=utf-8"))
  }

  @Test
  fun `parseMultipartBoundary returns null for non-multipart or missing boundary`() {
    assertNull(parseMultipartBoundary("application/json"))
    assertNull(parseMultipartBoundary("multipart/form-data"))
  }

  // ---- parseMultipartFormData ----

  private val boundary = "----RelaisBoundary7MA4YWxkTrZu0gW"

  @Test
  fun `parseMultipartFormData parses a text field and a binary file field`() {
    val fileBytes = byteArrayOf(0x00, 0x01, 0xFF.toByte(), 0x0A, 0x0D, 0x2D, 0x2D, 0x7F, 0x80.toByte())
    val body = buf(
      "--$boundary\r\n",
      "Content-Disposition: form-data; name=\"model\"\r\n\r\n",
      "gemma-4-e4b-it\r\n",
      "--$boundary\r\n",
      "Content-Disposition: form-data; name=\"file\"; filename=\"clip.wav\"\r\n",
      "Content-Type: audio/wav\r\n\r\n",
      fileBytes,
      "\r\n",
      "--$boundary--\r\n",
    )
    val parts = parseMultipartFormData(body, boundary)
    assertEquals(2, parts.size)

    val model = parts[0]
    assertEquals("model", model.name)
    assertNull(model.filename)
    assertNull(model.contentType)
    assertEquals("gemma-4-e4b-it", String(model.bytes, Charsets.UTF_8))

    val file = parts[1]
    assertEquals("file", file.name)
    assertEquals("clip.wav", file.filename)
    assertEquals("audio/wav", file.contentType)
    assertArrayEquals("binary body must survive byte-for-byte", fileBytes, file.bytes)
  }

  @Test
  fun `parseMultipartFormData tolerates a missing final CRLF after the closing boundary`() {
    val fileBytes = byteArrayOf(0x10, 0x20, 0x30)
    val body = buf(
      "--$boundary\r\n",
      "Content-Disposition: form-data; name=\"file\"; filename=\"a.wav\"\r\n\r\n",
      fileBytes,
      "\r\n",
      "--$boundary--", // no trailing CRLF / epilogue
    )
    val parts = parseMultipartFormData(body, boundary)
    assertEquals(1, parts.size)
    assertEquals("file", parts[0].name)
    assertArrayEquals(fileBytes, parts[0].bytes)
  }

  @Test
  fun `parseMultipartFormData decodes a UTF-8 text field via the part bytes`() {
    val utf8 = "café — 日本語".toByteArray(Charsets.UTF_8)
    val body = buf(
      "--$boundary\r\n",
      "Content-Disposition: form-data; name=\"prompt\"\r\n\r\n",
      utf8,
      "\r\n",
      "--$boundary--\r\n",
    )
    val parts = parseMultipartFormData(body, boundary)
    assertEquals(1, parts.size)
    assertEquals("prompt", parts[0].name)
    assertEquals("café — 日本語", String(parts[0].bytes, Charsets.UTF_8))
  }

  @Test
  fun `parseMultipartFormData accepts a quoted boundary token`() {
    val extracted = parseMultipartBoundary("multipart/form-data; boundary=\"$boundary\"")
    assertEquals(boundary, extracted)
    val body = buf(
      "--$boundary\r\n",
      "Content-Disposition: form-data; name=\"file\"; filename=\"x.wav\"\r\n\r\n",
      byteArrayOf(1, 2, 3),
      "\r\n",
      "--$boundary--\r\n",
    )
    val parts = parseMultipartFormData(body, requireNotNull(extracted))
    assertEquals(1, parts.size)
    assertArrayEquals(byteArrayOf(1, 2, 3), parts[0].bytes)
  }

  @Test
  fun `parseMultipartFormData ignores preamble and epilogue`() {
    val body = buf(
      "preamble text to ignore\r\n",
      "--$boundary\r\n",
      "Content-Disposition: form-data; name=\"file\"; filename=\"x.wav\"\r\n\r\n",
      byteArrayOf(9, 8, 7),
      "\r\n",
      "--$boundary--\r\n",
      "trailing epilogue to ignore\r\n",
    )
    val parts = parseMultipartFormData(body, boundary)
    assertEquals(1, parts.size)
    assertArrayEquals(byteArrayOf(9, 8, 7), parts[0].bytes)
  }

  @Test
  fun `parseMultipartFormData returns empty for an empty boundary`() {
    assertTrue(parseMultipartFormData("--x\r\n\r\n".toByteArray(), "").isEmpty())
  }

  // ---- Resource-bound hardening (security review: parse-amplification DoS) ----

  @Test
  fun `parseMultipartBoundary rejects an over-long boundary (RFC 2046 caps at 70)`() {
    val ok = "a".repeat(70)
    val tooLong = "a".repeat(71)
    assertEquals(ok, parseMultipartBoundary("multipart/form-data; boundary=$ok"))
    // A ~16KB boundary (bounded only by the header cap) would drive the naive scan quadratic — reject it.
    assertNull(parseMultipartBoundary("multipart/form-data; boundary=$tooLong"))
  }

  @Test
  fun `parseMultipartFormData caps the part count on boundary-heavy input`() {
    // 40 well-formed parts: the parser must return at most MULTIPART_MAX_PARTS (16) and must not
    // collect an unbounded offset list (the memory-amplification guard). Terminating here at all is
    // itself the assertion that the bounded scan loop doesn't run away.
    val sb = StringBuilder()
    repeat(40) { i ->
      sb.append("--b\r\n")
        .append("Content-Disposition: form-data; name=\"f$i\"\r\n\r\n")
        .append("v$i\r\n")
    }
    sb.append("--b--\r\n")
    val parts = parseMultipartFormData(sb.toString().toByteArray(Charsets.ISO_8859_1), "b")
    assertTrue("part count must be capped at 16, was ${parts.size}", parts.size <= 16)
  }
}
