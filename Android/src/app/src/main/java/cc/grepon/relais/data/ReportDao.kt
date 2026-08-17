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

/**
 * Data access for on-device AI-content reports (#258). Parameterized queries only. Suspend — driven
 * from the UI's viewModelScope and, since #273, from `ReportSendWorker`'s coroutine.
 *
 * The "there is no worker and no network path, by design" this KDoc used to claim stopped being true
 * in two steps: #258 gate 1 added the opt-in send, and #273 added the retry worker that drains it.
 * The local write is still unconditional and still never blocks on either.
 */
@Dao
interface ReportDao {
  @Insert suspend fun insert(report: ContentReport): Long

  /** Newest first — the order the operator reviews them in. */
  @Query("SELECT * FROM content_reports ORDER BY createdAt DESC, id DESC LIMIT :limit")
  suspend fun recent(limit: Int): List<ContentReport>

  @Query("SELECT COUNT(*) FROM content_reports") suspend fun count(): Int

  /**
   * Dismiss a single report once the operator has acted on it. Returns the number of rows removed, so
   * a caller can tell a real dismissal from a double-tap on an already-dismissed row.
   */
  @Query("DELETE FROM content_reports WHERE id = :id") suspend fun delete(id: Long): Int

  /** Clear every report. Backs the control panel's bulk action and in-app *Clear data*. */
  @Query("DELETE FROM content_reports") suspend fun clear(): Int

  /** A single row by id — what the retry worker and the manual SEND action re-read before sending. */
  @Query("SELECT * FROM content_reports WHERE id = :id") suspend fun byId(id: Long): ContentReport?

  /**
   * Rows awaiting delivery, OLDEST first (#273) — the retry worker's queue.
   *
   * Oldest-first deliberately, against `recent`'s newest-first review order: the backlog should drain
   * in the order the operator created it, and the Worker's 10-per-hour budget means a run can be cut
   * short, so the report that has waited longest must not be the one perpetually skipped.
   */
  @Query(
    "SELECT * FROM content_reports WHERE sendState = :state ORDER BY createdAt ASC, id ASC LIMIT :limit"
  )
  suspend fun awaitingSend(state: String, limit: Int): List<ContentReport>

  /**
   * Record where an attempt left a row. Returns rows touched, so a caller can tell a real update from
   * an attempt against a row the operator dismissed while the send was in flight.
   */
  @Query(
    "UPDATE content_reports SET sendState = :state, sendAttempts = :attempts, " +
      "lastAttemptAt = :atMs WHERE id = :id"
  )
  suspend fun markSend(id: Long, state: String, attempts: Int, atMs: Long): Int
}
