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
 * A fixed-length model input ready to feed to the EmbeddingGemma `.tflite`: token [ids] padded to the
 * graph's sequence length, the parallel attention [mask] (1 for a real token, 0 for padding), and
 * [length] = the count of real (non-pad) tokens including the BOS/EOS sentinels.
 */
data class EmbeddingModelInput(val ids: IntArray, val mask: IntArray, val length: Int) {
  // data class with arrays: value-equality by content so tests compare as expected.
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is EmbeddingModelInput) return false
    return length == other.length && ids.contentEquals(other.ids) && mask.contentEquals(other.mask)
  }

  override fun hashCode(): Int =
    (ids.contentHashCode() * 31 + mask.contentHashCode()) * 31 + length
}

/**
 * Builds the fixed-[seqLen] EmbeddingGemma input from raw content token ids (the SentencePiece output,
 * WITHOUT special tokens): prepends BOS, appends EOS, truncates the content so the whole sequence fits
 * in [seqLen], then right-pads with [padId]. The attention mask marks the real (BOS + content + EOS)
 * span; padding is masked out so a mean-pool over real tokens ignores it.
 *
 * Truncation drops the tail of the content (keeping BOS at the front and EOS at the very end) so the
 * sequence is always exactly [seqLen] long and always terminated by EOS — what the graph expects.
 *
 * @throws IllegalArgumentException if [seqLen] < 2 (no room for BOS + EOS).
 */
fun buildEmbeddingInput(
  contentIds: IntArray,
  seqLen: Int,
  bosId: Int,
  eosId: Int,
  padId: Int,
): EmbeddingModelInput {
  require(seqLen >= 2) { "seqLen must be >= 2 to hold BOS + EOS, was $seqLen" }
  val maxContent = seqLen - 2
  val kept = if (contentIds.size > maxContent) maxContent else contentIds.size
  val length = kept + 2

  val ids = IntArray(seqLen) { padId }
  val mask = IntArray(seqLen) // zero-filled = all padding by default
  ids[0] = bosId
  mask[0] = 1
  for (i in 0 until kept) {
    ids[i + 1] = contentIds[i]
    mask[i + 1] = 1
  }
  ids[kept + 1] = eosId
  mask[kept + 1] = 1
  return EmbeddingModelInput(ids = ids, mask = mask, length = length)
}
