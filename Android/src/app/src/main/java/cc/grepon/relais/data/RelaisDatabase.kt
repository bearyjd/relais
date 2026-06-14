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

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The shared on-device SQLite database. A static singleton ([get]) accessed with a [Context], matching
 * the node layer's idiom (RelaisConfig/RelaisEngine are objects), rather than a Hilt provider — so the
 * HTTP server and workers reach it without Hilt.
 *
 * Consumers extend this: #4 adds `ragDocument`/`ragChunk` (+ DAO), #5 adds `session_turns` (+ DAO),
 * #14 adds `batchJobs`. Each addition bumps [version] and appends a [Migration] to [MIGRATIONS].
 * There is NO destructive-migration fallback — on-device data (RAG corpus, sessions, queued jobs)
 * must survive upgrades. The schema is exported under `app/schemas/` for diffable, migration-testable
 * changes.
 */
@Database(entities = [SchemaMeta::class, SessionTurn::class], version = 2, exportSchema = true)
abstract class RelaisDatabase : RoomDatabase() {

  abstract fun schemaMetaDao(): SchemaMetaDao

  abstract fun sessionDao(): SessionDao

  companion object {
    private const val DB_NAME = "relais.db"

    @Volatile private var instance: RelaisDatabase? = null

    /**
     * v1 -> v2 (Feature #5): adds the `session_turns` table + its `(sessionKey, createdAt)` index.
     * The CREATE TABLE / CREATE INDEX statements mirror [SessionTurn] exactly (column order,
     * affinities, NOT NULL, autoincrement PK, Room's generated index name) — Room validates the
     * schema identity on open and throws IllegalStateException on any mismatch. Additive only; no
     * existing data is touched.
     *
     * Exposed (`@VisibleForTesting`) so `RelaisDatabaseMigrationTest` can force-run and validate this
     * exact migration against the exported `2.json` identity hash — see that test.
     */
    @VisibleForTesting
    internal val MIGRATION_1_2 =
      object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL(
            "CREATE TABLE IF NOT EXISTS `session_turns` (" +
              "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
              "`sessionKey` TEXT NOT NULL, " +
              "`role` TEXT NOT NULL, " +
              "`content` TEXT NOT NULL, " +
              "`createdAt` INTEGER NOT NULL)"
          )
          db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_session_turns_sessionKey_createdAt` " +
              "ON `session_turns` (`sessionKey`, `createdAt`)"
          )
        }
      }

    /** Migrations appended by consumers when they add tables + bump [version]. */
    val MIGRATIONS: List<Migration> = listOf(MIGRATION_1_2)

    /** Process-wide singleton (single process — see backlog §3). */
    fun get(context: Context): RelaisDatabase =
      instance
        ?: synchronized(this) {
          instance
            ?: Room.databaseBuilder(
                context.applicationContext,
                RelaisDatabase::class.java,
                DB_NAME,
              )
              .addMigrations(*MIGRATIONS.toTypedArray())
              .build()
              .also { instance = it }
        }

    @VisibleForTesting
    fun resetForTest() {
      instance?.close()
      instance = null
    }
  }
}
