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
import cc.grepon.relais.ModelDownloader
import java.io.File

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
    if (model.sha256.isBlank() && model.sizeBytes <= 0) {
      // A custom URL with neither a pinned size nor SHA: the server Content-Length check is the
      // only guard. Flag it so an operator knows the artifact is unverified (and can pin a SHA).
      Log.w(TAG, "provisioning ${model.fileName} with no pinned size or SHA — integrity unverified (custom URL)")
    }
    ModelDownloader.fetch(
      url = model.url,
      dst = target,
      token = token?.takeIf { it.isNotBlank() },
      expectedBytes = model.sizeBytes,
      sha256 = model.sha256.ifBlank { null },
      onProgress = onProgress,
    )
    Log.i(TAG, "Image model provisioned: ${target.path}")
    return target
  }

  /** Deletes [model]'s on-disk file so a later [ensure] re-downloads (e.g. after a failed load). */
  fun clearModel(context: Context, model: ImageModel) {
    modelFile(context, model).delete()
  }
}
