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

  @Test fun `migrations array is wired and empty at v1`() {
    // Consumers (#4/#5/#14) append Migration(n, n+1) here when they add tables + bump the version.
    assertEquals(0, RelaisDatabase.MIGRATIONS.size)
  }
}
