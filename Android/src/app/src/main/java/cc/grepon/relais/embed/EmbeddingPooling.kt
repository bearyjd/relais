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
 * Mean-pools a per-token model output into a single sentence embedding, averaging only the real
 * (non-pad) token rows.
 *
 * Most EmbeddingGemma `.tflite` variants emit an already-pooled `[1, dim]` sentence vector, in which
 * case the embedder uses the output directly and never calls this. But if a variant instead emits a
 * per-token `[1, seqLen, dim]` tensor, the embedder mean-pools it here over the attention [mask] so
 * padding contributes nothing.
 *
 * @param tokenEmbeddings flattened `seqLen * dim` row-major (`[t * dim + d]`).
 * @param mask length `seqLen`; rows where `mask[t] != 0` are averaged.
 * @return a fresh `[dim]` mean vector (NOT yet L2-normalized); all-zero if no token is masked in.
 * @throws IllegalArgumentException on a dim/length mismatch.
 */
fun meanPoolMasked(tokenEmbeddings: FloatArray, mask: IntArray, dim: Int): FloatArray {
  require(dim > 0) { "dim must be > 0, was $dim" }
  val seqLen = mask.size
  require(tokenEmbeddings.size == seqLen * dim) {
    "tokenEmbeddings (${tokenEmbeddings.size}) must equal seqLen ($seqLen) * dim ($dim)"
  }
  val out = FloatArray(dim)
  var count = 0
  for (t in 0 until seqLen) {
    if (mask[t] == 0) continue
    count++
    val base = t * dim
    for (d in 0 until dim) out[d] += tokenEmbeddings[base + d]
  }
  if (count == 0) return out
  val inv = 1f / count
  for (d in 0 until dim) out[d] *= inv
  return out
}
