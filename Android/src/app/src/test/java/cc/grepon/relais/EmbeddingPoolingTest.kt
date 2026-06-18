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

import cc.grepon.relais.embed.meanPoolMasked
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pure JVM tests for [meanPoolMasked] — the fallback mean-pool used only if a variant emits a
 * per-token `[1, seqLen, dim]` tensor instead of an already-pooled `[1, dim]` one.
 */
class EmbeddingPoolingTest {

  @Test fun `averages only the masked-in token rows, ignoring padding`() {
    // 3 tokens, dim 2; row 0 = [1,2], row 1 = [3,4], row 2 (pad) = [100,100].
    val tokens = floatArrayOf(1f, 2f, 3f, 4f, 100f, 100f)
    val mask = intArrayOf(1, 1, 0)
    assertArrayEquals(floatArrayOf(2f, 3f), meanPoolMasked(tokens, mask, dim = 2), 1e-6f)
  }

  @Test fun `single masked token returns that row unchanged`() {
    // 3 tokens, dim 3 = 9 floats; only row 0 is masked in.
    val tokens = floatArrayOf(5f, 6f, 7f, 9f, 9f, 9f, 8f, 8f, 8f)
    val mask = intArrayOf(1, 0, 0)
    assertArrayEquals(floatArrayOf(5f, 6f, 7f), meanPoolMasked(tokens, mask, dim = 3), 1e-6f)
  }

  @Test fun `all-masked-out yields a zero vector (no NaN)`() {
    val tokens = floatArrayOf(1f, 2f, 3f, 4f)
    val mask = intArrayOf(0, 0)
    assertArrayEquals(floatArrayOf(0f, 0f), meanPoolMasked(tokens, mask, dim = 2), 0f)
  }

  @Test fun `a length that is not seqLen times dim throws`() {
    try {
      meanPoolMasked(floatArrayOf(1f, 2f, 3f), intArrayOf(1, 1), dim = 2) // 3 != 2*2
      fail("expected IllegalArgumentException")
    } catch (e: IllegalArgumentException) {
      // expected
    }
  }

  @Test fun `non-positive dim throws`() {
    try {
      meanPoolMasked(floatArrayOf(), intArrayOf(), dim = 0)
      fail("expected IllegalArgumentException")
    } catch (e: IllegalArgumentException) {
      // expected
    }
  }
}
