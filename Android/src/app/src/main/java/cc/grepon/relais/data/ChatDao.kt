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
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * DAO for the "Chat Depth" persistence layer: [Conversation] + [ChatTurn] (schema v5). Backs the
 * in-app chat's conversation history — list, per-conversation turn stream, rename, delete (which
 * cascades to turns via the FK in [ChatTurn]), and the "edit/retry from here" trim
 * ([deleteTurnsAfter]).
 */
@Dao
interface ChatDao {

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertConversation(c: Conversation)

  @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
  fun observeConversations(): Flow<List<Conversation>>

  @Query("SELECT * FROM chat_turns WHERE conversationId = :conversationId ORDER BY createdAt ASC, id ASC")
  fun observeTurns(conversationId: String): Flow<List<ChatTurn>>

  @Query("SELECT * FROM chat_turns WHERE conversationId = :conversationId ORDER BY createdAt ASC, id ASC")
  suspend fun turnsFor(conversationId: String): List<ChatTurn>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertTurn(t: ChatTurn)

  @Query("UPDATE conversations SET title = :title, updatedAt = :updatedAt WHERE id = :id")
  suspend fun renameConversation(id: String, title: String, updatedAt: Long)

  @Query("UPDATE conversations SET updatedAt = :updatedAt WHERE id = :id")
  suspend fun touch(id: String, updatedAt: Long)

  @Query("DELETE FROM conversations WHERE id = :id")
  suspend fun deleteConversation(id: String)

  @Query("DELETE FROM chat_turns WHERE conversationId = :conversationId AND createdAt > :afterCreatedAt")
  suspend fun deleteTurnsAfter(conversationId: String, afterCreatedAt: Long)
}
