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

package cc.grepon.relais.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

/**
 * Data access for the RAG corpus (Feature #4). All queries are parameterized (no string concatenation
 * → no SQL injection from a hostile document id). Suspend functions — called off the response path.
 *
 * Retrieval is brute-force: [allChunks] loads the whole corpus and cosine-ranks it in memory. That
 * caps practical corpus size (~10k chunks), which is documented at the endpoint; there is no on-device
 * vector index (no sqlite-vec on Android).
 */
@Dao
interface RagDao {
  @Insert suspend fun insertDocument(doc: RagDocument): Long

  @Insert suspend fun insertChunks(chunks: List<RagChunk>)

  /** All chunks across the corpus, for the brute-force cosine scan. */
  @Query("SELECT * FROM rag_chunks")
  suspend fun allChunks(): List<RagChunk>

  @Query("SELECT * FROM rag_chunks WHERE documentId = :documentId ORDER BY chunkIndex ASC")
  suspend fun chunksFor(documentId: Long): List<RagChunk>

  @Query("SELECT * FROM rag_documents ORDER BY createdAt DESC, id DESC")
  suspend fun allDocuments(): List<RagDocument>

  @Query("SELECT COUNT(*) FROM rag_chunks")
  suspend fun chunkCount(): Int

  @Query("SELECT COUNT(*) FROM rag_documents")
  suspend fun documentCount(): Int

  @Query("DELETE FROM rag_chunks WHERE documentId = :documentId")
  suspend fun deleteChunksFor(documentId: Long)

  @Query("DELETE FROM rag_documents WHERE id = :documentId")
  suspend fun deleteDocumentRow(documentId: Long)

  /** Deletes a document and all its chunks atomically (manual cascade — no FK constraint). */
  @Transaction
  suspend fun deleteDocument(documentId: Long) {
    deleteChunksFor(documentId)
    deleteDocumentRow(documentId)
  }
}
