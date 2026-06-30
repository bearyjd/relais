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

package cc.grepon.relais.customtasks.agentchat

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * A minimal, self-contained HTTP/1.1 GET exchange over a caller-supplied stream pair, split out so the
 * request building + response parsing are PURE and JVM-testable without a socket (see `SkillHttpTest`).
 * [SkillSourceFetcher] supplies the streams of a TLS socket already connected to a vetted, pinned IP.
 *
 * Deliberately small: it sends `Connection: close`, refuses 3xx (a redirect could retarget an
 * already-vetted fetch at an internal host — same stance as the old `instanceFollowRedirects=false`),
 * honors `Content-Length` / `Transfer-Encoding: chunked` / close-delimited bodies, and bounds the body
 * to [MAX_BODY_BYTES] so a hostile-but-cert-valid host can't stream unbounded bytes into memory. Skill
 * sources are small Markdown manifests, so this does not need a general HTTP client.
 */
object SkillHttp {

  /** Upper bound on a fetched SKILL.md body. Generous for Markdown; caps a malicious large response. */
  const val MAX_BODY_BYTES: Int = 1_048_576 // 1 MB

  /** Bound on the header block so a host that never terminates headers can't grow the buffer. */
  private const val MAX_HEADER_BYTES: Int = 64 * 1024

  sealed interface Outcome {
    /** 2xx with a decoded UTF-8 [body]. */
    data class Ok(val body: String) : Outcome
    /** A 3xx was returned; refused. [location] is the `Location` header if present. */
    data class Redirected(val location: String?) : Outcome
    /** A non-2xx, non-3xx status [code]. */
    data class HttpError(val code: Int) : Outcome
    /** The body exceeded [MAX_BODY_BYTES] (or headers exceeded their bound). */
    data object TooLarge : Outcome
    /** The response could not be parsed as HTTP/1.1. */
    data class Malformed(val detail: String) : Outcome
  }

  /**
   * Writes `GET [target]` (with `Host: [host]`, `Connection: close`) to [output], then reads + parses
   * the response from [input]. Never follows redirects. Bounds the body to [maxBytes]. Pure — no I/O
   * beyond the two streams the caller owns.
   */
  fun fetch(
    host: String,
    target: String,
    output: OutputStream,
    input: InputStream,
    maxBytes: Int = MAX_BODY_BYTES,
  ): Outcome {
    val request = buildString {
      append("GET ").append(target).append(" HTTP/1.1\r\n")
      append("Host: ").append(host).append("\r\n")
      append("User-Agent: Relais\r\n")
      append("Accept: */*\r\n")
      append("Connection: close\r\n")
      append("\r\n")
    }
    output.write(request.toByteArray(Charsets.US_ASCII))
    output.flush()

    val header = readHeaderBlock(input) ?: return Outcome.Malformed("no header terminator")
    val (headerBytes, leftover) = header
    val lines = String(headerBytes, Charsets.ISO_8859_1).split("\r\n")
    val statusLine = lines.firstOrNull() ?: return Outcome.Malformed("empty status line")
    val code = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
      ?: return Outcome.Malformed("no status code in '$statusLine'")

    val headers = lines.drop(1)
      .filter { it.isNotEmpty() }
      .mapNotNull { line ->
        val i = line.indexOf(':')
        if (i <= 0) null else line.substring(0, i).trim().lowercase() to line.substring(i + 1).trim()
      }
      .toMap()

    when (code) {
      in 300..399 -> return Outcome.Redirected(headers["location"])
      !in 200..299 -> return Outcome.HttpError(code)
    }

    val bodyBytes =
      when {
        headers["transfer-encoding"]?.lowercase()?.contains("chunked") == true ->
          readChunked(leftover, input, maxBytes) ?: return Outcome.TooLarge
        headers["content-length"]?.trim()?.toIntOrNull() != null -> {
          val len = headers["content-length"]!!.trim().toInt()
          if (len > maxBytes) return Outcome.TooLarge
          readFixed(leftover, input, len)
        }
        else -> readToEnd(leftover, input, maxBytes) ?: return Outcome.TooLarge
      }
    return Outcome.Ok(String(bodyBytes, Charsets.UTF_8))
  }

  /** Reads up to and including the first CRLFCRLF; returns (headerBytesWithoutTerminator, bytesAfter). */
  private fun readHeaderBlock(input: InputStream): Pair<ByteArray, ByteArray>? {
    val buf = ByteArrayOutputStream()
    var matched = 0 // how many of \r\n\r\n we've matched
    while (buf.size() < MAX_HEADER_BYTES) {
      val b = input.read()
      if (b == -1) return null
      buf.write(b)
      matched =
        when {
          (matched == 0 || matched == 2) && b == '\r'.code -> matched + 1
          (matched == 1 || matched == 3) && b == '\n'.code -> matched + 1
          else -> 0
        }
      if (matched == 4) {
        val all = buf.toByteArray()
        val headerBytes = all.copyOfRange(0, all.size - 4) // drop the CRLFCRLF
        return headerBytes to ByteArray(0)
      }
    }
    return null
  }

  /** Reads exactly [len] body bytes, starting from [leftover] (bytes already pulled past the headers). */
  private fun readFixed(leftover: ByteArray, input: InputStream, len: Int): ByteArray {
    val out = ByteArrayOutputStream(len)
    out.write(leftover, 0, minOf(leftover.size, len))
    var remaining = len - out.size()
    val tmp = ByteArray(8 * 1024)
    while (remaining > 0) {
      val r = input.read(tmp, 0, minOf(tmp.size, remaining))
      if (r == -1) break
      out.write(tmp, 0, r)
      remaining -= r
    }
    return out.toByteArray()
  }

  /** Reads a close-delimited body (no length/chunking) to EOF, bounded; null if it exceeds [maxBytes]. */
  private fun readToEnd(leftover: ByteArray, input: InputStream, maxBytes: Int): ByteArray? {
    val out = ByteArrayOutputStream()
    out.write(leftover, 0, leftover.size)
    if (out.size() > maxBytes) return null
    val tmp = ByteArray(8 * 1024)
    while (true) {
      val r = input.read(tmp)
      if (r == -1) break
      out.write(tmp, 0, r)
      if (out.size() > maxBytes) return null
    }
    return out.toByteArray()
  }

  /** De-chunks a `Transfer-Encoding: chunked` body, bounded; null if it exceeds [maxBytes]. */
  private fun readChunked(leftover: ByteArray, input: InputStream, maxBytes: Int): ByteArray? {
    val stream = SequencePushback(leftover, input)
    val out = ByteArrayOutputStream()
    while (true) {
      val sizeLine = readLine(stream) ?: return out.toByteArray() // truncated; return what we have
      val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: return null
      if (size == 0) break
      if (out.size() + size > maxBytes) return null
      val chunk = ByteArray(size)
      var off = 0
      while (off < size) {
        val r = stream.read(chunk, off, size - off)
        if (r == -1) break
        off += r
      }
      out.write(chunk, 0, off)
      readLine(stream) // consume trailing CRLF after the chunk data
    }
    return out.toByteArray()
  }

  private fun readLine(input: InputStream): String? {
    val sb = StringBuilder()
    var b = input.read()
    if (b == -1) return null
    while (b != -1 && b != '\n'.code) {
      if (b != '\r'.code) sb.append(b.toChar())
      b = input.read()
    }
    return sb.toString()
  }

  /** An InputStream that first yields [head], then [tail] — so leftover header-read bytes aren't lost. */
  private class SequencePushback(head: ByteArray, private val tail: InputStream) : InputStream() {
    private val headStream = head.inputStream()
    override fun read(): Int = headStream.read().let { if (it != -1) it else tail.read() }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
      val r = headStream.read(b, off, len)
      return if (r != -1) r else tail.read(b, off, len)
    }
  }
}
