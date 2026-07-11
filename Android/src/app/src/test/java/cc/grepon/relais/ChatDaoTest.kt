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

import androidx.room.Room
import cc.grepon.relais.data.ChatDao
import cc.grepon.relais.data.ChatTurn
import cc.grepon.relais.data.Conversation
import cc.grepon.relais.data.RelaisDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Robolectric + in-memory Room round-trip for the "Chat Depth" persistence layer (conversations +
 * chat_turns, v4->v5). Mirrors the setup in [RelaisDatabaseTest].
 */
@RunWith(RobolectricTestRunner::class)
class ChatDaoTest {

  private lateinit var db: RelaisDatabase
  private lateinit var dao: ChatDao

  @Before
  fun setUp() {
    db =
      Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), RelaisDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    dao = db.chatDao()
  }

  @After fun tearDown() = db.close()

  @Test
  fun `turns round-trip in createdAt order`() = runBlocking {
    dao.upsertConversation(Conversation("c1", "Hi", "m", 1L, 1L))
    dao.insertTurn(ChatTurn("t1", "c1", "user", "hello", null, null, null, null, 10L))
    dao.insertTurn(ChatTurn("t2", "c1", "assistant", "hi there", null, null, "m", "GPU_LITERTLM", 20L))
    val turns = dao.turnsFor("c1")
    assertEquals(listOf("t1", "t2"), turns.map { it.id })
  }

  @Test
  fun `deleteTurnsAfter drops later turns only`() = runBlocking {
    dao.upsertConversation(Conversation("c1", "Hi", "m", 1L, 1L))
    dao.insertTurn(ChatTurn("t1", "c1", "user", "a", null, null, null, null, 10L))
    dao.insertTurn(ChatTurn("t2", "c1", "assistant", "b", null, null, "m", "GPU_LITERTLM", 20L))
    dao.insertTurn(ChatTurn("t3", "c1", "user", "c", null, null, null, null, 30L))
    dao.deleteTurnsAfter("c1", 15L)
    assertEquals(listOf("t1"), dao.turnsFor("c1").map { it.id })
  }

  @Test
  fun `deleteConversation cascades to turns`() = runBlocking {
    dao.upsertConversation(Conversation("c1", "Hi", "m", 1L, 1L))
    dao.insertTurn(ChatTurn("t1", "c1", "user", "a", null, null, null, null, 10L))
    dao.deleteConversation("c1")
    assertEquals(emptyList<String>(), dao.turnsFor("c1").map { it.id })
  }
}
