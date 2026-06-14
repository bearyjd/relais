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

/**
 * The shared on-device SQLite database. A static singleton ([get]) accessed with a [Context], matching
 * the node layer's idiom (RelaisConfig/RelaisEngine are objects), rather than a Hilt provider — so the
 * HTTP server and workers reach it without Hilt.
 *
 * Consumers extend this: #4 adds `ragDocument`/`ragChunk` (+ DAO), #5 adds `sessionTurn`, #14 adds
 * `batchJobs`. Each addition bumps [version] and appends a [Migration] to [MIGRATIONS]. There is NO
 * destructive-migration fallback — on-device data (RAG corpus, sessions, queued jobs) must survive
 * upgrades. The schema is exported under `app/schemas/` for diffable, migration-testable changes.
 */
@Database(entities = [SchemaMeta::class], version = 1, exportSchema = true)
abstract class RelaisDatabase : RoomDatabase() {

  abstract fun schemaMetaDao(): SchemaMetaDao

  companion object {
    private const val DB_NAME = "relais.db"

    @Volatile private var instance: RelaisDatabase? = null

    /** Migrations appended by consumers when they add tables + bump [version]. Empty at v1. */
    val MIGRATIONS: Array<Migration> = arrayOf()

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
              .addMigrations(*MIGRATIONS)
              .build()
              .also { instance = it }
        }

    @VisibleForTesting
    fun resetForTest() {
      instance = null
    }
  }
}
