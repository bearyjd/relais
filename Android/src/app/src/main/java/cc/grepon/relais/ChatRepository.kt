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

import android.content.Context
import cc.grepon.relais.data.ChatDao
import cc.grepon.relais.data.ChatTurn
import cc.grepon.relais.data.Conversation
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/**
 * Thin wrapper over [ChatDao] plus attachment-file IO for the in-app "Chat Depth" feature. Owns
 * the mapping between a [ChatTurn.attachmentPath] and the actual bytes on disk, stored under
 * `context.filesDir/chat/`.
 *
 * Note on `Conversation.updatedAt`: [appendUserTurn] and [appendAssistantTurn] cannot safely
 * re-`upsertConversation` with a fresh `updatedAt` without clobbering the title (upsert is
 * `REPLACE`, i.e. a full-row overwrite). Both methods instead call [ChatDao.touch], an
 * `updatedAt`-only update, using the same timestamp stamped on the turn's `createdAt` — this
 * keeps [ChatDao.observeConversations]'s `ORDER BY updatedAt DESC` resurfacing active
 * conversations without touching the title.
 */
class ChatRepository(private val context: Context, private val dao: ChatDao) {

  fun observeConversations(): Flow<List<Conversation>> = dao.observeConversations()

  fun observeTurns(conversationId: String): Flow<List<ChatTurn>> = dao.observeTurns(conversationId)

  suspend fun turnsFor(conversationId: String): List<ChatTurn> = dao.turnsFor(conversationId)

  suspend fun createConversation(title: String, modelId: String): String {
    val id = UUID.randomUUID().toString()
    val now = System.currentTimeMillis()
    dao.upsertConversation(
      Conversation(id = id, title = title, modelId = modelId, createdAt = now, updatedAt = now)
    )
    return id
  }

  suspend fun appendUserTurn(
    conversationId: String,
    text: String,
    attachmentType: String?,
    attachmentBytes: ByteArray?,
  ): ChatTurn {
    val turnId = UUID.randomUUID().toString()
    val attachmentPath =
      if (attachmentBytes != null) {
        writeAttachment(turnId, attachmentType, attachmentBytes)
      } else {
        null
      }
    val now = System.currentTimeMillis()
    val turn =
      ChatTurn(
        id = turnId,
        conversationId = conversationId,
        role = "user",
        content = text,
        attachmentType = attachmentType,
        attachmentPath = attachmentPath,
        answeredByModelId = null,
        answeredByBackend = null,
        createdAt = now,
      )
    dao.insertTurn(turn)
    dao.touch(conversationId, now)
    return turn
  }

  suspend fun appendAssistantTurn(
    conversationId: String,
    content: String,
    modelId: String,
    backend: String,
  ): ChatTurn {
    val now = System.currentTimeMillis()
    val turn =
      ChatTurn(
        id = UUID.randomUUID().toString(),
        conversationId = conversationId,
        role = "assistant",
        content = content,
        attachmentType = null,
        attachmentPath = null,
        answeredByModelId = modelId,
        answeredByBackend = backend,
        createdAt = now,
      )
    dao.insertTurn(turn)
    dao.touch(conversationId, now)
    return turn
  }

  suspend fun rename(conversationId: String, title: String) {
    dao.renameConversation(conversationId, title, System.currentTimeMillis())
  }

  suspend fun delete(conversationId: String) {
    val turns = dao.turnsFor(conversationId)
    dao.deleteConversation(conversationId)
    turns.forEach { turn -> turn.attachmentPath?.let { path -> File(path).delete() } }
  }

  suspend fun truncateAfter(conversationId: String, turn: ChatTurn) {
    val orphaned =
      dao.turnsFor(conversationId).filter { candidate -> candidate.createdAt > turn.createdAt }
    dao.deleteTurnsAfter(conversationId, turn.createdAt)
    orphaned.forEach { orphan -> orphan.attachmentPath?.let { path -> File(path).delete() } }
  }

  private fun writeAttachment(turnId: String, attachmentType: String?, bytes: ByteArray): String {
    val ext =
      when (attachmentType) {
        "image" -> "png"
        "audio" -> "wav"
        else -> "bin"
      }
    val chatDir = File(context.filesDir, "chat")
    if (!chatDir.exists()) {
      chatDir.mkdirs()
    }
    val file = File(chatDir, "$turnId.$ext")
    file.writeBytes(bytes)
    return file.absolutePath
  }
}
