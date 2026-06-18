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

package cc.grepon.relais.rag

/**
 * Splits a document into retrieval chunks of roughly [targetTokens] tokens, breaking on natural
 * boundaries (paragraphs, then sentences) so a chunk stays semantically coherent. Token counting is
 * injected ([countTokens]) so this is a pure function, unit-testable without a real tokenizer; the
 * caller passes the SentencePiece counter.
 *
 * A segment that alone exceeds [targetTokens] is hard-split on word boundaries so no chunk blows past
 * the embedder's sequence length. Blank chunks are dropped.
 */
object RagChunker {

  // Sentence boundary: . ! ? (optionally quoted/closed) followed by whitespace. Also split on newlines.
  private val SENTENCE_BOUNDARY = Regex("(?<=[.!?][\"')\\]]?)\\s+|\\n+")
  private val WHITESPACE = Regex("\\s+")

  fun chunk(text: String, targetTokens: Int, countTokens: (String) -> Int): List<String> {
    require(targetTokens > 0) { "targetTokens must be > 0, was $targetTokens" }
    val segments = SENTENCE_BOUNDARY.split(text).map { it.trim() }.filter { it.isNotEmpty() }
    if (segments.isEmpty()) return emptyList()

    val chunks = mutableListOf<String>()
    val current = StringBuilder()
    var currentTokens = 0

    fun flush() {
      val s = current.toString().trim()
      if (s.isNotEmpty()) chunks.add(s)
      current.setLength(0)
      currentTokens = 0
    }

    for (segment in segments) {
      val segTokens = countTokens(segment)
      if (segTokens > targetTokens) {
        flush()
        for (piece in hardSplit(segment, targetTokens, countTokens)) chunks.add(piece)
        continue
      }
      if (currentTokens > 0 && currentTokens + segTokens > targetTokens) flush()
      if (current.isNotEmpty()) current.append(' ')
      current.append(segment)
      currentTokens += segTokens
    }
    flush()
    return chunks
  }

  /** Splits one oversized segment on word boundaries into pieces that each fit [targetTokens]. */
  private fun hardSplit(segment: String, targetTokens: Int, countTokens: (String) -> Int): List<String> {
    val pieces = mutableListOf<String>()
    val current = StringBuilder()
    var currentTokens = 0
    for (word in segment.split(WHITESPACE)) {
      if (word.isEmpty()) continue
      val wordTokens = countTokens(word).coerceAtLeast(1)
      if (currentTokens > 0 && currentTokens + wordTokens > targetTokens) {
        pieces.add(current.toString())
        current.setLength(0)
        currentTokens = 0
      }
      if (current.isNotEmpty()) current.append(' ')
      current.append(word)
      currentTokens += wordTokens
    }
    if (current.isNotEmpty()) pieces.add(current.toString())
    return pieces
  }
}
