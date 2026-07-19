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

import android.content.Context

/**
 * Embedding-model facade. litertlm 0.11.0 has NO native embeddings API, so the implementation is a
 * SEPARATE model (a .tflite sentence-embedder) delivered by feature #6. Feature #4 (RAG) consumes
 * this interface. Until #6 registers an impl, [RelaisEmbedderProvider.get] is null.
 */
interface RelaisEmbedder {
  /** Embedding vector dimension (e.g. 384 for bge-small-en-v1.5). */
  val dim: Int

  /**
   * Stable model identifier reported to clients in the `model` field of `/v1/embeddings` and
   * `/v1/rerank` responses — the embedding model that actually did the work, NOT the resident LLM
   * (issue #190). The default (EmbeddingGemma's HF repo id) exists so test fakes need not override
   * it; [EmbeddingGemmaEmbedder] — the only shipped real implementation — states it explicitly
   * rather than relying on this default.
   */
  val modelId: String
    get() = EMBEDDING_REPO_ID

  /** True once a model is provisioned + loaded. Callers gate on this (→ 501 / RAG-disabled). */
  fun isAvailable(context: Context): Boolean

  /** Embeds [texts] in batch; each result has length [dim], L2-normalized. Blocking; call off-main. */
  fun embed(context: Context, texts: List<String>): List<FloatArray>

  /** Token count for usage accounting (tokenizer length, summed). */
  fun countTokens(texts: List<String>): Int
}

/**
 * Process-wide registration seam. The single source of truth that both `/v1/embeddings` (→501 when
 * null) and on-device RAG (→disabled when null) read — so no partial/garbage-vector path can exist
 * before feature #6 lands. Feature #6 calls [register] once at engine init.
 */
object RelaisEmbedderProvider {
  @Volatile private var impl: RelaisEmbedder? = null

  /** Register (or clear, with null) the embedder implementation. */
  fun register(embedder: RelaisEmbedder?) {
    impl = embedder
  }

  /** The registered embedder, or null if none. Callers still check [RelaisEmbedder.isAvailable]. */
  fun get(): RelaisEmbedder? = impl
}
