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
 * Data access for the async batch queue (Feature #14). Parameterized queries only (no injection from a
 * hostile jobId). Suspend — driven from the worker + the HTTP layer's `runBlocking` bridge.
 */
@Dao
interface BatchDao {
  @Insert suspend fun insert(job: BatchJob): Long

  /**
   * Atomic capacity-bounded enqueue: count + insert in ONE transaction so concurrent submits (the HTTP
   * pool is multi-threaded) can't both pass a stale count and overshoot [cap]. Returns false (caller
   * → 429) when the queue is already at [cap].
   */
  @Transaction
  suspend fun insertIfUnderCap(job: BatchJob, queuedStatus: String, cap: Int): Boolean {
    if (countByStatus(queuedStatus) >= cap) return false
    insert(job)
    return true
  }

  @Query("SELECT * FROM batch_jobs WHERE jobId = :jobId LIMIT 1")
  suspend fun byJobId(jobId: String): BatchJob?

  /** Oldest queued jobs first, capped — the worker drains these. */
  @Query("SELECT * FROM batch_jobs WHERE status = :status ORDER BY createdAt ASC, id ASC LIMIT :limit")
  suspend fun byStatus(status: String, limit: Int): List<BatchJob>

  /**
   * Atomically claim a queued job for this worker: flip queued→running only if it is STILL queued.
   * Returns 1 if this worker won the claim, 0 if another concurrent drain already took it. This is the
   * mutual-exclusion that a separate `byStatus` + `updateStatus` lacks — it prevents double-execution
   * (and a double webhook) when two drains overlap.
   */
  @Query("UPDATE batch_jobs SET status = :running, updatedAt = :now WHERE jobId = :jobId AND status = :queued")
  suspend fun claim(jobId: String, queued: String, running: String, now: Long): Int

  /**
   * Recover jobs stranded in `running` by a worker that died mid-job (process kill, reboot, OOM, or the
   * per-run time budget): flip any running job older than [cutoffMs] to `failed` with [errorJson], so a
   * client never polls `running` forever. [cutoffMs] must be well past the max single-job time.
   */
  @Query("UPDATE batch_jobs SET status = :failed, resultJson = :errorJson, updatedAt = :now WHERE status = :running AND updatedAt < :cutoffMs")
  suspend fun failStaleRunning(running: String, failed: String, errorJson: String, now: Long, cutoffMs: Long): Int

  @Query("UPDATE batch_jobs SET status = :status, updatedAt = :now WHERE jobId = :jobId")
  suspend fun updateStatus(jobId: String, status: String, now: Long)

  @Query("UPDATE batch_jobs SET status = :status, resultJson = :result, updatedAt = :now WHERE jobId = :jobId")
  suspend fun finish(jobId: String, status: String, result: String, now: Long)

  @Query("SELECT COUNT(*) FROM batch_jobs WHERE status = :status")
  suspend fun countByStatus(status: String): Int

  @Query("SELECT COUNT(*) FROM batch_jobs")
  suspend fun count(): Int

  /** TTL prune of finished/old jobs so the table doesn't grow unbounded. */
  @Query("DELETE FROM batch_jobs WHERE createdAt < :cutoffMs")
  suspend fun deleteOlderThan(cutoffMs: Long)
}
