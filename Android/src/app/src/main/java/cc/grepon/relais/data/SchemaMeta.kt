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
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Single-row marker so the v1 [RelaisDatabase] compiles (a Room `@Database` needs ≥1 entity) and to
 * record when/by-what the DB was created. Feature entities (#4 rag_*, #5 session_turn, #14 batch_jobs)
 * are added alongside this with a version bump + a [androidx.room.migration.Migration].
 */
@Entity(tableName = "schema_meta")
data class SchemaMeta(
  @PrimaryKey val id: Int = 1,
  val createdAtMs: Long,
  val note: String = "relais",
)

@Dao
interface SchemaMetaDao {
  @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(meta: SchemaMeta)

  @Query("SELECT * FROM schema_meta WHERE id = 1") suspend fun get(): SchemaMeta?
}
