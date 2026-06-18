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
 * EmbeddingGemma is trained with task-specific instruction prefixes prepended to the raw text before
 * tokenization. Embedding a query and a document with the SAME prefix collapses the retrieval
 * asymmetry the model was tuned for, so the caller must say which side it is embedding.
 *
 * The two retrieval prefixes are the canonical EmbeddingGemma prompts (see the
 * `google/embeddinggemma-300m` Sentence-Transformers `prompts` config): a search query uses the
 * `query` prompt, an indexed passage uses the `document` prompt. The trailing space is part of the
 * prefix — the text follows immediately.
 */
enum class EmbeddingTask(val prefix: String) {
  /** A search query / question being matched against an index. */
  QUERY("task: search result | query: "),

  /** A passage/document being embedded for storage and later retrieval (the `/v1/embeddings` default). */
  DOCUMENT("title: none | text: "),
  ;

  /** Prepends this task's instruction prefix to [text]. */
  fun apply(text: String): String = prefix + text

  companion object {
    /**
     * The default for the OpenAI-style `/v1/embeddings` endpoint: inputs are treated as documents to
     * be embedded into vectors. Retrieval queries opt in explicitly (the request task param / the
     * embedder's query path), so a client that just "embeds these texts" gets the document side.
     */
    val DEFAULT: EmbeddingTask = DOCUMENT

    /**
     * Parses a request-supplied task selector (case-insensitive). `null`/blank → [DEFAULT];
     * `"query"`/`"search_query"`/`"question"` → [QUERY]; `"document"`/`"passage"`/`"search_document"`
     * → [DOCUMENT]. Returns `null` for an unrecognized non-blank value so the caller can 400.
     */
    fun fromRequest(value: String?): EmbeddingTask? {
      val v = value?.trim()?.lowercase() ?: return DEFAULT
      if (v.isEmpty()) return DEFAULT
      return when (v) {
        "query", "search_query", "search query", "question", "retrieval_query" -> QUERY
        "document", "passage", "search_document", "search document", "retrieval_document" -> DOCUMENT
        else -> null
      }
    }
  }
}
