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

package cc.grepon.relais

import cc.grepon.relais.embed.EMBEDDING_GENERIC_VARIANT
import cc.grepon.relais.embed.EMBEDDING_TOKENIZER_FILE
import cc.grepon.relais.embed.downloadUrlFor
import cc.grepon.relais.embed.selectEmbeddingVariant
import cc.grepon.relais.embed.tokenizerDownloadUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [cc.grepon.relais.embed.EmbeddingModelSelector] — maps a device SoC to the right
 * `litert-community/embeddinggemma-300m` `.tflite` variant (seq512, mixed-precision). The selector is
 * a pure function over the Build.SOC_* strings so it is unit-testable with no device; the call site
 * reads [android.os.Build.SOC_MODEL]/[android.os.Build.SOC_MANUFACTURER] (API 31+) and passes them in.
 *
 * KNOWN facts (gated repo — NOT fetched here): there is NO Tensor-G4 build, so Pixel 9 (Tensor G4)
 * and any unmatched SoC fall back to the GENERIC variant.
 */
class EmbeddingModelSelectorTest {

  @Test fun `Tensor G5 selects the tensor_g5 variant`() {
    val v = selectEmbeddingVariant("Tensor G5", "Google")
    assertEquals("embeddinggemma-300M_seq512_mixed-precision.google.tensor_g5.tflite", v.fileName)
  }

  @Test fun `Tensor G4 falls back to GENERIC (no G4 build exists)`() {
    val v = selectEmbeddingVariant("Tensor G4", "Google")
    assertEquals(EMBEDDING_GENERIC_VARIANT, v)
  }

  @Test fun `null SoC falls back to GENERIC`() {
    assertEquals(EMBEDDING_GENERIC_VARIANT, selectEmbeddingVariant(null, null))
  }

  @Test fun `blank SoC falls back to GENERIC`() {
    assertEquals(EMBEDDING_GENERIC_VARIANT, selectEmbeddingVariant("", ""))
  }

  @Test fun `unknown SoC falls back to GENERIC`() {
    assertEquals(EMBEDDING_GENERIC_VARIANT, selectEmbeddingVariant("Exynos 2400", "Samsung"))
  }

  @Test fun `Qualcomm SM8550 selects the sm8550 variant`() {
    val v = selectEmbeddingVariant("SM8550", "Qualcomm")
    assertEquals("embeddinggemma-300M_seq512_mixed-precision.qualcomm.sm8550.tflite", v.fileName)
  }

  @Test fun `Qualcomm SM8650 selects the sm8650 variant`() {
    val v = selectEmbeddingVariant("SM8650", "QTI")
    assertEquals("embeddinggemma-300M_seq512_mixed-precision.qualcomm.sm8650.tflite", v.fileName)
  }

  @Test fun `Qualcomm SM8750 selects the sm8750 variant`() {
    val v = selectEmbeddingVariant("SM8750", "Qualcomm")
    assertEquals("embeddinggemma-300M_seq512_mixed-precision.qualcomm.sm8750.tflite", v.fileName)
  }

  @Test fun `Qualcomm SM8850 selects the sm8850 variant`() {
    val v = selectEmbeddingVariant("SM8850", "Qualcomm")
    assertEquals("embeddinggemma-300M_seq512_mixed-precision.qualcomm.sm8850.tflite", v.fileName)
  }

  @Test fun `MediaTek MT6991 selects the mt6991 variant`() {
    val v = selectEmbeddingVariant("MT6991", "MediaTek")
    assertEquals("embeddinggemma-300M_seq512_mixed-precision.mediatek.mt6991.tflite", v.fileName)
  }

  @Test fun `MediaTek MT6993 selects the mt6993 variant`() {
    val v = selectEmbeddingVariant("MT6993", "MediaTek")
    assertEquals("embeddinggemma-300M_seq512_mixed-precision.mediatek.mt6993.tflite", v.fileName)
  }

  @Test fun `matching is case-insensitive on substrings`() {
    assertEquals(
      "embeddinggemma-300M_seq512_mixed-precision.google.tensor_g5.tflite",
      selectEmbeddingVariant("tensor g5", "google").fileName,
    )
    assertEquals(
      "embeddinggemma-300M_seq512_mixed-precision.qualcomm.sm8650.tflite",
      selectEmbeddingVariant("qualcomm sm8650-ab", "qualcomm").fileName,
    )
  }

  @Test fun `GENERIC variant is the documented 179 MB default file`() {
    assertEquals(
      "embeddinggemma-300M_seq512_mixed-precision.tflite",
      EMBEDDING_GENERIC_VARIANT.fileName,
    )
    assertTrue(EMBEDDING_GENERIC_VARIANT.approxBytes > 0L)
  }

  @Test fun `every variant carries a positive approx size`() {
    for (soc in listOf("Tensor G5", "SM8550", "SM8650", "SM8750", "SM8850", "MT6991", "MT6993", null)) {
      assertTrue(selectEmbeddingVariant(soc, null).approxBytes > 0L)
    }
  }

  /**
   * Regression guard for the SoC-separator bug: the real HF files separate the SoC vendor with a
   * DOT (`…mixed-precision.google.tensor_g5.tflite`), not an underscore. An underscore-vendor name
   * 404s on every accelerator device, so no produced filename may contain one.
   */
  @Test fun `no SoC variant uses the underscore-vendor separator that 404s`() {
    for (soc in listOf("Tensor G5", "SM8550", "SM8650", "SM8750", "SM8850", "MT6991", "MT6993")) {
      val name = selectEmbeddingVariant(soc, null).fileName
      assertFalse(
        "filename '$name' uses the underscore-vendor form that does not exist on HuggingFace",
        name.contains("_google") || name.contains("_qualcomm") || name.contains("_mediatek"),
      )
    }
  }

  /**
   * Pins the full filename + byte-size inventory to the live `litert-community/embeddinggemma-300m`
   * repo, verified 2026-06-17 via `GET /api/models/.../tree/main`. Filenames determine the download
   * URL and MUST stay byte-exact; sizes drive the download-progress %. If HuggingFace re-exports and
   * a size assertion fails, re-verify against the tree API and bump — do not relax the filename.
   */
  @Test fun `variant inventory matches the verified HuggingFace tree`() {
    val expected = mapOf(
      "Tensor G5" to ("embeddinggemma-300M_seq512_mixed-precision.google.tensor_g5.tflite" to 191_971_472L),
      "SM8550" to ("embeddinggemma-300M_seq512_mixed-precision.qualcomm.sm8550.tflite" to 190_290_464L),
      "SM8650" to ("embeddinggemma-300M_seq512_mixed-precision.qualcomm.sm8650.tflite" to 190_294_560L),
      "SM8750" to ("embeddinggemma-300M_seq512_mixed-precision.qualcomm.sm8750.tflite" to 184_363_552L),
      "SM8850" to ("embeddinggemma-300M_seq512_mixed-precision.qualcomm.sm8850.tflite" to 184_474_144L),
      "MT6991" to ("embeddinggemma-300M_seq512_mixed-precision.mediatek.mt6991.tflite" to 187_803_028L),
      "MT6993" to ("embeddinggemma-300M_seq512_mixed-precision.mediatek.mt6993.tflite" to 183_543_612L),
    )
    for ((soc, fileAndSize) in expected) {
      val v = selectEmbeddingVariant(soc, null)
      assertEquals(fileAndSize.first, v.fileName)
      assertEquals("approxBytes for $soc", fileAndSize.second, v.approxBytes)
    }
    // GENERIC (no SoC suffix) — the seq512 default that every unmatched SoC + Pixel 9 (G4) falls to.
    assertEquals("embeddinggemma-300M_seq512_mixed-precision.tflite", EMBEDDING_GENERIC_VARIANT.fileName)
    assertEquals(179_132_472L, EMBEDDING_GENERIC_VARIANT.approxBytes)
  }

  @Test fun `downloadUrlFor builds the HF resolve URL for the selected file`() {
    val v = selectEmbeddingVariant("Tensor G5", "Google")
    assertEquals(
      "https://huggingface.co/litert-community/embeddinggemma-300m/resolve/main/" +
        "embeddinggemma-300M_seq512_mixed-precision.google.tensor_g5.tflite",
      downloadUrlFor(v),
    )
  }

  @Test fun `tokenizerDownloadUrl points at the sentencepiece model in the same repo`() {
    assertEquals("sentencepiece.model", EMBEDDING_TOKENIZER_FILE)
    assertEquals(
      "https://huggingface.co/litert-community/embeddinggemma-300m/resolve/main/sentencepiece.model",
      tokenizerDownloadUrl(),
    )
  }
}
