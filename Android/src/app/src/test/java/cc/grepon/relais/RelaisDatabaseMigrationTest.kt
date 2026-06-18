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

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import cc.grepon.relais.data.RelaisDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Real v1 -> v2 migration validation for the shared on-device DB (Feature #5). Guards against
 * bricking devices on app upgrade: it physically builds a v1 database, force-runs
 * [RelaisDatabase.MIGRATION_1_2], and validates the migrated schema, so any column/type/index drift
 * between the migration SQL and the [SessionTurn] entity is caught here rather than on an upgrading
 * device.
 *
 * Approach (reviewer's SECOND option — chosen after a real attempt at the first): the canonical
 * `MigrationTestHelper(Instrumentation, ...)` path fails under Robolectric/JVM in THIS project,
 * throwing FileNotFoundException because the helper resolves the exported schema JSONs from the
 * *instrumentation* asset path, and the unit-test (Robolectric) asset wiring does not surface the
 * exported schema JSONs there. Rather than fake the validation, this test opens a real v1 database via
 * a [SupportSQLiteOpenHelper] (the v1 `schema_meta` table + Room's `room_master_table` seeded to the
 * v1 identity hash), closes it, then opens it through `Room.databaseBuilder(...).addMigrations(
 * MIGRATION_1_2)`. That FORCES the migration to run on open AND makes Room VALIDATE the migrated
 * schema's identity hash against the compiled v2 schema (the same hash committed in `2.json`); a
 * mismatch (column/type/index drift) throws on open. PRAGMA assertions then confirm the
 * `session_turns` table and its `(sessionKey, createdAt)` index exist with the expected columns.
 */
@RunWith(RobolectricTestRunner::class)
class RelaisDatabaseMigrationTest {

  private companion object {
    const val TEST_DB = "relais-migration-test.db"

    // v1 of the schema (see app/schemas/.../1.json): the single `schema_meta` table, plus the Room
    // master table seeded to the v1 identityHash so Room recognizes the file as a valid v1 DB.
    const val V1_IDENTITY_HASH = "cde3c5e04f91bfcb5c5b7ae5ba01e80e"
    const val CREATE_SCHEMA_META =
      "CREATE TABLE IF NOT EXISTS `schema_meta` (" +
        "`id` INTEGER NOT NULL, `createdAtMs` INTEGER NOT NULL, `note` TEXT NOT NULL, " +
        "PRIMARY KEY(`id`))"
    const val CREATE_ROOM_MASTER =
      "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)"
    const val SEED_ROOM_MASTER =
      "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '$V1_IDENTITY_HASH')"
  }

  private val context: Context
    get() = RuntimeEnvironment.getApplication()

  @After
  fun tearDown() {
    context.deleteDatabase(TEST_DB)
  }

  /** Writes a real on-disk v1 database (pre-#5: no `session_turns` table) and closes it. */
  private fun createV1Database() {
    val configuration =
      SupportSQLiteOpenHelper.Configuration.builder(context)
        .name(TEST_DB)
        .callback(
          object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: SupportSQLiteDatabase) {
              db.execSQL(CREATE_SCHEMA_META)
              db.execSQL(CREATE_ROOM_MASTER)
              db.execSQL(SEED_ROOM_MASTER)
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
              // No-op: this helper only ever materializes the v1 schema; the v1->v2 upgrade is
              // exercised by Room below, not here.
            }
          }
        )
        .build()
    val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
    // Touching the writable DB triggers onCreate, then close to flush the v1 file to disk.
    helper.writableDatabase.close()
    helper.close()
  }

  @Test
  fun `v1 to v2 migration runs and validates against the compiled v2 schema`() {
    createV1Database()

    // Opening a v1 file through Room runs the full migration chain to the compiled version (now v3),
    // so BOTH migrations are wired; Room VALIDATES the final schema's identity hash. This test focuses
    // on the v1->v2 step: any drift between MIGRATION_1_2's SQL and the SessionTurn entity throws here,
    // and the assertions below confirm `session_turns` exists + survives the later v2->v3 migration.
    val db =
      Room.databaseBuilder(context, RelaisDatabase::class.java, TEST_DB)
        .addMigrations(RelaisDatabase.MIGRATION_1_2, RelaisDatabase.MIGRATION_2_3)
        .allowMainThreadQueries()
        .build()

    // Force Room to actually open the file (lazy until first access) so the migration + validation run.
    val supportDb = db.openHelper.writableDatabase

    // Confirm the v2 table physically exists with exactly the expected columns, in order.
    val cols = mutableListOf<String>()
    supportDb.query("PRAGMA table_info(`session_turns`)").use { c ->
      val nameCol = c.getColumnIndexOrThrow("name")
      while (c.moveToNext()) cols.add(c.getString(nameCol))
    }
    assertEquals(listOf("id", "sessionKey", "role", "content", "createdAt"), cols)

    // Confirm the (sessionKey, createdAt) index Room expects exists after migration.
    val indexNames = mutableListOf<String>()
    supportDb.query("PRAGMA index_list(`session_turns`)").use { c ->
      val nameCol = c.getColumnIndexOrThrow("name")
      while (c.moveToNext()) indexNames.add(c.getString(nameCol))
    }
    assertTrue(
      "expected the (sessionKey, createdAt) index to exist after migration",
      indexNames.contains("index_session_turns_sessionKey_createdAt"),
    )

    db.close()
  }

  @Test
  fun `v2 to v3 migration runs and validates against the compiled v3 schema`() {
    // Start from a real v1 file and let Room run the FULL chain (1->2->3); opening through Room with
    // both migrations forces them to EXECUTE and VALIDATES the final schema's identity hash against the
    // compiled v3 schema (== 3.json). Any drift between MIGRATION_2_3's SQL and the RagDocument/RagChunk
    // entities throws here on open (Feature #4 — guards against bricking an upgrading device).
    createV1Database()

    val db =
      Room.databaseBuilder(context, RelaisDatabase::class.java, TEST_DB)
        .addMigrations(RelaisDatabase.MIGRATION_1_2, RelaisDatabase.MIGRATION_2_3)
        .allowMainThreadQueries()
        .build()
    val supportDb = db.openHelper.writableDatabase

    fun columns(table: String): List<String> {
      val cols = mutableListOf<String>()
      supportDb.query("PRAGMA table_info(`$table`)").use { c ->
        val nameCol = c.getColumnIndexOrThrow("name")
        while (c.moveToNext()) cols.add(c.getString(nameCol))
      }
      return cols
    }

    fun indices(table: String): List<String> {
      val names = mutableListOf<String>()
      supportDb.query("PRAGMA index_list(`$table`)").use { c ->
        val nameCol = c.getColumnIndexOrThrow("name")
        while (c.moveToNext()) names.add(c.getString(nameCol))
      }
      return names
    }

    assertEquals(listOf("id", "title", "createdAt"), columns("rag_documents"))
    assertEquals(
      listOf("id", "documentId", "chunkIndex", "text", "embedding", "dim", "createdAt"),
      columns("rag_chunks"),
    )
    assertTrue(indices("rag_documents").contains("index_rag_documents_createdAt"))
    assertTrue(indices("rag_chunks").contains("index_rag_chunks_documentId"))

    db.close()
  }
}
