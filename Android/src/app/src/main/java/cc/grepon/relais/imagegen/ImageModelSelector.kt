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

/** Id of the default image model — SD-Turbo (fast, ~4 steps). Operator can flip to a quality model. */
const val DEFAULT_IMAGE_MODEL_ID = "turbo"

/**
 * A provisionable stable-diffusion.cpp image model: the `.gguf` to download + verify, plus its sane
 * default sampler params. [sha256] pins the artifact for integrity (a swapped/corrupt file is
 * rejected); [sizeBytes] is the published size for the reuse-if-complete check + download %
 * (`-1` = unknown, e.g. an operator `custom` URL). [steps]/[cfg] are the model's defaults; a request
 * may clamp `steps` within range. Plain data class (no Android types) so it is unit-testable.
 */
data class ImageModel(
  val id: String,
  val fileName: String,
  val sha256: String,
  val sizeBytes: Long,
  val steps: Int,
  val cfg: Float,
  val url: String,
)

/** HF `resolve/main` URL for [fileName] in the PUBLIC repo [repo] (free CDN; no token needed). */
private fun hfResolve(repo: String, fileName: String): String =
  "https://huggingface.co/$repo/resolve/main/$fileName"

/**
 * SD-Turbo (cfg=1, steps=4) — the v1 default: ~90 s/image on a Vulkan device, lowest step count.
 * Served from the **public** sd.cpp gguf repo `Green-Sky/SD-Turbo-GGUF` (Green-Sky maintains
 * stable-diffusion.cpp), so the node provisions it with no HF token. License: SD-Turbo is Stability
 * **non-commercial research** — fine for a self-hosted research node (revisit if Relais goes commercial).
 */
val IMAGE_MODEL_TURBO =
  ImageModel(
    id = "turbo",
    fileName = "sd_turbo-f16-q8_0.gguf",
    sha256 = "d50be7655f0a554cf8041c145d88b210bd5f3c545423119dee62ae08cae51580",
    sizeBytes = 2_023_745_376L,
    steps = 4,
    cfg = 1f,
    url = hfResolve("Green-Sky/SD-Turbo-GGUF", "sd_turbo-f16-q8_0.gguf"),
  )

/**
 * Stable Diffusion 1.5 q4_0 (cfg=7, steps=20) — the quality upgrade. Served from the **public**
 * `second-state/stable-diffusion-v1-5-GGUF`. License: CreativeML OpenRAIL-M (redistribution-friendly).
 * Slower (~3 min) but better fidelity.
 */
val IMAGE_MODEL_SD15 =
  ImageModel(
    id = "sd15",
    fileName = "stable-diffusion-v1-5-pruned-emaonly-Q4_0.gguf",
    sha256 = "b8944e9fe0b69b36ae1b5bb0185b3a7b8ef14347fe0fa9af6c64c4829022261f",
    sizeBytes = 1_566_768_416L,
    steps = 20,
    cfg = 7f,
    url = hfResolve("second-state/stable-diffusion-v1-5-GGUF", "stable-diffusion-v1-5-pruned-emaonly-Q4_0.gguf"),
  )

/** The built-in, SHA-pinned model set. Operator selects one via [RelaisConfig.imageModelId]. */
val IMAGE_MODELS: List<ImageModel> = listOf(IMAGE_MODEL_TURBO, IMAGE_MODEL_SD15)

/**
 * Resolves [id] to a built-in [ImageModel], or — for `id == "custom"` — builds one from the operator's
 * [customUrl] (+ optional [customSha] to pin it; blank = no SHA check). Returns null for an unknown id,
 * a `custom` request with no URL, or a `custom` URL that is not `http(s)://` (the provisioner speaks
 * HTTP only). The on-disk filename is derived from the URL's last path segment, sanitized so a
 * pathological segment can't escape the model dir. Pure + unit-testable.
 */
fun imageModelById(id: String?, customUrl: String? = null, customSha: String? = null): ImageModel? {
  IMAGE_MODELS.firstOrNull { it.id == id }?.let { return it }
  if (id == "custom" && !customUrl.isNullOrBlank()) {
    val lower = customUrl.lowercase()
    if (!lower.startsWith("https://") && !lower.startsWith("http://")) return null
    return ImageModel(
      id = "custom",
      fileName = safeFileName(customUrl),
      sha256 = customSha.orEmpty(),
      sizeBytes = -1L, // unknown for an arbitrary operator URL
      steps = IMAGE_MODEL_TURBO.steps,
      cfg = IMAGE_MODEL_TURBO.cfg,
      url = customUrl,
    )
  }
  return null
}

/** Last path segment with query/fragment stripped; falls back to `custom.gguf` for `.`/`..`/empty. */
private fun safeFileName(url: String): String {
  val seg = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
  return if (seg.isBlank() || seg == "." || seg == "..") "custom.gguf" else seg
}
