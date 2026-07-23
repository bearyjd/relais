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
import cc.grepon.relais.ModelDownloader
import cc.grepon.relais.RelaisConfig
import java.io.File

/** On-disk locations of the two assets the embedder needs. */
data class EmbeddingAssets(val modelFile: File, val tokenizerFile: File)

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
    ModelDownloader.fetch(
      url = url,
      dst = target,
      token = token,
      expectedBytes = expectedBytes,
      sha256 = null,
      onProgress = onProgress,
    )
  }
}
