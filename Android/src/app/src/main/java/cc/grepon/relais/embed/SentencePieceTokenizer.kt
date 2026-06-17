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
 * Pure-Kotlin SentencePiece **Unigram** encoder (feature #6). No native code, no protobuf dependency,
 * so it builds offline and unit-tests headlessly against golden fixtures.
 *
 * Implements the Unigram lattice + byte-fallback algorithm, verified BYTE-EXACT against the reference
 * `SentencePieceProcessor.EncodeAsIds` for **identity-normalizer** models. It does NOT yet apply a
 * `precompiled_charsmap` (e.g. `nmt_nfkc`) or `remove_extra_whitespaces`; the constructor REJECTS
 * (fail-loud) any model needing those, so it can never silently emit wrong ids. EmbeddingGemma's real
 * `sentencepiece.model` carries an nmt_nfkc charsmap → full normalization + the byte-exact-vs-real
 * check land in the follow-up that adds charsmap support.
 *
 * Pipeline: normalize (escape spaces → `▁`, optional dummy prefix) → Viterbi best-path over the Unigram
 * lattice (per-position, a 1-char "unknown" node scored `minScore - 10` when no single-char piece
 * matches) → resolve unknown nodes to UTF-8 byte pieces (`<0xNN>`) when `byte_fallback`, else `unk`.
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
