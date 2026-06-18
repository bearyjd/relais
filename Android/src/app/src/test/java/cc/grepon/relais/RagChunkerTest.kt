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

import cc.grepon.relais.rag.RagChunker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pure JVM tests for [RagChunker]. Token counting is injected as a word count so the assertions are
 * deterministic without a real tokenizer (the production caller passes the SentencePiece counter).
 */
class RagChunkerTest {

  // Deterministic stand-in for the tokenizer: one "token" per whitespace-separated word.
  private val words: (String) -> Int = { it.split(Regex("\\s+")).filter { w -> w.isNotEmpty() }.size }

  @Test fun `packs sentences greedily up to the token budget`() {
    val text = "One two three. Four five six. Seven eight nine."
    // Each sentence = 3 words; budget 6 → two sentences per chunk.
    val chunks = RagChunker.chunk(text, targetTokens = 6, countTokens = words)
    assertEquals(2, chunks.size)
    assertEquals("One two three. Four five six.", chunks[0])
    assertEquals("Seven eight nine.", chunks[1])
  }

  @Test fun `each chunk stays within the token budget`() {
    val text = "Alpha beta. Gamma delta. Epsilon zeta. Eta theta. Iota kappa."
    val chunks = RagChunker.chunk(text, targetTokens = 4, countTokens = words)
    assertTrue(chunks.isNotEmpty())
    for (c in chunks) assertTrue("chunk '$c' exceeds budget", words(c) <= 4)
  }

  @Test fun `a single oversized sentence is hard-split on word boundaries`() {
    val text = "one two three four five six seven eight nine ten."
    // 10 words, no sentence breaks, budget 3 → ceil(10/3) = 4 chunks, each <= 3 words.
    val chunks = RagChunker.chunk(text, targetTokens = 3, countTokens = words)
    assertEquals(4, chunks.size)
    for (c in chunks) assertTrue("chunk '$c' exceeds budget", words(c) <= 3)
    // Reassembled words preserve order + completeness.
    assertEquals(
      "one two three four five six seven eight nine ten.",
      chunks.joinToString(" "),
    )
  }

  @Test fun `splits on newlines as boundaries`() {
    val text = "first line\nsecond line\nthird line"
    val chunks = RagChunker.chunk(text, targetTokens = 2, countTokens = words)
    assertEquals(listOf("first line", "second line", "third line"), chunks)
  }

  @Test fun `blank and whitespace-only input yields no chunks`() {
    assertTrue(RagChunker.chunk("", targetTokens = 10, countTokens = words).isEmpty())
    assertTrue(RagChunker.chunk("   \n\t  ", targetTokens = 10, countTokens = words).isEmpty())
  }

  @Test fun `a non-positive budget throws`() {
    try {
      RagChunker.chunk("anything", targetTokens = 0, countTokens = words)
      fail("expected IllegalArgumentException")
    } catch (e: IllegalArgumentException) {
      // expected
    }
  }
}
