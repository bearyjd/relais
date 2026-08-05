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
 * from the UI's viewModelScope; there is no worker and no network path, by design.
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
}
