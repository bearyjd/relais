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

/**
 * One downloadable LiteRT `.tflite` variant of `litert-community/embeddinggemma-300m` (seq512,
 * mixed-precision). [approxBytes] is the published artifact size (used for download-progress %).
 */
data class EmbeddingModelVariant(val fileName: String, val approxBytes: Long)

/** HuggingFace repo that hosts every EmbeddingGemma variant + the tokenizer (license-gated). */
const val EMBEDDING_REPO_ID = "litert-community/embeddinggemma-300m"

/** SentencePiece tokenizer file in [EMBEDDING_REPO_ID]; paired with the selected `.tflite`. */
const val EMBEDDING_TOKENIZER_FILE = "sentencepiece.model"

private const val VARIANT_PREFIX = "embeddinggemma-300M_seq512_mixed-precision"

/**
 * The default/fallback variant (no SoC accelerator delegate — runs anywhere). 179 MB. Selected for
 * any device whose SoC has no dedicated build, INCLUDING Pixel 9 (Tensor G4): there is NO Tensor-G4
 * artifact, so G4 and every unmatched SoC fall back here.
 */
val EMBEDDING_GENERIC_VARIANT = EmbeddingModelVariant("$VARIANT_PREFIX.tflite", 179_132_472L)

// Accelerator-specific builds. Filenames + byte sizes VERIFIED 2026-06-17 against the live repo
// tree (`GET https://huggingface.co/api/models/litert-community/embeddinggemma-300m/tree/main`).
// The repo is GATED for download, but the file *listing* is public — so these are ground-truth, not
// guessed. CRITICAL: the SoC vendor is separated by a DOT (`.google` / `.qualcomm` / `.mediatek`),
// NOT an underscore; the underscore form 404s on every accelerator device. Ordering is irrelevant —
// each substring is unique to one SoC.
private val EMBEDDING_VARIANTS: List<Pair<List<String>, EmbeddingModelVariant>> = listOf(
  // Google Tensor G5 (Pixel 10). NOTE: no Tensor-G4 build exists → Pixel 9 uses GENERIC.
  listOf("tensor g5", "tensor_g5") to
    EmbeddingModelVariant("${VARIANT_PREFIX}.google.tensor_g5.tflite", 191_971_472L),
  // Qualcomm Snapdragon (SM8550 / SM8650 / SM8750 / SM8850).
  listOf("sm8550") to EmbeddingModelVariant("${VARIANT_PREFIX}.qualcomm.sm8550.tflite", 190_290_464L),
  listOf("sm8650") to EmbeddingModelVariant("${VARIANT_PREFIX}.qualcomm.sm8650.tflite", 190_294_560L),
  listOf("sm8750") to EmbeddingModelVariant("${VARIANT_PREFIX}.qualcomm.sm8750.tflite", 184_363_552L),
  listOf("sm8850") to EmbeddingModelVariant("${VARIANT_PREFIX}.qualcomm.sm8850.tflite", 184_474_144L),
  // MediaTek Dimensity (MT6991 / MT6993).
  listOf("mt6991") to EmbeddingModelVariant("${VARIANT_PREFIX}.mediatek.mt6991.tflite", 187_803_028L),
  listOf("mt6993") to EmbeddingModelVariant("${VARIANT_PREFIX}.mediatek.mt6993.tflite", 183_543_612L),
)

/**
 * Maps a device SoC to its EmbeddingGemma `.tflite` variant. Pure over the
 * [android.os.Build.SOC_MODEL] / [android.os.Build.SOC_MANUFACTURER] strings (API 31+, the node's
 * minSdk) so it is unit-testable with no device — the call site reads `Build.SOC_*` and passes them.
 *
 * Matches case-insensitively on substrings of either string (e.g. `"Tensor G5"`, `"SM8650"`,
 * `"MT6991"`). Any unrecognized, blank, or null SoC — and specifically Pixel 9's Tensor G4, which has
 * NO dedicated build — falls back to [EMBEDDING_GENERIC_VARIANT].
 */
fun selectEmbeddingVariant(socModel: String?, socManufacturer: String?): EmbeddingModelVariant {
  val haystack = "${socModel.orEmpty()} ${socManufacturer.orEmpty()}".lowercase()
  if (haystack.isBlank()) return EMBEDDING_GENERIC_VARIANT
  for ((needles, variant) in EMBEDDING_VARIANTS) {
    if (needles.any { haystack.contains(it) }) return variant
  }
  return EMBEDDING_GENERIC_VARIANT
}

/** The HF `resolve/main` download URL for the [variant]'s `.tflite` file. */
fun downloadUrlFor(variant: EmbeddingModelVariant): String =
  "https://huggingface.co/$EMBEDDING_REPO_ID/resolve/main/${variant.fileName}"

/** The HF `resolve/main` download URL for the paired SentencePiece tokenizer. */
fun tokenizerDownloadUrl(): String =
  "https://huggingface.co/$EMBEDDING_REPO_ID/resolve/main/$EMBEDDING_TOKENIZER_FILE"
