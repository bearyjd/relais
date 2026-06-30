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

package cc.grepon.relais.imagegen

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads + SHA-verifies an [ImageModel] `.gguf` into app storage, reusing an already-complete file.
 * A near-copy of [cc.grepon.relais.embed.EmbeddingModelProvisioner], with two differences for image
 * models: (1) the HF bearer token is **optional** (the weights are open; a public CDN needs no auth, a
 * gated/private mirror does — so the token is sent only when configured), and (2) a fresh download is
 * **SHA-256-verified** against [ImageModel.sha256] before it is finalized, so a swapped or corrupt
 * artifact is rejected (the weights pinned at convert time are the bytes that run).
 *
 * Self-contained blocking streaming GET (no WorkManager / UI coupling) — call it off the main thread.
 * The model is consumed by the `:imagegen` process via `ModelSpec.localFile(File)` (PR-C).
 */
object ImageModelProvisioner {

  private const val TAG = "RelaisImageProvision"

  private const val MAX_REDIRECTS = 5
  private const val CONNECT_TIMEOUT_MS = 20_000
  private const val READ_TIMEOUT_MS = 60_000

  /** App-scoped directory holding the image-gen models (sibling of the embedder's `relais/embed`). */
  fun modelDir(context: Context): File {
    val base = context.getExternalFilesDir(null) ?: context.filesDir
    return File(base, "relais/imagegen").apply { mkdirs() }
  }

  /** On-disk path for [model] (whether or not it is present yet). */
  fun modelFile(context: Context, model: ImageModel): File = File(modelDir(context), model.fileName)

  /**
   * True iff [file] is present at its expected size. For a known [expectedBytes] (`> 0`) this is an
   * exact match; for an unknown size (`<= 0`, e.g. an operator `custom` URL) it is "exists + non-empty".
   * Pure (operates on a [File]) so it is unit-testable with a temp file.
   */
  internal fun isComplete(file: File, expectedBytes: Long): Boolean =
    if (expectedBytes > 0) file.length() == expectedBytes else file.length() > 0L

  /** True iff [model] is already fully present on disk (no download needed). */
  fun isProvisioned(context: Context, model: ImageModel): Boolean =
    isComplete(modelFile(context, model), model.sizeBytes)

  /**
   * Ensures [model] is present on disk, downloading + verifying it if missing/incomplete, and returns
   * its path. Reuse of an already-complete file needs no network and no [token]. A download sends the
   * HF bearer [token] only if non-blank (open weights → usually none). [onProgress] receives 0..100.
   * Blocking; first run streams ~1.5–2 GB. Throws on truncation, SHA mismatch, or IO failure.
   */
  fun ensure(context: Context, model: ImageModel, token: String? = null, onProgress: (Int) -> Unit = {}): File {
    val target = modelFile(context, model)
    if (isComplete(target, model.sizeBytes)) {
      onProgress(100)
      return target
    }
    val tmp = File(target.parentFile, "${target.name}.part")
    tmp.delete()
    streamTo(model.url, tmp, token?.takeIf { it.isNotBlank() }, model.sizeBytes, onProgress)

    if (model.sizeBytes > 0 && tmp.length() != model.sizeBytes) {
      tmp.delete()
      throw IllegalStateException("download of ${model.fileName} truncated: ${tmp.length()} != ${model.sizeBytes}")
    }
    if (model.sha256.isNotBlank()) {
      val actual = sha256Hex(tmp)
      if (!actual.equals(model.sha256, ignoreCase = true)) {
        tmp.delete()
        throw IllegalStateException("SHA-256 mismatch for ${model.fileName}: got $actual, expected ${model.sha256}")
      }
    } else if (model.sizeBytes <= 0) {
      // A custom URL with neither a pinned size nor SHA: the server Content-Length check above is the
      // only guard. Flag it so an operator knows the artifact is unverified (and can pin a SHA).
      Log.w(TAG, "provisioned ${model.fileName} with no pinned size or SHA — integrity unverified (custom URL)")
    }
    if (!tmp.renameTo(target)) {
      tmp.delete()
      throw IllegalStateException("could not finalize ${model.fileName}")
    }
    Log.i(TAG, "Image model provisioned: ${target.path}")
    return target
  }

  /** Deletes [model]'s on-disk file so a later [ensure] re-downloads (e.g. after a failed load). */
  fun clearModel(context: Context, model: ImageModel) {
    modelFile(context, model).delete()
  }

  /** Streaming SHA-256 of [file] as lowercase hex. Reads in chunks so a multi-GB file isn't buffered. */
  internal fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
      val buf = ByteArray(64 * 1024)
      while (true) {
        val r = input.read(buf)
        if (r == -1) break
        digest.update(buf, 0, r)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  /**
   * Streams [url] into [target]. HuggingFace `resolve` URLs 302-redirect to a signed CDN that needs no
   * auth, so redirects are followed MANUALLY and [token] (if any) is sent ONLY to the original host —
   * never forwarded to the CDN. Identical posture to the embedder's provisioner.
   */
  private fun streamTo(url: String, target: File, token: String?, expectedBytes: Long, onProgress: (Int) -> Unit) {
    val originalHost = URL(url).host
    var current = url
    var sendAuth = token != null
    var hops = 0
    while (true) {
      val conn = (URL(current).openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = false
        connectTimeout = CONNECT_TIMEOUT_MS
        readTimeout = READ_TIMEOUT_MS
        requestMethod = "GET"
        if (sendAuth && token != null) setRequestProperty("Authorization", "Bearer $token")
      }
      try {
        val code = conn.responseCode
        if (code in 300..399) {
          val location = conn.getHeaderField("Location")
            ?: throw IllegalStateException("redirect ($code) with no Location for $target")
          if (++hops > MAX_REDIRECTS) throw IllegalStateException("too many redirects fetching $target")
          val nextHost = URL(URL(current), location).host
          // One-way drop: once auth is dropped on a cross-host hop it never comes back, even on a
          // later same-host CDN→CDN redirect. Compare to the ORIGINAL host, not the previous hop.
          sendAuth = sendAuth && token != null && nextHost.equals(originalHost, ignoreCase = true)
          current = URL(URL(current), location).toString()
          continue
        }
        if (code != HttpURLConnection.HTTP_OK) {
          throw IllegalStateException("HTTP $code fetching $target")
        }
        val contentLen = if (expectedBytes > 0) expectedBytes else conn.contentLengthLong
        var written = 0L
        conn.inputStream.buffered().use { input ->
          target.outputStream().buffered().use { output ->
            val buf = ByteArray(64 * 1024)
            var read: Int
            var lastPct = -1
            while (input.read(buf).also { read = it } != -1) {
              output.write(buf, 0, read)
              written += read
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
        // Enforce the server-declared length on EVERY path (incl. a custom URL with no pinned size):
        // a clean early-EOF mid-body otherwise looks like a successful download.
        if (contentLen > 0 && written != contentLen) {
          throw IllegalStateException("download of $target truncated: $written != $contentLen")
        }
        onProgress(100)
        return
      } finally {
        conn.disconnect()
      }
    }
  }
}
