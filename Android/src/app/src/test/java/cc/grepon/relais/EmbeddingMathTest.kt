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

import cc.grepon.relais.embed.cosineSimilarity
import cc.grepon.relais.embed.l2Normalize
import cc.grepon.relais.embed.truncateMrl
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Pure JVM tests for [cc.grepon.relais.embed.EmbeddingMath] — cosine similarity, L2 normalization,
 * and Matryoshka (MRL) truncation. No Android types; deterministic. These guard the math that
 * consumers (RAG re-ranking, `/v1/embeddings` post-processing) rely on independent of which
 * embedder impl feature #6's follow-up registers.
 */
class EmbeddingMathTest {

  private val eps = 1e-5f

  @Test fun `cosine of identical vectors is one`() {
    val v = floatArrayOf(1f, 2f, 3f, 4f)
    assertEquals(1.0f, cosineSimilarity(v, v), eps)
  }

  @Test fun `cosine of orthogonal vectors is zero`() {
    val a = floatArrayOf(1f, 0f)
    val b = floatArrayOf(0f, 1f)
    assertEquals(0.0f, cosineSimilarity(a, b), eps)
  }

  @Test fun `cosine of opposite vectors is minus one`() {
    val a = floatArrayOf(1f, 2f, 3f)
    val b = floatArrayOf(-1f, -2f, -3f)
    assertEquals(-1.0f, cosineSimilarity(a, b), eps)
  }

  @Test fun `cosine is scale invariant`() {
    val a = floatArrayOf(3f, 4f)
    val b = floatArrayOf(6f, 8f) // same direction, 2x magnitude
    assertEquals(1.0f, cosineSimilarity(a, b), eps)
  }

  @Test fun `cosine with a zero vector is zero (no NaN)`() {
    val a = floatArrayOf(0f, 0f, 0f)
    val b = floatArrayOf(1f, 2f, 3f)
    assertEquals(0.0f, cosineSimilarity(a, b), eps)
  }

  @Test fun `cosine rejects mismatched lengths`() {
    assertThrows(IllegalArgumentException::class.java) {
      cosineSimilarity(floatArrayOf(1f, 2f), floatArrayOf(1f, 2f, 3f))
    }
  }

  @Test fun `l2Normalize yields unit length`() {
    val v = floatArrayOf(3f, 4f) // |v| == 5
    val n = l2Normalize(v)
    val mag = sqrt((n[0] * n[0] + n[1] * n[1]).toDouble()).toFloat()
    assertEquals(1.0f, mag, eps)
    assertEquals(0.6f, n[0], eps)
    assertEquals(0.8f, n[1], eps)
  }

  @Test fun `l2Normalize does not mutate its input`() {
    val v = floatArrayOf(3f, 4f)
    l2Normalize(v)
    assertEquals(3f, v[0], 0f)
    assertEquals(4f, v[1], 0f)
  }

  @Test fun `l2Normalize of a zero vector returns zeros (no NaN)`() {
    val n = l2Normalize(floatArrayOf(0f, 0f, 0f))
    assertEquals(0f, n[0], 0f)
    assertEquals(0f, n[1], 0f)
    assertEquals(0f, n[2], 0f)
  }

  @Test fun `truncateMrl takes the first dim components and re-normalizes`() {
    val v = floatArrayOf(3f, 4f, 99f, 99f) // first two are the 3-4-5 triangle
    val t = truncateMrl(v, 2)
    assertEquals(2, t.size)
    // Direction of the first two components is preserved, re-normalized to unit length.
    assertEquals(0.6f, t[0], eps)
    assertEquals(0.8f, t[1], eps)
    val mag = sqrt((t[0] * t[0] + t[1] * t[1]).toDouble()).toFloat()
    assertEquals(1.0f, mag, eps)
  }

  @Test fun `truncateMrl to full length returns a re-normalized copy`() {
    val v = floatArrayOf(3f, 4f)
    val t = truncateMrl(v, 2)
    assertEquals(2, t.size)
    val mag = sqrt((t[0] * t[0] + t[1] * t[1]).toDouble()).toFloat()
    assertEquals(1.0f, mag, eps)
  }

  @Test fun `truncateMrl rejects dim larger than the vector`() {
    assertThrows(IllegalArgumentException::class.java) {
      truncateMrl(floatArrayOf(1f, 2f), 3)
    }
  }

  @Test fun `truncateMrl rejects non-positive dim`() {
    assertThrows(IllegalArgumentException::class.java) {
      truncateMrl(floatArrayOf(1f, 2f), 0)
    }
  }
}
