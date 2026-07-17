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

package cc.grepon.relais.embed

import android.content.Context
import android.util.Log
import cc.grepon.relais.RelaisConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** On-disk locations of the two assets the embedder needs. */
data class EmbeddingAssets(val modelFile: File, val tokenizerFile: File)

/**
 * Whether the bearer token may still be sent after a manual redirect hop (issue #174). Pure so the
 * one-way-drop invariant is unit-tested without network. Auth is kept ONLY while [currentlyAuthing]
 * and the [nextHost] equals the [originalHost] — comparing to the ORIGINAL host (not the previous
 * hop) and AND-ing with the running flag means once auth is dropped on a cross-host hop it can never
 * be re-attached to a later same-CDN redirect. (`ImageModelProvisioner` uses the same rule.)
 */
internal fun redirectKeepsAuth(currentlyAuthing: Boolean, nextHost: String, originalHost: String): Boolean =
  currentlyAuthing && nextHost.equals(originalHost, ignoreCase = true)

/**
 * Downloads the EmbeddingGemma `.tflite` + paired `sentencepiece.model` from the gated HuggingFace
 * repo into app storage, reusing already-present files. Self-contained (a blocking, resumable-free
 * streaming GET with the HF bearer token) so it has no WorkManager / UI coupling — call it off the
 * main thread.
 *
 * v1 provisions the GENERIC seq512 variant ([EMBEDDING_GENERIC_VARIANT]): it runs anywhere on the
 * CPU (XNNPACK) and so is guaranteed to load via the bundled LiteRT runtime. The SoC-accelerated
 * variants ([selectEmbeddingVariant]) are AOT-compiled for a specific NPU and need that vendor's
 * delegate — enabling them is a separate, device-verified follow-up.
 */
object EmbeddingModelProvisioner {

  private const val TAG = "RelaisEmbedProvision"

  /** Published size of `sentencepiece.model` in the repo (used for reuse-if-complete checks). */
  private const val TOKENIZER_BYTES = 4_683_319L

  private const val MAX_REDIRECTS = 5
  private const val CONNECT_TIMEOUT_MS = 20_000
  private const val READ_TIMEOUT_MS = 60_000

  /** App-scoped directory holding the embedding assets. */
  fun embedDir(context: Context): File {
    val base = context.getExternalFilesDir(null) ?: context.filesDir
    return File(base, "relais/embed").apply { mkdirs() }
  }

  /**
   * Ensures both assets are present on disk, downloading whichever is missing/incomplete, and returns
   * their paths. Requires [RelaisConfig.hfToken] to be set (the repo is license-gated) — throws
   * [IllegalStateException] if the token is absent. [onProgress] receives 0..100 across the combined
   * download (best-effort). Blocking; may take a while on first run (~180 MB). Throws on failure.
   */
  fun ensure(context: Context, onProgress: (Int) -> Unit = {}): EmbeddingAssets {
    val variant = EMBEDDING_GENERIC_VARIANT
    val dir = embedDir(context)
    val modelFile = File(dir, variant.fileName)
    val tokenizerFile = File(dir, EMBEDDING_TOKENIZER_FILE)

    // Reuse an already-complete pair WITHOUT needing the (gated) token — a node restart re-loads the
    // on-disk model even when no HF token is currently set. A download (below) still requires it.
    if (modelFile.length() == variant.approxBytes && tokenizerFile.length() == TOKENIZER_BYTES) {
      onProgress(100)
      return EmbeddingAssets(modelFile = modelFile, tokenizerFile = tokenizerFile)
    }

    val token = RelaisConfig.hfToken(context)?.takeIf { it.isNotBlank() }
      ?: throw IllegalStateException("HuggingFace token not set; cannot fetch the gated embedding model")

    // Weighted combined progress: the model dwarfs the tokenizer, so it owns ~98% of the bar.
    val total = variant.approxBytes + TOKENIZER_BYTES
    val modelWeight = variant.approxBytes.toDouble() / total
    val tokenizerWeight = TOKENIZER_BYTES.toDouble() / total

    downloadIfNeeded(
      url = downloadUrlFor(variant),
      target = modelFile,
      token = token,
      expectedBytes = variant.approxBytes,
    ) { p -> onProgress((p * modelWeight).toInt().coerceIn(0, 100)) }

    downloadIfNeeded(
      url = tokenizerDownloadUrl(),
      target = tokenizerFile,
      token = token,
      expectedBytes = TOKENIZER_BYTES,
    ) { p -> onProgress((modelWeight * 100 + p * tokenizerWeight).toInt().coerceIn(0, 100)) }

    return EmbeddingAssets(modelFile = modelFile, tokenizerFile = tokenizerFile)
  }

  /** True iff both assets are already present at their full published size (no download needed). */
  fun isProvisioned(context: Context): Boolean {
    val dir = embedDir(context)
    val model = File(dir, EMBEDDING_GENERIC_VARIANT.fileName)
    val tokenizer = File(dir, EMBEDDING_TOKENIZER_FILE)
    return model.length() == EMBEDDING_GENERIC_VARIANT.approxBytes && tokenizer.length() == TOKENIZER_BYTES
  }

  /**
   * Deletes the on-disk model + tokenizer so a later [ensure] re-downloads them. The size-only reuse
   * check can't tell a corrupt-but-right-size file from a good one, so the embedder calls this when a
   * downloaded model fails to load (corrupt / incompatible) — dropping it lets the next attempt heal.
   */
  fun clearModel(context: Context) {
    val dir = embedDir(context)
    File(dir, EMBEDDING_GENERIC_VARIANT.fileName).delete()
    File(dir, EMBEDDING_TOKENIZER_FILE).delete()
  }

  private fun downloadIfNeeded(
    url: String,
    target: File,
    token: String,
    expectedBytes: Long,
    onProgress: (Int) -> Unit,
  ) {
    if (target.length() == expectedBytes) {
      Log.d(TAG, "reusing ${target.name} (${target.length()} bytes)")
      onProgress(100)
      return
    }
    Log.d(TAG, "downloading ${target.name} from $url")
    val tmp = File(target.parentFile, "${target.name}.part")
    tmp.delete()
    streamTo(url, tmp, token, expectedBytes, onProgress)
    if (expectedBytes > 0 && tmp.length() != expectedBytes) {
      tmp.delete()
      throw IllegalStateException("download of ${target.name} truncated: ${tmp.length()} != $expectedBytes")
    }
    if (!tmp.renameTo(target)) {
      tmp.delete()
      throw IllegalStateException("could not finalize ${target.name}")
    }
  }

  /**
   * Streams [url] into [target]. HuggingFace `resolve` URLs 302-redirect to a signed CDN URL that
   * needs no auth, so redirects are followed MANUALLY and the bearer token is sent ONLY to the
   * huggingface.co host — never forwarded to the CDN.
   */
  private fun streamTo(url: String, target: File, token: String, expectedBytes: Long, onProgress: (Int) -> Unit) {
    var current = url
    val originalHost = URL(url).host
    var sendAuth = true
    var hops = 0
    while (true) {
      val conn = (URL(current).openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = false
        connectTimeout = CONNECT_TIMEOUT_MS
        readTimeout = READ_TIMEOUT_MS
        requestMethod = "GET"
        if (sendAuth) setRequestProperty("Authorization", "Bearer $token")
      }
      try {
        val code = conn.responseCode
        if (code in 300..399) {
          val location = conn.getHeaderField("Location")
            ?: throw IllegalStateException("redirect ($code) with no Location for $target")
          if (++hops > MAX_REDIRECTS) throw IllegalStateException("too many redirects fetching $target")
          // One-way auth drop: the bearer token is sent ONLY while we stay on the ORIGINAL host.
          // Compare to originalHost (not the previous hop) and never re-enable once dropped, so a
          // chain huggingface.co -> cdn -> cdn/... can't re-attach the token to the CDN host (#174).
          val nextHost = URL(URL(current), location).host
          sendAuth = redirectKeepsAuth(sendAuth, nextHost, originalHost)
          current = URL(URL(current), location).toString()
          continue
        }
        if (code != HttpURLConnection.HTTP_OK) {
          throw IllegalStateException("HTTP $code fetching $target (gated repo needs a valid HF token)")
        }
        val contentLen = if (expectedBytes > 0) expectedBytes else conn.contentLengthLong
        conn.inputStream.buffered().use { input ->
          target.outputStream().buffered().use { output ->
            val buf = ByteArray(64 * 1024)
            var read: Int
            var written = 0L
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
        onProgress(100)
        return
      } finally {
        conn.disconnect()
      }
    }
  }
}
