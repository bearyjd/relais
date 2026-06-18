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

import android.content.Context
import androidx.room.withTransaction
import cc.grepon.relais.data.RagChunk
import cc.grepon.relais.data.RagDocument
import cc.grepon.relais.data.RelaisDatabase
import cc.grepon.relais.embed.EmbeddingGemmaEmbedder
import cc.grepon.relais.embed.EmbeddingTask
import cc.grepon.relais.embed.cosineSimilarity
import cc.grepon.relais.embed.truncateMrl

/**
 * On-device RAG corpus (Feature #4): ingest documents (chunk → embed as DOCUMENT → store a 256-dim MRL
 * vector) and retrieve (embed the query as QUERY → brute-force cosine top-k over the whole corpus).
 *
 * Brute-force, no vector index (no sqlite-vec on Android), so the corpus has a practical ceiling
 * (~10k chunks) — documented at the endpoint. Retrieval is per-request opt-in, never silent.
 *
 * Suspend functions (the DAO is suspend); the thread-based HTTP server bridges via `runBlocking`,
 * matching `RelaisSessionStore`.
 */
object RagStore {

  /** Stored vector dimension: EmbeddingGemma's 768 truncated via Matryoshka to keep the corpus small. */
  const val STORE_DIM = 256

  /** Target tokens per chunk — comfortably under the seq512 model limit once the prompt prefix + BOS/EOS are added. */
  const val CHUNK_TARGET_TOKENS = 400

  /**
   * Hard ceiling on total stored chunks. The retrieval scan is brute-force in-memory (no on-device
   * vector index), so this bounds the per-query allocation + latency and keeps the node from OOMing;
   * ingest is rejected once it would push the corpus past this.
   */
  const val MAX_CORPUS_CHUNKS = 10_000

  /** Outcome of an ingest attempt. */
  sealed interface IngestOutcome {
    data class Stored(val documentId: Long, val chunkCount: Int) : IngestOutcome
    /** [text] had no embeddable content (e.g. only whitespace/punctuation). */
    data object Empty : IngestOutcome
    /** The corpus is at capacity ([cap]); nothing was stored. */
    data class OverCapacity(val current: Int, val cap: Int) : IngestOutcome
  }

  data class Retrieved(val text: String, val score: Float, val documentId: Long, val chunkIndex: Int)

  /**
   * Chunks [text], embeds each chunk as a DOCUMENT, truncates to [STORE_DIM], and stores it under a new
   * document titled [title] — the document row + its chunks commit ATOMICALLY (no orphan rows on a
   * mid-insert failure). Rejected ([OverCapacity]) if it would exceed [MAX_CORPUS_CHUNKS]; [Empty] if
   * the text yields no chunks. [embedder] must be loaded (caller gates on it).
   */
  suspend fun ingest(context: Context, title: String, text: String, embedder: EmbeddingGemmaEmbedder): IngestOutcome {
    val chunks = RagChunker.chunk(text, CHUNK_TARGET_TOKENS) { embedder.countTokens(listOf(it)) }
    if (chunks.isEmpty()) return IngestOutcome.Empty

    val db = RelaisDatabase.get(context)
    val dao = db.ragDao()
    // Pre-check before paying for embeddings (a concurrent ingest could still race; the soft overshoot
    // is bounded by one document's chunks and harmless to the brute-force scan).
    val existing = dao.chunkCount()
    if (existing + chunks.size > MAX_CORPUS_CHUNKS) return IngestOutcome.OverCapacity(existing, MAX_CORPUS_CHUNKS)

    val vectors = embedder.embed(context, chunks, EmbeddingTask.DOCUMENT) // 768-dim, L2-normalized (off-txn)
    val now = System.currentTimeMillis()
    return db.withTransaction {
      val docId = dao.insertDocument(RagDocument(title = title, createdAt = now))
      val rows = chunks.mapIndexed { i, chunkText ->
        val v = truncateMrl(vectors[i], STORE_DIM) // first STORE_DIM components, re-normalized
        RagChunk(
          documentId = docId,
          chunkIndex = i,
          text = chunkText,
          embedding = RagVectorCodec.encode(v),
          dim = STORE_DIM,
          createdAt = now,
        )
      }
      dao.insertChunks(rows)
      IngestOutcome.Stored(documentId = docId, chunkCount = chunks.size)
    }
  }

  /**
   * Embeds [queryText] as a QUERY, then returns the [topK] corpus chunks by descending cosine
   * similarity. Chunks whose stored dimension differs from the query's are skipped (defensive — all
   * current chunks are [STORE_DIM]).
   */
  suspend fun query(context: Context, queryText: String, topK: Int, embedder: EmbeddingGemmaEmbedder): List<Retrieved> {
    val qv = truncateMrl(embedder.embed(context, listOf(queryText), EmbeddingTask.QUERY).single(), STORE_DIM)
    val chunks = RelaisDatabase.get(context).ragDao().allChunks()
    return chunks
      .asSequence()
      .mapNotNull { c ->
        val v = RagVectorCodec.decode(c.embedding)
        if (v.size != qv.size) null
        else Retrieved(text = c.text, score = cosineSimilarity(qv, v), documentId = c.documentId, chunkIndex = c.chunkIndex)
      }
      .sortedByDescending { it.score }
      .take(topK)
      .toList()
  }

  suspend fun delete(context: Context, documentId: Long) {
    RelaisDatabase.get(context).ragDao().deleteDocument(documentId)
  }

  suspend fun documents(context: Context): List<RagDocument> =
    RelaisDatabase.get(context).ragDao().allDocuments()

  /** (documentCount, chunkCount) for status/metrics. */
  suspend fun stats(context: Context): Pair<Int, Int> {
    val dao = RelaisDatabase.get(context).ragDao()
    return dao.documentCount() to dao.chunkCount()
  }
}
