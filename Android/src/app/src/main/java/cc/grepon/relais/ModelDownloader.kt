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

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The ONE download primitive behind the tts/imagegen/embed provisioners (issue #173 item 2),
 * replacing ~300 duplicated lines of redirect-handling, streaming, size-cap, and SHA-256 logic
 * that had drifted slightly out of sync across the three originals.
 */
object ModelDownloader {

  private const val MAX_REDIRECTS = 5
  private const val CONNECT_TIMEOUT_MS = 20_000
  private const val READ_TIMEOUT_MS = 60_000
  private const val ONE_MIB = 1_048_576L
  // Only hit when NEITHER a pinned expectedBytes NOR a server Content-Length is available (e.g. a
  // custom URL behind a server that omits it) — generous enough not to truncate a real multi-GB
  // image model, while still bounding a runaway/malicious stream.
  private const val DEFAULT_UNKNOWN_SIZE_CAP = 4L * 1024 * 1024 * 1024

  /**
   * Whether the bearer token may still be sent after a manual redirect hop (issue #174). Pure so
   * the one-way-drop invariant is unit-tested without network. Auth is kept ONLY while
   * [currentlyAuthing] and [nextHost] equals [originalHost] — comparing to the ORIGINAL host (not
   * the previous hop) and AND-ing with the running flag means once auth is dropped on a cross-host
   * hop it can never be re-attached to a later same-CDN redirect.
   */
  internal fun redirectKeepsAuth(currentlyAuthing: Boolean, nextHost: String, originalHost: String): Boolean =
    currentlyAuthing && nextHost.equals(originalHost, ignoreCase = true)

  /**
   * Streams [url] into [dst] (via a `.part` temp file, atomically renamed on success), following
   * redirects manually so [token] is dropped the moment a hop leaves the original host (never sent
   * to a CDN). Verifies the byte count against [expectedBytes] (if `> 0`) and the content against
   * [sha256] (if non-null) before finalizing. A storage-fill safety cap aborts mid-stream if the
   * server sends materially more than expected. [onProgress] receives 0..100, called only when the
   * integer percent changes. Blocking — call off the main thread. Returns [dst].
   *
   * Throws [IOException] on truncation, mismatch, or a size-cap violation, and [IllegalStateException]
   * on redirect/HTTP failures or a finalize (rename) failure.
   */
  fun fetch(
    url: String,
    dst: File,
    token: String? = null,
    expectedBytes: Long = -1,
    sha256: String? = null,
    onProgress: (Int) -> Unit = {},
  ): File {
    dst.parentFile?.mkdirs()
    val tmp = File(dst.parentFile, "${dst.name}.part")
    tmp.delete()

    val originalHost = URL(url).host
    var current = url
    var sendAuth = !token.isNullOrBlank()
    var hops = 0

    while (true) {
      val conn = (URL(current).openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = false
        connectTimeout = CONNECT_TIMEOUT_MS
        readTimeout = READ_TIMEOUT_MS
        requestMethod = "GET"
        if (sendAuth && !token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
      }
      try {
        val code = conn.responseCode
        if (code in 300..399) {
          val location = conn.getHeaderField("Location")
            ?: throw IllegalStateException("redirect ($code) with no Location for $dst")
          if (++hops > MAX_REDIRECTS) throw IllegalStateException("too many redirects fetching $dst")
          val nextHost = URL(URL(current), location).host
          // One-way drop: once auth is dropped on a cross-host hop it never comes back, even on a
          // later same-host CDN→CDN redirect. Compare to the ORIGINAL host, not the previous hop.
          sendAuth = redirectKeepsAuth(sendAuth, nextHost, originalHost)
          current = URL(URL(current), location).toString()
          continue
        }
        if (code != HttpURLConnection.HTTP_OK) {
          throw IllegalStateException("HTTP $code fetching $dst")
        }

        val contentLen = if (expectedBytes > 0) expectedBytes else conn.contentLengthLong
        // Cap derives from whatever real size we know (pinned expectedBytes, else the server's own
        // Content-Length); only an utterly sizeless response falls back to the generous hardcoded cap.
        val cap = if (contentLen > 0) contentLen + ONE_MIB else DEFAULT_UNKNOWN_SIZE_CAP
        val digest = sha256?.let { MessageDigest.getInstance("SHA-256") }
        var written = 0L
        try {
          conn.inputStream.buffered().use { input ->
            tmp.outputStream().buffered().use { output ->
              val buf = ByteArray(64 * 1024)
              var read: Int
              var lastPct = -1
              while (input.read(buf).also { read = it } != -1) {
                output.write(buf, 0, read)
                digest?.update(buf, 0, read)
                written += read
                if (written > cap) {
                  throw IOException("download of $dst exceeded size cap ($written > $cap) — aborting")
                }
                if (contentLen > 0) {
                  val pct = ((written * 100) / contentLen).toInt().coerceIn(0, 100)
                  if (pct != lastPct) {
                    lastPct = pct
                    onProgress(pct)
                  }
                }
              }
            }
          }
        } catch (t: Throwable) {
          tmp.delete()
          throw t
        }

        if (expectedBytes > 0 && tmp.length() != expectedBytes) {
          val got = tmp.length()
          tmp.delete()
          throw IOException("download of $dst truncated: $got != $expectedBytes")
        }
        if (sha256 != null) {
          val actual = digest?.digest()?.joinToString("") { "%02x".format(it) }.orEmpty()
          if (!actual.equals(sha256, ignoreCase = true)) {
            tmp.delete()
            throw IOException("SHA-256 mismatch for $dst: got $actual, expected $sha256")
          }
        }

        dst.delete()
        if (!tmp.renameTo(dst)) {
          tmp.delete()
          throw IllegalStateException("could not finalize $dst")
        }
        onProgress(100)
        return dst
      } finally {
        conn.disconnect()
      }
    }
  }
}
