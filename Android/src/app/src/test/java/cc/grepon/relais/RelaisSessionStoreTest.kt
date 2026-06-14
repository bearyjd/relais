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
import cc.grepon.relais.data.SessionDao
import cc.grepon.relais.data.SessionTurn
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Robolectric + in-memory Room exercise of the session-memory store SQL (Feature #5): record/load
 * round-trip, isolation between sessions on clear, TTL prune cutoff, per-session cap trim — plus the
 * IP-hash privacy contract on [RelaisSessionStore.hashIp]. Drives the DAO directly (the same queries
 * the store wraps) so the persistence behavior is verified without the file-backed singleton.
 */
@RunWith(RobolectricTestRunner::class)
class RelaisSessionStoreTest {

  private lateinit var db: RelaisDatabase
  private lateinit var dao: SessionDao

  @Before fun setUp() {
    db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), RelaisDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    dao = db.sessionDao()
  }

  @After fun tearDown() {
    db.close()
    // The two prune tests exercise RelaisSessionStore.prune, which uses the file-backed singleton;
    // reset + delete it so its rows never leak into another test's singleton.
    RelaisDatabase.resetForTest()
    RuntimeEnvironment.getApplication().deleteDatabase("relais.db")
  }

  private fun turn(key: String, role: String, text: String, t: Long) =
    SessionTurn(sessionKey = key, role = role, content = text, createdAt = t)

  @Test fun `record then load round-trips oldest-first`() = runBlocking {
    dao.insert(turn("h:a", "user", "hello", 1))
    dao.insert(turn("h:a", "assistant", "hi there", 2))
    val loaded = dao.recentDesc("h:a", 10).asReversed()
    assertEquals(listOf("hello", "hi there"), loaded.map { it.content })
    assertEquals(listOf("user", "assistant"), loaded.map { it.role })
  }

  @Test fun `recentDesc caps to the most recent N`() = runBlocking {
    for (i in 1..6) dao.insert(turn("h:a", "user", "m$i", i.toLong()))
    val recent = dao.recentDesc("h:a", 3).asReversed() // oldest-first within the most-recent 3
    assertEquals(listOf("m4", "m5", "m6"), recent.map { it.content })
  }

  @Test fun `clear empties one session without touching another`() = runBlocking {
    dao.insert(turn("h:a", "user", "a1", 1))
    dao.insert(turn("h:b", "user", "b1", 1))
    dao.deleteFor("h:a")
    assertEquals(0, dao.countFor("h:a"))
    assertEquals(1, dao.countFor("h:b"))
  }

  @Test fun `prune deletes only turns older than the cutoff`() = runBlocking {
    dao.insert(turn("h:a", "user", "old", 100))
    dao.insert(turn("h:a", "user", "new", 1_000))
    dao.deleteOlderThan(500) // delete createdAt < 500
    val remaining = dao.turnsFor("h:a")
    assertEquals(1, remaining.size)
    assertEquals("new", remaining.single().content)
  }

  @Test fun `per-session cap trims oldest beyond keep`() = runBlocking {
    for (i in 1..5) dao.insert(turn("h:a", "user", "m$i", i.toLong()))
    dao.trimToCap("h:a", keep = 2)
    val remaining = dao.turnsFor("h:a")
    assertEquals(listOf("m4", "m5"), remaining.map { it.content })
  }

  @Test fun `cap trim leaves other sessions intact`() = runBlocking {
    for (i in 1..4) dao.insert(turn("h:a", "user", "a$i", i.toLong()))
    dao.insert(turn("h:b", "user", "b1", 1))
    dao.trimToCap("h:a", keep = 1)
    assertEquals(1, dao.countFor("h:a"))
    assertEquals(1, dao.countFor("h:b"))
  }

  @Test fun `distinctSessionKeys returns each session once`() = runBlocking {
    dao.insert(turn("h:a", "user", "a1", 1))
    dao.insert(turn("h:a", "assistant", "a2", 2))
    dao.insert(turn("h:b", "user", "b1", 1))
    assertEquals(setOf("h:a", "h:b"), dao.distinctSessionKeys().toSet())
  }

  @Test fun `prune retroactively trims an over-cap session down to the per-session cap`() = runBlocking {
    // RelaisSessionStore.prune operates on the file-backed singleton, so insert + assert through the
    // singleton DAO (not the in-memory `dao`). 6 turns recorded under the previous (higher) cap; the
    // operator then lowers the cap to 2. The per-session trim only fires on record(), so prune must
    // retroactively catch up the existing over-cap session.
    val ctx = RuntimeEnvironment.getApplication()
    val storeDao = RelaisDatabase.get(ctx).sessionDao()
    for (i in 1..6) storeDao.insert(turn("h:a", "user", "m$i", i.toLong()))
    RelaisSessionStore.prune(
      ctx,
      ttlMs = Long.MAX_VALUE, // never TTL-evict; isolate the cap behavior
      perSessionCap = 2,
      globalMaxTurns = 50_000,
    )
    assertEquals(listOf("m5", "m6"), storeDao.turnsFor("h:a").map { it.content })
  }

  @Test fun `prune global cap evicts oldest rows beyond the global limit across sessions`() = runBlocking {
    // Two sessions, 6 rows total with strictly increasing timestamps across both; a global cap of 3
    // must keep only the 3 newest rows overall (oldest-first eviction), regardless of which session.
    val ctx = RuntimeEnvironment.getApplication()
    val storeDao = RelaisDatabase.get(ctx).sessionDao()
    storeDao.insert(turn("h:a", "user", "a1", 1))
    storeDao.insert(turn("h:b", "user", "b1", 2))
    storeDao.insert(turn("h:a", "user", "a2", 3))
    storeDao.insert(turn("h:b", "user", "b2", 4))
    storeDao.insert(turn("h:a", "user", "a3", 5))
    storeDao.insert(turn("h:b", "user", "b3", 6))
    RelaisSessionStore.prune(
      ctx,
      ttlMs = Long.MAX_VALUE,
      perSessionCap = 200, // high enough that the per-session pass is a no-op here
      globalMaxTurns = 3,
    )
    assertEquals(3, storeDao.countAll())
    val kept = (storeDao.turnsFor("h:a") + storeDao.turnsFor("h:b")).map { it.content }.toSet()
    assertEquals(setOf("a3", "b2", "b3"), kept) // the 3 newest by createdAt (5, 4, 6)
  }

  @Test fun `session and total counts reflect inserts`() = runBlocking {
    dao.insert(turn("h:a", "user", "x", 1))
    dao.insert(turn("h:a", "assistant", "y", 2))
    dao.insert(turn("h:b", "user", "z", 1))
    assertEquals(3, dao.countAll())
    assertEquals(2, dao.sessionCount())
  }

  @Test fun `hashIp never returns the raw ip and is deterministic`() {
    val ip = "192.168.1.42"
    val h1 = RelaisSessionStore.hashIp(ip, salt = "salt123")
    val h2 = RelaisSessionStore.hashIp(ip, salt = "salt123")
    assertEquals(h1, h2)
    assertNotEquals(ip, h1)
    assertFalse(h1!!.contains(ip))
    assertTrue(h1.all { it.isDigit() || it in 'a'..'f' }) // hex only
  }

  @Test fun `hashIp varies by salt and is null for unknown`() {
    assertNotEquals(
      RelaisSessionStore.hashIp("10.0.0.1", "saltA"),
      RelaisSessionStore.hashIp("10.0.0.1", "saltB"),
    )
    assertNull(RelaisSessionStore.hashIp(null, "salt"))
    assertNull(RelaisSessionStore.hashIp("unknown", "salt"))
    assertNull(RelaisSessionStore.hashIp("  ", "salt"))
  }
}
