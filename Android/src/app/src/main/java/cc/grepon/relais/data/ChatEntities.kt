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
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class Conversation(
  @PrimaryKey val id: String,
  val title: String,
  val modelId: String,
  val createdAt: Long,
  val updatedAt: Long,
)

@Entity(
  tableName = "chat_turns",
  foreignKeys = [
    ForeignKey(
      entity = Conversation::class,
      parentColumns = ["id"],
      childColumns = ["conversationId"],
      onDelete = ForeignKey.CASCADE,
    )
  ],
  indices = [Index("conversationId"), Index(value = ["conversationId", "createdAt"])],
)
data class ChatTurn(
  @PrimaryKey val id: String,
  val conversationId: String,
  val role: String,            // "user" | "assistant"
  val content: String,
  val attachmentType: String?, // "image" | "audio" | null
  val attachmentPath: String?, // app-storage path, or null
  val answeredByModelId: String?,
  val answeredByBackend: String?,
  val createdAt: Long,
)
