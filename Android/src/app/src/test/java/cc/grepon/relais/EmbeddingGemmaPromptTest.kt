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

import cc.grepon.relais.embed.EmbeddingTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure JVM tests for [EmbeddingTask] — the EmbeddingGemma instruction prefixes. The exact prefix
 * strings are the canonical `google/embeddinggemma-300m` retrieval prompts; embedding a query with
 * the document prefix (or vice-versa) silently degrades retrieval, so the strings are pinned here.
 */
class EmbeddingGemmaPromptTest {

  @Test fun `query prefix is the canonical search-result query prompt`() {
    assertEquals("task: search result | query: ", EmbeddingTask.QUERY.prefix)
    assertEquals("task: search result | query: the capital of France", EmbeddingTask.QUERY.apply("the capital of France"))
  }

  @Test fun `document prefix is the canonical title-none text prompt`() {
    assertEquals("title: none | text: ", EmbeddingTask.DOCUMENT.prefix)
    assertEquals("title: none | text: Paris is the capital.", EmbeddingTask.DOCUMENT.apply("Paris is the capital."))
  }

  @Test fun `the endpoint default is DOCUMENT`() {
    assertEquals(EmbeddingTask.DOCUMENT, EmbeddingTask.DEFAULT)
  }

  @Test fun `fromRequest maps null and blank to the default`() {
    assertEquals(EmbeddingTask.DEFAULT, EmbeddingTask.fromRequest(null))
    assertEquals(EmbeddingTask.DEFAULT, EmbeddingTask.fromRequest(""))
    assertEquals(EmbeddingTask.DEFAULT, EmbeddingTask.fromRequest("   "))
  }

  @Test fun `fromRequest recognizes query aliases case-insensitively`() {
    for (v in listOf("query", "QUERY", "Search_Query", "search query", "question", "retrieval_query")) {
      assertEquals("task='$v'", EmbeddingTask.QUERY, EmbeddingTask.fromRequest(v))
    }
  }

  @Test fun `fromRequest recognizes document aliases case-insensitively`() {
    for (v in listOf("document", "DOCUMENT", "passage", "search_document", "retrieval_document")) {
      assertEquals("task='$v'", EmbeddingTask.DOCUMENT, EmbeddingTask.fromRequest(v))
    }
  }

  @Test fun `fromRequest returns null for an unrecognized task so the caller can 400`() {
    assertNull(EmbeddingTask.fromRequest("classification"))
    assertNull(EmbeddingTask.fromRequest("nonsense"))
  }
}
