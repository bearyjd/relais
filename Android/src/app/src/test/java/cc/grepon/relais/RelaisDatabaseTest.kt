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
import cc.grepon.relais.data.RelaisDatabase
import cc.grepon.relais.data.SchemaMeta
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Robolectric + in-memory Room: proves the shared DB + DAO wiring that #4 (RAG), #5 (sessions), and
 * #14 (batch) extend. The `SchemaMeta` marker exists so the v1 DB compiles and to record "created by".
 */
@RunWith(RobolectricTestRunner::class)
class RelaisDatabaseTest {

  private lateinit var db: RelaisDatabase

  @Before fun setUp() {
    db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), RelaisDatabase::class.java)
      .allowMainThreadQueries()
      .build()
  }

  @After fun tearDown() = db.close()

  @Test fun `schema meta round-trips`() = runBlocking {
    db.schemaMetaDao().put(SchemaMeta(id = 1, createdAtMs = 123L, note = "relais"))
    val got = db.schemaMetaDao().get()
    assertEquals(123L, got?.createdAtMs)
    assertEquals("relais", got?.note)
  }

  @Test fun `migrations array carries the contiguous schema upgrade chain`() {
    // #5 added session_turns at v2; #4 added rag_documents/rag_chunks at v3; #14 added batch_jobs at v4;
    // Chat Depth added conversations/chat_turns at v5. Each migration must be wired so an upgrade-in-place
    // keeps existing on-device data (no destructive fallback), and the chain must be contiguous (no
    // version gap).
    val sorted = RelaisDatabase.MIGRATIONS.sortedBy { it.startVersion }
    assertEquals(4, sorted.size)
    sorted.forEachIndexed { i, m ->
      assertEquals(i + 1, m.startVersion)
      assertEquals(i + 2, m.endVersion)
    }
  }

  @Test fun `session turn round-trips on the v2 schema`() = runBlocking {
    val turn = cc.grepon.relais.data.SessionTurn(sessionKey = "h:x", role = "user", content = "hi", createdAt = 5L)
    val id = db.sessionDao().insert(turn)
    val got = db.sessionDao().turnsFor("h:x")
    assertEquals(1, got.size)
    assertEquals("hi", got.single().content)
    assertEquals(id, got.single().id)
  }
}
