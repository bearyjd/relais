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
import cc.grepon.relais.data.BatchDao
import cc.grepon.relais.data.BatchJob
import cc.grepon.relais.data.BatchStatus
import cc.grepon.relais.data.RelaisDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Robolectric + in-memory Room tests for the batch queue mechanics the #14 review hardened:
 *  - [BatchDao.claim] is an atomic queued→running gate (the mutual exclusion that stops double-execution);
 *  - [BatchDao.failStaleRunning] reaps only jobs stranded `running` past the cutoff;
 *  - [BatchDao.insertIfUnderCap] enforces the queue cap transactionally (count+insert can't overshoot).
 */
@RunWith(RobolectricTestRunner::class)
class BatchDaoTest {

  private lateinit var db: RelaisDatabase
  private lateinit var dao: BatchDao

  @Before fun setUp() {
    db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), RelaisDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    dao = db.batchDao()
  }

  @After fun tearDown() = db.close()

  private fun queued(jobId: String, createdAt: Long = 1_000L) =
    BatchJob(
      jobId = jobId, status = BatchStatus.QUEUED, requestJson = "{}", resultJson = null,
      webhookUrl = null, createdAt = createdAt, updatedAt = createdAt,
    )

  @Test fun `claim wins once then loses — no double execution`() = runBlocking {
    dao.insert(queued("j1"))
    // First claim flips queued→running and wins.
    assertEquals(1, dao.claim("j1", BatchStatus.QUEUED, BatchStatus.RUNNING, 2_000L))
    assertEquals(BatchStatus.RUNNING, dao.byJobId("j1")?.status)
    // A second, concurrent claim sees it is no longer queued → 0 (the loser skips it).
    assertEquals(0, dao.claim("j1", BatchStatus.QUEUED, BatchStatus.RUNNING, 2_100L))
  }

  @Test fun `failStaleRunning reaps only stale running jobs`() = runBlocking {
    // A running job last touched long ago (orphaned), a fresh running job, and a queued job.
    dao.insert(queued("stale").copy(status = BatchStatus.RUNNING, updatedAt = 1_000L))
    dao.insert(queued("fresh").copy(status = BatchStatus.RUNNING, updatedAt = 9_000L))
    dao.insert(queued("waiting"))

    val reaped = dao.failStaleRunning(
      running = BatchStatus.RUNNING, failed = BatchStatus.FAILED,
      errorJson = """{"error":"interrupted"}""", now = 10_000L, cutoffMs = 5_000L,
    )

    assertEquals(1, reaped)
    assertEquals(BatchStatus.FAILED, dao.byJobId("stale")?.status)
    assertTrue(dao.byJobId("stale")?.resultJson?.contains("interrupted") == true)
    assertEquals(BatchStatus.RUNNING, dao.byJobId("fresh")?.status) // not stale → untouched
    assertEquals(BatchStatus.QUEUED, dao.byJobId("waiting")?.status) // never running → untouched
  }

  @Test fun `insertIfUnderCap enforces the queue cap transactionally`() = runBlocking {
    assertTrue(dao.insertIfUnderCap(queued("a"), BatchStatus.QUEUED, cap = 2))
    assertTrue(dao.insertIfUnderCap(queued("b"), BatchStatus.QUEUED, cap = 2))
    // At cap → rejected, and nothing inserted.
    assertFalse(dao.insertIfUnderCap(queued("c"), BatchStatus.QUEUED, cap = 2))
    assertEquals(2, dao.countByStatus(BatchStatus.QUEUED))
    // A running job doesn't count against the queued cap → a new queued job fits again.
    dao.claim("a", BatchStatus.QUEUED, BatchStatus.RUNNING, 3_000L)
    assertTrue(dao.insertIfUnderCap(queued("d"), BatchStatus.QUEUED, cap = 2))
  }
}
