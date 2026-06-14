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

import kotlin.math.sqrt

/**
 * Pure vector math shared by embedding consumers (RAG re-ranking, `/v1/embeddings` post-processing).
 * No Android types, no I/O — every function is deterministic and unit-tested ([EmbeddingMathTest]),
 * independent of which embedder impl a later feature #6 follow-up registers.
 *
 * All functions are non-mutating: they return new arrays and never alter their inputs.
 */

/**
 * Cosine similarity of two equal-length vectors, in `[-1, 1]`. Returns `0` when either vector has
 * zero magnitude (avoids NaN). Throws [IllegalArgumentException] on a length mismatch — a silent
 * truncation would hide a caller bug (mismatched embedding dimensions).
 */
fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
  require(a.size == b.size) { "vector length mismatch: ${a.size} != ${b.size}" }
  var dot = 0.0
  var normA = 0.0
  var normB = 0.0
  for (i in a.indices) {
    val x = a[i].toDouble()
    val y = b[i].toDouble()
    dot += x * y
    normA += x * x
    normB += y * y
  }
  if (normA == 0.0 || normB == 0.0) return 0f
  return (dot / (sqrt(normA) * sqrt(normB))).toFloat()
}

/**
 * Returns a new unit-length (L2-normalized) copy of [v]. A zero vector is returned unchanged (as a
 * fresh zero array) rather than producing NaN. Does not mutate [v].
 */
fun l2Normalize(v: FloatArray): FloatArray {
  var sumSq = 0.0
  for (x in v) sumSq += x.toDouble() * x.toDouble()
  val mag = sqrt(sumSq)
  if (mag == 0.0) return FloatArray(v.size)
  return FloatArray(v.size) { (v[it] / mag).toFloat() }
}

/**
 * Matryoshka (MRL) truncation: take the first [dim] components of [v], then re-normalize to unit
 * length. EmbeddingGemma's representation is trained so a leading slice (768 → 512/256/128) is itself
 * a usable embedding once re-normalized. Throws [IllegalArgumentException] when [dim] is non-positive
 * or larger than the vector — a caller asking for more dimensions than exist is a bug, not a clamp.
 */
fun truncateMrl(v: FloatArray, dim: Int): FloatArray {
  require(dim > 0) { "dim must be positive: $dim" }
  require(dim <= v.size) { "dim ($dim) exceeds vector size (${v.size})" }
  return l2Normalize(v.copyOfRange(0, dim))
}
