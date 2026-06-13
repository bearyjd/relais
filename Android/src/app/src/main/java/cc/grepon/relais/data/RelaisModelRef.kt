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

package cc.grepon.relais.data

import com.google.gson.Gson

/**
 * A self-contained pointer to a node-runnable model: everything the provisioner needs to construct
 * a download URL and on-disk path **without** an allowlist match. Persisted (as JSON, plaintext)
 * when the operator picks a model in the selector, so the headless node can provision any chosen
 * model — curated or an arbitrary HuggingFace `.litertlm` repo — offline and without the static
 * allowlist.
 *
 * The fields mirror what [AllowedModel.toModel] consumes to build the HF resolve URL
 * (`https://huggingface.co/{modelId}/resolve/{commitHash}/{modelFile}?download=true`) and the local
 * layout (`{external}/{normalizedName}/{commitHash}/{modelFile}`). Kept a plain data class (no
 * Android types) so it round-trips trivially and is unit-testable.
 *
 * @property modelId HF repo id, e.g. `litert-community/gemma-4-E4B-it-litert-lm`.
 * @property modelFile the `.litertlm` file within the repo to download/serve.
 * @property commitHash the repo revision (HF `sha`) the file is pinned to; also the on-disk version dir.
 * @property sizeInBytes file size, for the download progress %. `-1` if unknown (download still works).
 * @property displayName short label for the selector row; also the unique [Model.name] for the path.
 * @property source provenance — [SOURCE_ALLOWLIST] (curated) or [SOURCE_HUGGINGFACE] (search/paste).
 */
data class RelaisModelRef(
  val modelId: String,
  val modelFile: String,
  val commitHash: String,
  val sizeInBytes: Long,
  val displayName: String,
  val source: String,
) {
  fun toJson(): String = Gson().toJson(this)

  companion object {
    const val SOURCE_ALLOWLIST = "allowlist"
    const val SOURCE_HUGGINGFACE = "huggingface"

    /** Parses a persisted ref, returning null on absent/malformed JSON (never throws). */
    fun fromJson(json: String?): RelaisModelRef? =
      json?.let { runCatching { Gson().fromJson(it, RelaisModelRef::class.java) }.getOrNull() }
        // Gson fills fields by reflection and does NOT honor Kotlin non-null types: a partial/garbage
        // object can deserialize with these "non-null" String fields actually null. Use isNullOrBlank
        // (nullable receiver) so a null field fails validation instead of throwing the non-null
        // intrinsic NPE — a corrupt ref must decode to null, never crash a headless boot.
        ?.takeIf {
          !it.modelId.isNullOrBlank() &&
            !it.modelFile.isNullOrBlank() &&
            !it.commitHash.isNullOrBlank() &&
            !it.displayName.isNullOrBlank()
        }
  }
}
