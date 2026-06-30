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

/**
 * HuggingFace repo (operator self-host) that holds the stable-diffusion.cpp `.gguf` image models,
 * fetched over the free `resolve/main` CDN — the same path the embedder uses. The converted ggufs are
 * not drop-in public artifacts (built via `convert.py`), so they are self-hosted here. Override per
 * model with an operator URL (the `custom` model) when self-hosting elsewhere.
 */
const val IMAGE_REPO_ID = "dispense1301/relais-imagegen"

/** Id of the default image model — SD-Turbo (fast, ~4 steps). Operator can flip to a quality model. */
const val DEFAULT_IMAGE_MODEL_ID = "turbo"

/**
 * A provisionable sd.cpp image model: the `.gguf` to download + verify, plus its sane default sampler
 * params. [sha256] pins the artifact for integrity (the weights are open, but pin so a swapped/corrupt
 * file is rejected); [sizeBytes] is the published size for the reuse-if-complete check + download %
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

private fun hfUrl(fileName: String): String =
  "https://huggingface.co/$IMAGE_REPO_ID/resolve/main/$fileName"

/**
 * SD-Turbo (cfg=1, steps=4) — the v1 default: ~90 s/image on a Vulkan device, lowest step count.
 * License: Stability **non-commercial research** — fine for a self-hosted research node (see the plan).
 */
val IMAGE_MODEL_TURBO =
  ImageModel(
    id = "turbo",
    fileName = "sdturbo.gguf",
    sha256 = "d50be7655f0a554cf8041c145d88b210bd5f3c545423119dee62ae08cae51580",
    sizeBytes = 2_023_745_376L,
    steps = 4,
    cfg = 1f,
    url = hfUrl("sdturbo.gguf"),
  )

/**
 * Stable Diffusion 1.5 q4_0 (cfg=7, steps=20) — the quality upgrade. License: OpenRAIL-M (more
 * redistribution-friendly than SD-Turbo). Slower (~3 min) but better fidelity.
 */
val IMAGE_MODEL_SD15 =
  ImageModel(
    id = "sd15",
    fileName = "sd15-q4_0.gguf",
    sha256 = "b8944e9fe0b69b36ae1b5bb0185b3a7b8ef14347fe0fa9af6c64c4829022261f",
    sizeBytes = 1_566_768_416L,
    steps = 20,
    cfg = 7f,
    url = hfUrl("sd15-q4_0.gguf"),
  )

/** The built-in, SHA-pinned model set. Operator selects one via [RelaisConfig.imageModelId]. */
val IMAGE_MODELS: List<ImageModel> = listOf(IMAGE_MODEL_TURBO, IMAGE_MODEL_SD15)

/**
 * Resolves [id] to a built-in [ImageModel], or — for `id == "custom"` — builds one from the operator's
 * [customUrl] (+ optional [customSha] to pin it; blank = no SHA check). Returns null for an unknown id,
 * or a `custom` request with no URL. Pure + unit-testable.
 */
fun imageModelById(id: String?, customUrl: String? = null, customSha: String? = null): ImageModel? {
  IMAGE_MODELS.firstOrNull { it.id == id }?.let { return it }
  if (id == "custom" && !customUrl.isNullOrBlank()) {
    return ImageModel(
      id = "custom",
      fileName = customUrl.substringAfterLast('/').substringBefore('?').ifBlank { "custom.gguf" },
      sha256 = customSha.orEmpty(),
      sizeBytes = -1L, // unknown for an arbitrary operator URL
      steps = IMAGE_MODEL_TURBO.steps,
      cfg = IMAGE_MODEL_TURBO.cfg,
      url = customUrl,
    )
  }
  return null
}
