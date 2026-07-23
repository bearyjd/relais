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
import cc.grepon.relais.ModelDownloader
import java.io.File
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
    val tmp = File(ttsRoot(context), "${voice.id}.tar.bz2")
    ModelDownloader.fetch(
      url = voice.url,
      dst = tmp,
      token = null,
      expectedBytes = voice.sizeBytes,
      sha256 = voice.sha256,
      onProgress = onProgress,
    )

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
}
