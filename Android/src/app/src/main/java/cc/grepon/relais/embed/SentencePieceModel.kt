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
 * A parsed SentencePiece `ModelProto` — only the fields the Unigram encoder ([SentencePieceTokenizer])
 * needs. PURE (no Android): hand-parses the protobuf wire format so EmbeddingGemma's `sentencepiece.model`
 * can be loaded with NO native code and NO protobuf dependency, and the encoder unit-tests headlessly.
 *
 * Wire schema (sentencepiece_model.proto, verified against the runtime descriptor):
 *   ModelProto { repeated SentencePiece pieces = 1; TrainerSpec trainer_spec = 2; NormalizerSpec normalizer_spec = 3 }
 *   SentencePiece { string piece = 1; float score = 2; Type type = 3 }   // Type: NORMAL=1 UNKNOWN=2 CONTROL=3 USER_DEFINED=4 BYTE=6
 *   TrainerSpec { ... bool byte_fallback = 35; int32 unk_id = 40; bos_id = 41; eos_id = 42; pad_id = 43 }
 *   NormalizerSpec { string name = 1; bytes precompiled_charsmap = 2; bool add_dummy_prefix = 3;
 *                    bool remove_extra_whitespaces = 4; bool escape_whitespaces = 5 }
 */
class SentencePieceModel
private constructor(
  /** Token piece strings, indexed by token id. */
  val pieces: Array<String>,
  /** Unigram log-prob scores, indexed by token id. */
  val scores: FloatArray,
  /** Piece types ([TYPE_NORMAL]/[TYPE_UNKNOWN]/[TYPE_CONTROL]/[TYPE_USER_DEFINED]/[TYPE_BYTE]), by id. */
  val types: IntArray,
  val unkId: Int,
  val bosId: Int,
  val eosId: Int,
  val padId: Int,
  /** When true, an out-of-vocab char is emitted as its UTF-8 byte pieces (`<0xNN>`), not [unkId]. */
  val byteFallback: Boolean,
  val addDummyPrefix: Boolean,
  val removeExtraWhitespaces: Boolean,
  val escapeWhitespaces: Boolean,
  /** Whether the model carries a non-empty `precompiled_charsmap` (a non-identity normalizer). */
  val hasPrecompiledCharsmap: Boolean,
) {
  val vocabSize: Int get() = pieces.size

  /** Piece string → id, ONLY for [TYPE_NORMAL]/[TYPE_USER_DEFINED] (the strings matched against input). */
  private val pieceToId: HashMap<String, Int> = HashMap(pieces.size * 2)
  /** Byte value (0..255) → token id, derived from the `<0xNN>` [TYPE_BYTE] pieces. -1 if absent. */
  val byteToId: IntArray = IntArray(256) { -1 }
  /** Minimum score over all pieces — the basis for the unknown-node penalty in Viterbi. */
  val minScore: Float
  /** Longest piece length in Unicode code points — bounds the encoder's match window. */
  val maxPieceCodePoints: Int

  init {
    var mn = Float.MAX_VALUE
    var maxCp = 1
    for (i in pieces.indices) {
      val type = types[i]
      if (type == TYPE_NORMAL || type == TYPE_USER_DEFINED) {
        pieceToId[pieces[i]] = i
        val cp = pieces[i].codePointCount(0, pieces[i].length)
        if (cp > maxCp) maxCp = cp
      }
      // SentencePiece's min_score_ (the unknown-node penalty basis) is over NORMAL pieces ONLY —
      // BYTE/CONTROL/UNKNOWN pieces (often score 0) must not drag it down.
      if (type == TYPE_NORMAL && scores[i] < mn) mn = scores[i]
      if (type == TYPE_BYTE) parseBytePiece(pieces[i]).let { if (it in 0..255) byteToId[it] = i }
    }
    minScore = if (mn == Float.MAX_VALUE) 0f else mn
    maxPieceCodePoints = maxCp
  }

  /** id of a NORMAL/USER_DEFINED piece string, or -1. */
  fun idOfPiece(piece: String): Int = pieceToId[piece] ?: -1

  companion object {
    const val TYPE_NORMAL = 1
    const val TYPE_UNKNOWN = 2
    const val TYPE_CONTROL = 3
    const val TYPE_USER_DEFINED = 4
    const val TYPE_BYTE = 6

    private const val KUNK_PENALTY = 10.0f // SentencePiece's fixed unknown penalty.

    /** SentencePiece's unknown-node score: [minScore] minus the fixed penalty. */
    fun unknownScore(model: SentencePieceModel): Float = model.minScore - KUNK_PENALTY

    /** Parses a serialized `ModelProto`. Throws [IllegalArgumentException] on malformed input. */
    fun parse(bytes: ByteArray): SentencePieceModel {
      val pieces = ArrayList<String>()
      val scores = ArrayList<Float>()
      val types = ArrayList<Int>()
      // TrainerSpec / NormalizerSpec defaults (sentencepiece_model.proto proto2 defaults).
      var unkId = 0
      var bosId = 1
      var eosId = 2
      var padId = -1
      var byteFallback = false
      var addDummyPrefix = true
      var removeExtraWhitespaces = true
      var escapeWhitespaces = true
      var hasCharsmap = false

      val r = Reader(bytes, 0, bytes.size)
      while (!r.eom()) {
        val tag = r.readVarint().toInt()
        val field = tag ushr 3
        val wire = tag and 0x7
        when (field) {
          1 -> { // repeated SentencePiece
            val sub = r.readMessage()
            var piece = ""
            var score = 0f
            var type = TYPE_NORMAL
            while (!sub.eom()) {
              val t2 = sub.readVarint().toInt()
              when ((t2 ushr 3)) {
                1 -> piece = sub.readString()
                2 -> score = sub.readFloat32()
                3 -> type = sub.readVarint().toInt()
                else -> sub.skip(t2 and 0x7)
              }
            }
            pieces.add(piece); scores.add(score); types.add(type)
          }
          2 -> { // TrainerSpec
            val sub = r.readMessage()
            while (!sub.eom()) {
              val t2 = sub.readVarint().toInt()
              when ((t2 ushr 3)) {
                35 -> byteFallback = sub.readVarint() != 0L
                40 -> unkId = sub.readVarint().toInt()
                41 -> bosId = sub.readVarint().toInt()
                42 -> eosId = sub.readVarint().toInt()
                43 -> padId = sub.readVarint().toInt()
                else -> sub.skip(t2 and 0x7)
              }
            }
          }
          3 -> { // NormalizerSpec
            val sub = r.readMessage()
            while (!sub.eom()) {
              val t2 = sub.readVarint().toInt()
              when ((t2 ushr 3)) {
                2 -> { val cs = sub.readBytes(); hasCharsmap = cs.isNotEmpty() }
                3 -> addDummyPrefix = sub.readVarint() != 0L
                4 -> removeExtraWhitespaces = sub.readVarint() != 0L
                5 -> escapeWhitespaces = sub.readVarint() != 0L
                else -> sub.skip(t2 and 0x7)
              }
            }
          }
          else -> r.skip(wire)
        }
      }
      require(pieces.isNotEmpty()) { "SentencePiece model has no pieces" }
      return SentencePieceModel(
        pieces = pieces.toTypedArray(),
        scores = scores.toFloatArray(),
        types = types.toIntArray(),
        unkId = unkId, bosId = bosId, eosId = eosId, padId = padId,
        byteFallback = byteFallback, addDummyPrefix = addDummyPrefix,
        removeExtraWhitespaces = removeExtraWhitespaces, escapeWhitespaces = escapeWhitespaces,
        hasPrecompiledCharsmap = hasCharsmap,
      )
    }

    /** `"<0x41>"` → 0x41; -1 if not a byte piece. */
    private fun parseBytePiece(s: String): Int =
      if (s.length == 6 && s.startsWith("<0x") && s.endsWith(">"))
        s.substring(3, 5).toIntOrNull(16) ?: -1
      else -1

    /** Minimal protobuf wire reader over a sub-range of a byte array. */
    private class Reader(val buf: ByteArray, var pos: Int, val end: Int) {
      fun eom(): Boolean = pos >= end

      fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
          require(pos < end) { "truncated varint" }
          val b = buf[pos++].toInt() and 0xFF
          result = result or ((b.toLong() and 0x7F) shl shift)
          if (b < 0x80) break
          shift += 7
          require(shift < 64) { "varint too long" }
        }
        return result
      }

      fun readFloat32(): Float {
        require(pos + 4 <= end) { "truncated float" }
        val bits = (buf[pos].toInt() and 0xFF) or
          ((buf[pos + 1].toInt() and 0xFF) shl 8) or
          ((buf[pos + 2].toInt() and 0xFF) shl 16) or
          ((buf[pos + 3].toInt() and 0xFF) shl 24)
        pos += 4
        return Float.fromBits(bits)
      }

      fun readBytes(): ByteArray {
        val len = readVarint().toInt()
        // `len in 0..(end - pos)` is overflow-safe: `end - pos` is non-negative, so a hostile huge
        // length (which would overflow `pos + len`) fails the check cleanly instead of going negative.
        require(len in 0..(end - pos)) { "bad length-delimited field" }
        val out = buf.copyOfRange(pos, pos + len)
        pos += len
        return out
      }

      fun readString(): String = String(readBytes(), Charsets.UTF_8)

      fun readMessage(): Reader {
        val len = readVarint().toInt()
        require(len in 0..(end - pos)) { "bad sub-message length" }
        val sub = Reader(buf, pos, pos + len)
        pos += len
        return sub
      }

      fun skip(wire: Int) {
        when (wire) {
          0 -> readVarint()
          1 -> pos += 8
          2 -> { val len = readVarint().toInt(); require(len in 0..(end - pos)); pos += len }
          5 -> pos += 4
          else -> throw IllegalArgumentException("unknown wire type $wire")
        }
      }
    }
  }
}
