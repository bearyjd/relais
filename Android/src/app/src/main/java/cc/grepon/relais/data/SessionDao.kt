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
 * Data access for the session-memory store (Feature #5). All queries are parameterized (no string
 * concatenation) so a hostile session key or content can never alter the SQL (security: SQL
 * injection). Suspend functions — called from coroutine scopes off the response critical path.
 */
@Dao
interface SessionDao {
  @Insert suspend fun insert(turn: SessionTurn): Long

  /** Oldest-first turns for one session, capped to [limit] most-recent rows (then re-ordered asc). */
  @Query(
    "SELECT * FROM session_turns WHERE sessionKey = :sessionKey " +
      "ORDER BY createdAt DESC, id DESC LIMIT :limit"
  )
  suspend fun recentDesc(sessionKey: String, limit: Int): List<SessionTurn>

  /** All turns for one session, oldest first (unbounded — callers should prefer [recentDesc]). */
  @Query("SELECT * FROM session_turns WHERE sessionKey = :sessionKey ORDER BY createdAt ASC, id ASC")
  suspend fun turnsFor(sessionKey: String): List<SessionTurn>

  @Query("DELETE FROM session_turns WHERE sessionKey = :sessionKey")
  suspend fun deleteFor(sessionKey: String)

  /** Prunes every turn older than [cutoffMs] (TTL). */
  @Query("DELETE FROM session_turns WHERE createdAt < :cutoffMs")
  suspend fun deleteOlderThan(cutoffMs: Long)

  @Query("SELECT COUNT(*) FROM session_turns WHERE sessionKey = :sessionKey")
  suspend fun countFor(sessionKey: String): Int

  @Query("SELECT COUNT(*) FROM session_turns")
  suspend fun countAll(): Int

  /** Distinct sessions currently stored (gauge: active sessions). */
  @Query("SELECT COUNT(DISTINCT sessionKey) FROM session_turns")
  suspend fun sessionCount(): Int

  /**
   * Every distinct session key currently stored. The periodic prune iterates these to retroactively
   * trim each over-cap session (e.g. after the operator lowers `sessionMaxTurns`) — a per-session cap
   * is only enforced on record() at insert time, so a lowered cap needs this catch-up pass.
   */
  @Query("SELECT DISTINCT sessionKey FROM session_turns")
  suspend fun distinctSessionKeys(): List<String>

  /**
   * Global row-cap evictor: keeps only the [globalCap] most-recent turns across ALL sessions and
   * deletes the rest (oldest first). Bounds total DB growth when a client varies its session key and
   * creates many distinct sessions faster than TTL reclaims them. The subquery selects the ids to
   * KEEP (newest [globalCap] by createdAt then id); everything else is removed. Parameterized.
   */
  @Query(
    "DELETE FROM session_turns WHERE id NOT IN (" +
      "SELECT id FROM session_turns ORDER BY createdAt DESC, id DESC LIMIT :globalCap)"
  )
  suspend fun trimToGlobalCap(globalCap: Int)

  /**
   * Per-session row-cap trim: deletes the oldest turns of [sessionKey] beyond the [keep] most recent.
   * The subquery selects the ids to KEEP (newest [keep]); everything else for that session is removed.
   * Parameterized — no string concatenation.
   */
  @Query(
    "DELETE FROM session_turns WHERE sessionKey = :sessionKey AND id NOT IN (" +
      "SELECT id FROM session_turns WHERE sessionKey = :sessionKey " +
      "ORDER BY createdAt DESC, id DESC LIMIT :keep)"
  )
  suspend fun trimToCap(sessionKey: String, keep: Int)
}
