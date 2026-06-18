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

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** An ingested RAG source document; its text lives split across [RagChunk] rows. */
@Entity(tableName = "rag_documents", indices = [Index(value = ["createdAt"])])
data class RagDocument(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val title: String,
  val createdAt: Long,
)

/**
 * One retrieval chunk of a [RagDocument]: its [text] and the L2-normalized embedding stored as a
 * little-endian float32 blob ([cc.grepon.relais.rag.RagVectorCodec]). [dim] records the vector length
 * (256 for the MRL-truncated store). Chunks are deleted explicitly with their document (no FK cascade,
 * so the migration stays a plain additive CREATE).
 */
@Entity(tableName = "rag_chunks", indices = [Index(value = ["documentId"])])
class RagChunk(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val documentId: Long,
  val chunkIndex: Int,
  val text: String,
  val embedding: ByteArray,
  val dim: Int,
  val createdAt: Long,
) {
  // Content-equality (the default array reference-equality would be wrong/surprising).
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is RagChunk) return false
    return id == other.id && documentId == other.documentId && chunkIndex == other.chunkIndex &&
      text == other.text && dim == other.dim && createdAt == other.createdAt &&
      embedding.contentEquals(other.embedding)
  }

  override fun hashCode(): Int {
    var result = id.hashCode()
    result = 31 * result + documentId.hashCode()
    result = 31 * result + chunkIndex
    result = 31 * result + text.hashCode()
    result = 31 * result + embedding.contentHashCode()
    result = 31 * result + dim
    result = 31 * result + createdAt.hashCode()
    return result
  }
}
