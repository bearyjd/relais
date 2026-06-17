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

import java.util.PriorityQueue

/**
 * Pure-Kotlin SentencePiece encoder supporting both **Unigram** (Viterbi best-path) and **BPE** (greedy
 * highest-priority adjacent-pair merge), dispatched by the model's `model_type` (feature #6). No native
 * code, no protobuf dependency, so it builds offline and unit-tests headlessly against golden fixtures.
 *
 * Verified BYTE-EXACT against the reference `SentencePieceProcessor.EncodeAsIds` for both algorithms on
 * identity-normalizer models — including the real **EmbeddingGemma** tokenizer (BPE, 262k vocab,
 * identity normalizer, `byte_fallback`).
 *
 * Normalization: escape spaces → `▁`, optional dummy prefix. It does NOT apply a `precompiled_charsmap`
 * (e.g. `nmt_nfkc`) or `remove_extra_whitespaces`; the constructor REJECTS (fail-loud) any model needing
 * those, so it can never silently emit wrong ids. (EmbeddingGemma needs neither — its normalizer is
 * identity.) Out-of-vocab code points resolve to UTF-8 byte pieces (`<0xNN>`) when `byte_fallback`,
 * else `unk`.
 */
class SentencePieceTokenizer(val model: SentencePieceModel) {

  private val unkScore: Float = SentencePieceModel.unknownScore(model)

  init {
    // Fail-loud on normalization features not yet implemented, so a model needing them can never be
    // tokenized to subtly-wrong ids (silent degraded embeddings). Lifted when charsmap support lands.
    require(!model.hasPrecompiledCharsmap) {
      "precompiled_charsmap (e.g. nmt_nfkc) normalization not yet supported"
    }
    require(!model.removeExtraWhitespaces) { "remove_extra_whitespaces normalization not yet supported" }
  }

  /** Encodes [text] to token ids, byte-exact with SentencePiece `EncodeAsIds` (identity normalizer). */
  fun encode(text: String): IntArray {
    if (text.isEmpty()) return IntArray(0)
    val normalized = normalize(text)
    if (normalized.isEmpty()) return IntArray(0)
    val cps = normalized.codePoints().toArray()
    return when (model.modelType) {
      SentencePieceModel.MODEL_TYPE_BPE -> bpeEncode(cps)
      else -> unigramEncode(cps) // UNIGRAM (default)
    }
  }

  /** Unigram: Viterbi best-path over the lattice with a per-position unknown node (minScore − 10). */
  private fun unigramEncode(cps: IntArray): IntArray {
    val n = cps.size

    val best = FloatArray(n + 1) { Float.NEGATIVE_INFINITY }
    val backStart = IntArray(n + 1) { -1 }
    val backId = IntArray(n + 1) { UNKNOWN } // piece id, or UNKNOWN for an unknown-char node
    best[0] = 0f

    val sb = StringBuilder(model.maxPieceCodePoints)
    for (begin in 0 until n) {
      if (best[begin] == Float.NEGATIVE_INFINITY) continue
      var matchedSingleChar = false
      val maxLen = minOf(model.maxPieceCodePoints, n - begin)
      sb.setLength(0)
      for (len in 1..maxLen) {
        sb.appendCodePoint(cps[begin + len - 1])
        val id = model.idOfPiece(sb.toString())
        if (id >= 0) {
          val end = begin + len
          val candidate = best[begin] + model.scores[id]
          if (candidate > best[end]) {
            best[end] = candidate
            backStart[end] = begin
            backId[end] = id
          }
          if (len == 1) matchedSingleChar = true
        }
      }
      if (!matchedSingleChar) {
        // No single-char piece covers this position → SentencePiece inserts a 1-char unknown node.
        val end = begin + 1
        val candidate = best[begin] + unkScore
        if (candidate > best[end]) {
          best[end] = candidate
          backStart[end] = begin
          backId[end] = UNKNOWN
        }
      }
    }

    // Reconstruct the best path (end → 0), then emit in forward order.
    var pos = n
    val starts = ArrayList<Int>()
    val ids = ArrayList<Int>()
    while (pos > 0) {
      starts.add(backStart[pos])
      ids.add(backId[pos])
      pos = backStart[pos]
    }
    starts.reverse()
    ids.reverse()

    val out = ArrayList<Int>(n + 2)
    for (i in ids.indices) {
      val id = ids[i]
      if (id != UNKNOWN) {
        out.add(id)
      } else {
        appendUnknown(cps[starts[i]], out)
      }
    }
    return out.toIntArray()
  }

  /** Total token count across [texts] (for usage accounting). */
  fun countTokens(texts: List<String>): Int = texts.sumOf { encode(it).size }

  /**
   * BPE: greedily merge the highest-priority adjacent pair whose concatenation is a vocab piece
   * (mirrors SentencePiece `bpe_model.cc`). Ties break leftmost. Leftover non-vocab symbols (single
   * OOV code points) byte-fall-back. Operates over a doubly-linked list of symbol spans.
   */
  private fun bpeEncode(cps: IntArray): IntArray {
    val n = cps.size
    val symStart = IntArray(n) { it }
    val symEnd = IntArray(n) { it + 1 }
    val prev = IntArray(n) { it - 1 }
    val next = IntArray(n) { if (it + 1 < n) it + 1 else -1 }
    val alive = BooleanArray(n) { true }
    val sb = StringBuilder(model.maxPieceCodePoints)

    fun pieceString(start: Int, end: Int): String {
      sb.setLength(0)
      for (k in start until end) sb.appendCodePoint(cps[k])
      return sb.toString()
    }

    // Max-heap by score, then leftmost — matches SentencePiece's SymbolPair ordering.
    val pq = PriorityQueue(16, compareByDescending<BpeCand> { it.score }.thenBy { it.left })
    fun tryPush(left: Int) {
      val right = next[left]
      if (right == -1) return
      val id = model.idOfPiece(pieceString(symStart[left], symEnd[right]))
      if (id >= 0) pq.add(BpeCand(model.scores[id], left, symEnd[right] - symStart[left]))
    }
    for (i in 0 until n) tryPush(i)

    while (pq.isNotEmpty()) {
      val c = pq.poll()
      val left = c.left
      val right = next[left]
      if (!alive[left] || right == -1 || !alive[right]) continue
      if (symEnd[right] - symStart[left] != c.len) continue // stale: a neighbor grew since this was pushed
      // Merge `right` into `left`.
      symEnd[left] = symEnd[right]
      alive[right] = false
      val rn = next[right]
      next[left] = rn
      if (rn != -1) prev[rn] = left
      tryPush(left)
      if (prev[left] != -1) tryPush(prev[left])
    }

    val out = ArrayList<Int>(n)
    var i = 0 // symbol 0 has no left neighbor, so it is never consumed → always the live head
    while (i != -1) {
      if (alive[i]) {
        val id = model.idOfPiece(pieceString(symStart[i], symEnd[i]))
        if (id >= 0) out.add(id) else for (k in symStart[i] until symEnd[i]) appendUnknown(cps[k], out)
      }
      i = next[i]
    }
    return out.toIntArray()
  }

  /** Resolves an out-of-vocab code point: its UTF-8 byte pieces when [byteFallback], else `unk`. */
  private fun appendUnknown(codePoint: Int, out: ArrayList<Int>) {
    if (!model.byteFallback) {
      out.add(model.unkId)
      return
    }
    val utf8 = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8)
    for (b in utf8) {
      val byteId = model.byteToId[b.toInt() and 0xFF]
      out.add(if (byteId >= 0) byteId else model.unkId)
    }
  }

  /**
   * SentencePiece normalization for the identity normalizer: optional whitespace collapse, a single
   * dummy-prefix space, then escape every space to `▁` (U+2581).
   */
  private fun normalize(text: String): String {
    // remove_extra_whitespaces is rejected in init; for the identity normalizer only the dummy-prefix
    // space + space→▁ escaping remain.
    var s = text
    if (model.addDummyPrefix) s = " $s"
    if (model.escapeWhitespaces) s = s.replace(' ', SPACE)
    return s
  }

  companion object {
    private const val UNKNOWN = -1
    private const val SPACE = '▁' // ▁ — SentencePiece's whitespace symbol
  }
}

/** A pending BPE merge: the vocab [score] of merging the symbol at [left] with its right neighbor, plus
 * the merged piece's code-point [len] — used to discard a stale candidate after a neighbor grew. */
private class BpeCand(val score: Float, val left: Int, val len: Int)
