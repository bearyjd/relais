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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Byte-exactness test for the pure-Kotlin Unigram encoder, against golden fixtures produced by the
 * reference `sentencepiece` library (Python 0.2.1) on a purpose-trained tiny Unigram model
 * (`sp_tiny.model`, vocab 400, byte_fallback=true, add_dummy_prefix=true, normalizer=identity — the
 * same knobs EmbeddingGemma uses). The cases exercise: dummy prefix, inter-word `▁`, multi-char
 * pieces, byte-fallback for OOV ASCII (uppercase) + CJK + emoji, preserved repeated whitespace, and
 * empty input. The real-EmbeddingGemma byte-exact check (incl. its nmt_nfkc charsmap) runs once HF
 * gated access is granted; this proves the algorithm.
 */
class SentencePieceTokenizerTest {

  private val tokenizer: SentencePieceTokenizer by lazy {
    val bytes = checkNotNull(javaClass.getResourceAsStream("/sp_tiny.model")) {
      "sp_tiny.model test resource missing"
    }.readBytes()
    SentencePieceTokenizer(SentencePieceModel.parse(bytes))
  }

  @Test
  fun parsesModelConfig() {
    val m = tokenizer.model
    assertEquals(400, m.vocabSize)
    assertEquals(0, m.unkId)
    assertEquals(1, m.bosId)
    assertEquals(2, m.eosId)
    assertEquals(3, m.padId)
    assertTrue("byte_fallback expected", m.byteFallback)
    assertTrue("add_dummy_prefix expected", m.addDummyPrefix)
    assertEquals("identity normalizer has no charsmap", false, m.hasPrecompiledCharsmap)
    // Byte pieces <0x00>..<0xFF> at ids 4..259.
    assertEquals(4, m.byteToId[0x00])
    assertEquals(0x47 + 4, m.byteToId[0x47]) // 'G' byte → id 75
  }

  @Test
  fun encodesByteExactAgainstReference() {
    for ((text, expected) in CASES) {
      assertArrayEquals("encode mismatch for ${text.quote()}", expected, tokenizer.encode(text))
    }
  }

  @Test
  fun countTokensSumsEncodedLengths() {
    val texts = CASES.map { it.first }
    val expected = CASES.sumOf { it.second.size }
    assertEquals(expected, tokenizer.countTokens(texts))
  }

  private fun String.quote() = "\"" + replace("\n", "\\n") + "\""

  private companion object {
    // Golden token ids from Python `sentencepiece` 0.2.1 `EncodeAsIds` on sp_tiny.model.
    val CASES: List<Pair<String, IntArray>> = listOf(
      "the quick brown fox" to
        intArrayOf(260, 265, 317, 307, 284, 260, 272, 262, 321, 266, 322, 275),
      "search result query what is RAG?" to
        intArrayOf(260, 261, 286, 271, 294, 260, 262, 306, 318, 317, 263, 345, 328, 294, 334,
          260, 296, 260, 377, 309, 75, 67),
      "café Ünïcödé" to
        intArrayOf(385, 360, 276, 260, 378, 266, 311, 271, 312, 264, 276),
      "日本語 ☕" to
        intArrayOf(260, 234, 155, 169, 234, 160, 176, 236, 174, 162, 260, 230, 156, 153),
      "  leading and   inner spaces " to
        intArrayOf(260, 260, 260, 285, 268, 326, 277, 270, 264, 260, 260, 282, 266, 288, 298,
          268, 355, 261, 260),
      "Numbers 12345 mixed" to
        intArrayOf(260, 82, 278, 343, 262, 261, 260, 380, 290, 368, 369, 308, 260, 356, 275, 289),
      "UPPER lower MiXeD" to
        intArrayOf(260, 89, 376, 376, 73, 377, 260, 274, 321, 288, 260, 81, 301, 92, 263, 72),
      "" to intArrayOf(),
    )
  }
}
