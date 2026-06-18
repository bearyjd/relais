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

import cc.grepon.relais.embed.buildEmbeddingInput
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pure JVM tests for [buildEmbeddingInput] — wraps SentencePiece content ids in BOS/EOS and pads to
 * the graph's fixed sequence length. EmbeddingGemma special ids: bos=2, eos=1, pad=0.
 */
class EmbeddingTokenizationTest {

  private val BOS = 2
  private val EOS = 1
  private val PAD = 0

  @Test fun `short content is wrapped in BOS_EOS and right-padded`() {
    val out = buildEmbeddingInput(intArrayOf(10, 11, 12), seqLen = 8, bosId = BOS, eosId = EOS, padId = PAD)
    assertArrayEquals(intArrayOf(BOS, 10, 11, 12, EOS, PAD, PAD, PAD), out.ids)
    assertArrayEquals(intArrayOf(1, 1, 1, 1, 1, 0, 0, 0), out.mask)
    assertEquals(5, out.length)
  }

  @Test fun `empty content yields just BOS_EOS`() {
    val out = buildEmbeddingInput(intArrayOf(), seqLen = 4, bosId = BOS, eosId = EOS, padId = PAD)
    assertArrayEquals(intArrayOf(BOS, EOS, PAD, PAD), out.ids)
    assertArrayEquals(intArrayOf(1, 1, 0, 0), out.mask)
    assertEquals(2, out.length)
  }

  @Test fun `content that exactly fills seqLen minus two is not truncated and not padded`() {
    // seqLen 5 → room for 3 content ids + BOS + EOS.
    val out = buildEmbeddingInput(intArrayOf(7, 8, 9), seqLen = 5, bosId = BOS, eosId = EOS, padId = PAD)
    assertArrayEquals(intArrayOf(BOS, 7, 8, 9, EOS), out.ids)
    assertArrayEquals(intArrayOf(1, 1, 1, 1, 1), out.mask)
    assertEquals(5, out.length)
  }

  @Test fun `overflowing content is tail-truncated, still BOS-prefixed and EOS-terminated, full length`() {
    // seqLen 5 → only 3 content ids fit; the 4th/5th are dropped, EOS still appended last.
    val out = buildEmbeddingInput(intArrayOf(7, 8, 9, 10, 11), seqLen = 5, bosId = BOS, eosId = EOS, padId = PAD)
    assertArrayEquals(intArrayOf(BOS, 7, 8, 9, EOS), out.ids)
    assertArrayEquals(intArrayOf(1, 1, 1, 1, 1), out.mask)
    assertEquals(5, out.length)
    assertEquals("last real token must be EOS", EOS, out.ids[out.length - 1])
  }

  @Test fun `pad positions carry padId in ids and zero in mask`() {
    val out = buildEmbeddingInput(intArrayOf(42), seqLen = 6, bosId = BOS, eosId = EOS, padId = PAD)
    // [BOS, 42, EOS, PAD, PAD, PAD]
    for (i in out.length until 6) {
      assertEquals("ids[$i] should be pad", PAD, out.ids[i])
      assertEquals("mask[$i] should be 0", 0, out.mask[i])
    }
  }

  @Test fun `seqLen below two throws (no room for BOS plus EOS)`() {
    try {
      buildEmbeddingInput(intArrayOf(1), seqLen = 1, bosId = BOS, eosId = EOS, padId = PAD)
      fail("expected IllegalArgumentException")
    } catch (e: IllegalArgumentException) {
      // expected
    }
  }

  @Test fun `a non-zero pad id fills the tail`() {
    val out = buildEmbeddingInput(intArrayOf(5), seqLen = 5, bosId = 2, eosId = 1, padId = 99)
    assertArrayEquals(intArrayOf(2, 5, 1, 99, 99), out.ids)
  }
}
