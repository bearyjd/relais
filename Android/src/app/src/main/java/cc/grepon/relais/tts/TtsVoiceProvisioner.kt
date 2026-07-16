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

package cc.grepon.relais.tts

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/**
 * Downloads + SHA-verifies + extracts a sherpa-onnx [TtsVoice] `.tar.bz2` bundle into app storage,
 * reusing an already-extracted voice. Mirrors [cc.grepon.relais.imagegen.ImageModelProvisioner], with
 * the extra tarball step: sherpa voices ship as `.tar.bz2` (a dir of model/tokens/espeak-ng-data), and
 * the JDK has no bzip2 — so commons-compress does the decompress + untar. Path-traversal-guarded.
 */
object TtsVoiceProvisioner {
  private const val TAG = "RelaisTtsProvision"

  /** Root under which all voices extract: `<externalFiles>/tts`, falling back to internal `filesDir`
   *  when external storage is unavailable (getExternalFilesDir → null). */
  private fun ttsRoot(context: Context): File =
    File(context.getExternalFilesDir(null) ?: context.filesDir, "tts")

  /** The extracted voice directory (`<externalFiles>/tts/<dirName>`). */
  fun voiceDir(context: Context, voice: TtsVoice): File = File(ttsRoot(context), voice.dirName)

  /** True iff [voice] is fully extracted on disk (acoustic model + tokens + phonemizer data present). */
  fun isProvisioned(context: Context, voice: TtsVoice): Boolean {
    val dir = voiceDir(context, voice)
    return File(dir, voice.onnxName).isFile &&
      File(dir, "tokens.txt").isFile &&
      File(dir, "espeak-ng-data").isDirectory
  }

  /**
   * Ensures [voice] is extracted on disk, downloading + verifying + extracting if missing, and returns
   * its directory. Reuse needs no network. Blocking (~60–90 MB fetch on first run). Throws on truncation,
   * SHA mismatch, IO failure, or a malformed archive.
   */
  fun ensure(context: Context, voice: TtsVoice, onProgress: (Int) -> Unit = {}): File {
    val dir = voiceDir(context, voice)
    if (isProvisioned(context, voice)) {
      onProgress(100)
      return dir
    }
    ttsRoot(context).mkdirs()
    val tmp = File(ttsRoot(context), "${voice.id}.tar.bz2.part")
    tmp.delete()
    download(voice.url, tmp, voice.sizeBytes, onProgress)

    if (voice.sizeBytes > 0 && tmp.length() != voice.sizeBytes) {
      tmp.delete()
      throw IllegalStateException("voice ${voice.id} truncated: ${tmp.length()} != ${voice.sizeBytes}")
    }
    val actual = sha256Hex(tmp)
    if (!actual.equals(voice.sha256, ignoreCase = true)) {
      tmp.delete()
      throw IllegalStateException("SHA-256 mismatch for ${voice.id}: got $actual, expected ${voice.sha256}")
    }

    // Extract into a staging dir, then atomically swap in — a crash mid-extract never leaves a
    // half-populated voiceDir that isProvisioned() would wrongly accept.
    val staging = File(ttsRoot(context), "${voice.id}.staging")
    staging.deleteRecursively()
    staging.mkdirs()
    extractTarBz2(tmp, staging)
    tmp.delete()

    // The tarball's top-level dir IS voice.dirName; the extracted voice is staging/<dirName>.
    val extracted = File(staging, voice.dirName)
    if (!File(extracted, voice.onnxName).isFile) {
      staging.deleteRecursively()
      throw IllegalStateException("voice ${voice.id}: ${voice.onnxName} not found after extract")
    }
    dir.deleteRecursively()
    if (!extracted.renameTo(dir)) {
      staging.deleteRecursively()
      throw IllegalStateException("could not finalize voice ${voice.id}")
    }
    staging.deleteRecursively()
    Log.i(TAG, "TTS voice provisioned: ${dir.path}")
    return dir
  }

  /** Deletes [voice]'s extracted dir so a later [ensure] re-provisions (e.g. after a failed load). */
  fun clear(context: Context, voice: TtsVoice) {
    voiceDir(context, voice).deleteRecursively()
  }

  /** Streams [url] to [dst], reporting 0..100 progress against [expectedBytes] when known. */
  private fun download(url: String, dst: File, expectedBytes: Long, onProgress: (Int) -> Unit) {
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
      connectTimeout = 30_000
      readTimeout = 60_000
      instanceFollowRedirects = true
    }
    try {
      val total = if (expectedBytes > 0) expectedBytes else conn.contentLengthLong
      // Abort if the server streams materially more than the pinned size (a redirect/compromised host
      // shouldn't be able to fill the phone's storage before the post-hoc length/SHA check rejects it).
      val cap = if (expectedBytes > 0) expectedBytes + 1_048_576L else 512L * 1024 * 1024
      conn.inputStream.use { input ->
        dst.outputStream().use { out ->
          val buf = ByteArray(64 * 1024)
          var read = 0L
          var lastPct = -1
          while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            read += n
            if (read > cap) {
              throw IllegalStateException("download exceeded size cap ($read > $cap) — aborting")
            }
            if (total > 0) {
              val pct = (read * 100 / total).toInt().coerceIn(0, 100)
              if (pct != lastPct) { lastPct = pct; onProgress(pct) }
            }
          }
        }
      }
    } finally {
      conn.disconnect()
    }
  }

  /** Decompress + untar [tarBz2] into [destDir], rejecting entries that escape it (zip-slip guard). */
  private fun extractTarBz2(tarBz2: File, destDir: File) {
    val destCanonical = destDir.canonicalFile
    tarBz2.inputStream().buffered().use { fis ->
      BZip2CompressorInputStream(fis).use { bz ->
        TarArchiveInputStream(bz).use { tar ->
          while (true) {
            val entry = tar.nextEntry ?: break
            val out = File(destDir, entry.name).canonicalFile
            if (!out.path.startsWith(destCanonical.path + File.separator) && out.path != destCanonical.path) {
              throw SecurityException("archive entry escapes target dir: ${entry.name}")
            }
            if (entry.isDirectory) {
              out.mkdirs()
            } else {
              out.parentFile?.mkdirs()
              out.outputStream().use { tar.copyTo(it, 64 * 1024) }
            }
          }
        }
      }
    }
  }

  /** Streaming SHA-256 of [file] as lowercase hex. */
  private fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
      val buf = ByteArray(64 * 1024)
      while (true) {
        val n = input.read(buf)
        if (n < 0) break
        digest.update(buf, 0, n)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }
}
