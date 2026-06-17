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

import cc.grepon.relais.embed.SentencePieceModel
import cc.grepon.relais.embed.SentencePieceTokenizer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Byte-exactness test for the **BPE** encode path (EmbeddingGemma's tokenizer is BPE, not Unigram).
 * Golden ids from reference `sentencepiece` 0.2.1 `EncodeAsIds` on a purpose-trained tiny BPE model
 * (`sp_tiny_bpe.model`, vocab 400) configured with the SAME knobs as the real EmbeddingGemma model:
 * `model_type=bpe`, `byte_fallback=true`, `add_dummy_prefix=false`, identity normalizer. Cases exercise
 * greedy merges, inter-word `▁` (no dummy prefix), byte-fallback for OOV ASCII/CJK/BMP+astral emoji,
 * repeated whitespace, and empty input. The real-EmbeddingGemma byte-exact check (262k-vocab BPE) is
 * validated locally against the gated model — see the PR.
 */
class SentencePieceBpeTest {

  private val tokenizer: SentencePieceTokenizer by lazy {
    val bytes = checkNotNull(javaClass.getResourceAsStream("/sp_tiny_bpe.model")) {
      "sp_tiny_bpe.model test resource missing"
    }.readBytes()
    SentencePieceTokenizer(SentencePieceModel.parse(bytes))
  }

  @Test
  fun parsesBpeConfig() {
    val m = tokenizer.model
    assertEquals(SentencePieceModel.MODEL_TYPE_BPE, m.modelType)
    assertEquals(400, m.vocabSize)
    assertTrue("byte_fallback expected", m.byteFallback)
    assertFalse("add_dummy_prefix should be false (matches EmbeddingGemma)", m.addDummyPrefix)
  }

  @Test
  fun encodesBpeByteExactAgainstReference() {
    for ((text, expected) in CASES) {
      assertArrayEquals("BPE encode mismatch for \"$text\"", expected, tokenizer.encode(text))
    }
  }

  private companion object {
    // Golden token ids from Python `sentencepiece` 0.2.1 `EncodeAsIds` on sp_tiny_bpe.model.
    val CASES: List<Pair<String, IntArray>> = listOf(
      "the quick brown fox" to
        intArrayOf(325, 329, 281, 362, 343, 361, 350, 312, 346, 316, 349, 365),
      "search result query what is RAG?" to
        intArrayOf(348, 300, 354, 358, 278, 272, 326, 329, 269, 363, 299, 358, 260, 343, 292, 343,
          397, 372, 75, 67),
      "café Ünïcödé" to
        intArrayOf(354, 347, 369, 367, 343, 399, 346, 375, 354, 376, 352, 367),
      "日本語 ☕" to
        intArrayOf(234, 155, 169, 234, 160, 176, 236, 174, 162, 343, 230, 156, 153),
      "Numbers 12345 mixed" to
        intArrayOf(82, 276, 361, 319, 343, 341, 386, 387, 370, 283, 351, 365, 270),
      "UPPER lower" to intArrayOf(89, 396, 396, 73, 397, 343, 353, 312, 269),
      "😀 a😀b" to intArrayOf(244, 163, 156, 132, 263, 244, 163, 156, 132, 361),
      "  spaces  " to intArrayOf(343, 304, 347, 317, 343, 343),
      "" to intArrayOf(),
    )
  }
}
