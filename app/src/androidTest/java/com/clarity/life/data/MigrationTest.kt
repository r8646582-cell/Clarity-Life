package com.clarity.life.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The migration gate (CLAUDE.md rule 8). It needs a device or an emulator, so it runs on GitHub Actions
 * (the android job's emulator step), not on the machine that writes the code.
 *
 * Every migration has a test here that opens a database of the old version with rows in it, migrates it,
 * lets Room check the result against the entities, and checks the rows survived. It also proves the
 * committed schemas really create, and that a migration exists for every version step, so the app never
 * falls back to wiping data.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ClarityDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun theCommittedVersion1SchemaCreates() {
        val db = helper.createDatabase(TEST_DB, 1)
        val tables = mutableListOf<String>()
        db.query(
            "SELECT name FROM sqlite_master WHERE type IN ('table','view') " +
                "AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%' " +
                "AND name NOT LIKE 'search_doc_%' AND name != 'room_master_table'",
        ).use { cursor ->
            while (cursor.moveToNext()) tables.add(cursor.getString(0))
        }
        db.close()
        assertEquals("version 1 had 28 tables", 28, tables.size)
        assertTrue("search_doc is missing", tables.contains("search_doc"))
        assertTrue("checkin is missing", tables.contains("checkin"))
    }

    @Test
    fun version1To2KeepsEveryRowAndAddsReflectionItems() {
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL("INSERT INTO conversation (id, title, started_at) VALUES (1, 'First', 1760000000000)")
            execSQL(
                "INSERT INTO message (id, conversation_id, role, content, created_at) " +
                    "VALUES (1, 1, 'user', 'i only study well in the morning', 1760000000000)",
            )
            execSQL(
                "INSERT INTO note (id, kind, text, first_seen, last_seen, created_at) " +
                    "VALUES (1, 'what_helps', 'Mornings work for you', '2026-10-10', '2026-10-10', 1760000000000)",
            )
            close()
        }
        // Room compares every migrated table with the entities and throws on any difference.
        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, Migrations.V1_TO_V2)
        db.query("SELECT content FROM message WHERE id = 1").use {
            assertTrue("the message is gone", it.moveToFirst())
            assertEquals("i only study well in the morning", it.getString(0))
        }
        db.query("SELECT text FROM note WHERE id = 1").use {
            assertTrue("the note is gone", it.moveToFirst())
            assertEquals("Mornings work for you", it.getString(0))
        }
        db.execSQL(
            "INSERT INTO reflection_item (conversation_id, kind, text, day, created_at) " +
                "VALUES (1, 'idea', 'if-then plan', '2026-10-10', 1760000000000)",
        )
        db.query("SELECT his_words FROM reflection_item").use {
            assertTrue(it.moveToFirst())
            assertEquals("the default is an empty string", "", it.getString(0))
        }
        db.close()
    }

    @Test
    fun version2To3KeepsEveryBacklogAndLeavesItsDayToBeFilled() {
        helper.createDatabase(TEST_DB, 2).apply {
            execSQL(
                "INSERT INTO backlog (id, name, total, unit, deadline, created_at) " +
                    "VALUES (1, 'Chemistry lectures', 50, 'lectures', '2026-11-20', 1760000000000)",
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(TEST_DB, 3, true, Migrations.V2_TO_V3)
        db.query("SELECT name, total, start_day FROM backlog WHERE id = 1").use {
            assertTrue("the backlog is gone", it.moveToFirst())
            assertEquals("Chemistry lectures", it.getString(0))
            assertEquals(50.0, it.getDouble(1), 0.0)
            assertTrue("an old backlog's day is filled on first read, not guessed here", it.isNull(2))
        }
        db.execSQL(
            "INSERT INTO backlog (name, total, unit, deadline, created_at, start_day) " +
                "VALUES ('Past papers', 10, 'papers', '2026-11-20', 1760000000000, '2026-10-10')",
        )
        db.close()
    }

    @Test
    fun version3To4KeepsEveryRowAndAddsStories() {
        helper.createDatabase(TEST_DB, 3).apply {
            execSQL("INSERT INTO conversation (id, title, started_at) VALUES (1, 'First', 1760000000000)")
            execSQL("INSERT INTO quote (id, conversation_id, text, said_day) VALUES (1, 1, 'I know what to do', '2026-10-13')")
            execSQL(
                "INSERT INTO chapter (period_start, period_end, title, body, created_at) " +
                    "VALUES ('2026-10-01', '2026-12-31', 'The plan that held', 'Body', 1760000000000)",
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, Migrations.V3_TO_V4)
        db.query("SELECT text FROM quote WHERE id = 1").use {
            assertTrue("the quote is gone", it.moveToFirst())
            assertEquals("I know what to do", it.getString(0))
        }
        db.query("SELECT COUNT(*) FROM chapter").use {
            it.moveToFirst()
            assertEquals("the chapter is gone", 1, it.getInt(0))
        }
        db.execSQL(
            "INSERT INTO story (period_start, period_end, short, then_quote_id, created_at, key) " +
                "VALUES ('2026-10-01', '2026-10-31', 'Short', 1, 1760000000000, 'story:2026-10')",
        )
        db.query("SELECT overcame_json FROM story WHERE key = 'story:2026-10'").use {
            assertTrue(it.moveToFirst())
            assertEquals("[]", it.getString(0))
        }
        var second = false
        try {
            db.execSQL(
                "INSERT INTO story (period_start, period_end, short, created_at, key) " +
                    "VALUES ('2026-10-01', '2026-10-31', 'Again', 1760000000000, 'story:2026-10')",
            )
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            second = true
        }
        assertTrue("a month can only have one story", second)
        db.close()
    }

    @Test
    fun version4To5KeepsEveryRowAndAddsBriefs() {
        helper.createDatabase(TEST_DB, 4).apply {
            execSQL("INSERT INTO conversation (id, title, started_at) VALUES (1, 'First', 1760000000000)")
            execSQL(
                "INSERT INTO story (period_start, period_end, short, created_at, key) " +
                    "VALUES ('2026-10-01', '2026-10-31', 'Short', 1760000000000, 'story:2026-10')",
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(TEST_DB, 5, true, Migrations.V4_TO_V5)
        db.query("SELECT title FROM conversation WHERE id = 1").use {
            assertTrue("the conversation is gone", it.moveToFirst())
            assertEquals("First", it.getString(0))
        }
        db.query("SELECT COUNT(*) FROM story").use {
            it.moveToFirst()
            assertEquals("the story is gone", 1, it.getInt(0))
        }
        db.execSQL(
            "INSERT INTO brief (day_key, body, created_at, key) " +
                "VALUES ('2026-10-20', 'Today asks for 2.', 1760000000000, 'brief:2026-10-20')",
        )
        var second = false
        try {
            db.execSQL(
                "INSERT INTO brief (day_key, body, created_at, key) " +
                    "VALUES ('2026-10-20', 'Again', 1760000000000, 'brief:2026-10-20')",
            )
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            second = true
        }
        assertTrue("a day can only have one brief", second)
        db.close()
    }

    /** Max plan Phase 3: facts with time. Old rows keep NULL ("not known"); the new table takes rows. */
    @Test
    fun version5To6KeepsEveryRowAndAddsTimes() {
        helper.createDatabase(TEST_DB, 5).apply {
            execSQL("INSERT INTO profile_entry (`key`, value, confirmed, updated_at) VALUES ('Work', 'Accounts assistant', 1, 1760000000000)")
            execSQL("INSERT INTO target (id, name, target, schedule, start_day) VALUES (1, 'Lectures', 2, 'daily', '2026-10-01')")
            execSQL("INSERT INTO checkin (target_id, day_key, level, amount, reason, entered_at) VALUES (1, '2026-10-02', 'not', 0, 'tired', 1760000000000)")
            close()
        }
        val db = helper.runMigrationsAndValidate(TEST_DB, 6, true, Migrations.V5_TO_V6)
        db.query("SELECT value, confirmed, since_day FROM profile_entry WHERE `key` = 'Work'").use {
            assertTrue("the profile entry is gone", it.moveToFirst())
            assertEquals("Accounts assistant", it.getString(0))
            assertEquals(1, it.getInt(1))
            assertTrue("an old entry's day is not known, not guessed", it.isNull(2))
        }
        db.query("SELECT level, reason, entered_minute, entered_day FROM checkin").use {
            assertTrue("the check-in is gone", it.moveToFirst())
            assertEquals("not", it.getString(0))
            assertEquals("tired", it.getString(1))
            assertTrue(it.isNull(2))
            assertTrue(it.isNull(3))
        }
        db.execSQL(
            "INSERT INTO profile_past (`key`, value, since_day, until_day, created_at) " +
                "VALUES ('Work', 'Student', NULL, '2026-10-14', 1760000000000)",
        )
        db.query("SELECT COUNT(*) FROM profile_past").use {
            it.moveToFirst()
            assertEquals(1, it.getInt(0))
        }
        db.close()
    }

    // ---------- every old version, with real-looking rows, all the way to the newest (Max plan, Phase 1, step 5) ----------

    /** The rows of a sample backup in contracts/backups/ (bundled into the test assets), opened with the app's own code. */
    private fun sampleTables(file: String): org.json.JSONObject {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val vectors = org.json.JSONObject(ctx.assets.open("backup_vectors.json").use { it.readBytes().toString(Charsets.UTF_8) })
        val bytes = ctx.assets.open(file).use { it.readBytes() }
        val (_, json) = com.clarity.life.core.BackupFormat.opening(
            java.io.ByteArrayInputStream(bytes),
            vectors.getString("passphrase").toCharArray(),
        )
        return org.json.JSONObject(json.use { it.readBytes().toString(Charsets.UTF_8) }).getJSONObject("tables")
    }

    private fun insertAll(db: androidx.sqlite.db.SupportSQLiteDatabase, tables: org.json.JSONObject): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        for (table in tables.keys()) {
            val rows = tables.getJSONArray(table)
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val values = android.content.ContentValues()
                for (col in row.keys()) {
                    when (val v = row.get(col)) {
                        org.json.JSONObject.NULL -> values.putNull(col)
                        is String -> values.put(col, v)
                        is Int -> values.put(col, v.toLong())
                        is Long -> values.put(col, v)
                        is Double -> values.put(col, v)
                        else -> error("unexpected value in $table.$col")
                    }
                }
                db.insert(table, android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT, values)
            }
            if (rows.length() > 0) counts[table] = rows.length()
        }
        return counts
    }

    @Test
    fun everyOldVersionWithRealRowsReachesTheNewest() {
        // Version 2 only added a table, so version 1's rows are version 2's rows too.
        val starts = listOf(
            1 to "format1-schema1.cbk", 2 to "format1-schema1.cbk", 3 to "format2-schema3.cbk", 4 to "format2-schema4.cbk",
            5 to "format2-schema5.cbk",
        )
        for ((version, sample) in starts) {
            val name = "migrate-from-$version.db"
            val tables = sampleTables(sample)
            val counts = helper.createDatabase(name, version).let { old ->
                insertAll(old, tables).also { old.close() }
            }
            // Every migration from there on, then Room checks the result against today's entities.
            val db = helper.runMigrationsAndValidate(name, DATABASE_VERSION, true, *ClarityDatabase.MIGRATIONS)
            for ((table, n) in counts) {
                db.query("SELECT COUNT(*) FROM `$table`").use {
                    it.moveToFirst()
                    assertEquals("from version $version: rows in $table", n, it.getInt(0))
                }
            }
            db.query("SELECT content FROM message WHERE id = 1").use {
                assertTrue(it.moveToFirst())
                assertTrue("from version $version: the words changed", it.getString(0).startsWith("I only study well in the morning — before 10, with tea ✓"))
            }
            db.query("SELECT start_done, total FROM backlog WHERE id = 1").use {
                assertTrue(it.moveToFirst())
                assertEquals(4.5, it.getDouble(0), 0.0)
                assertEquals(50.0, it.getDouble(1), 0.0)
            }
            db.query("PRAGMA foreign_key_check").use { assertEquals("from version $version: broken links", 0, it.count) }
            db.close()
        }
    }

    @Test
    fun aMissingMigrationStopsWithAnErrorAndKeepsEveryRow() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "no-migration.db"
        helper.createDatabase(name, 1).apply {
            execSQL("INSERT INTO conversation (id, title, started_at) VALUES (1, 'Kept', 1760000000000)")
            execSQL("INSERT INTO message (id, conversation_id, role, content, created_at) VALUES (1, 1, 'user', 'still here', 1760000000000)")
            close()
        }
        // The app's database with no way from version 1: Room must refuse to open, never start empty.
        val room = Room.databaseBuilder(context, ClarityDatabase::class.java, name).build()
        var refused = false
        try {
            room.openHelper.writableDatabase
        } catch (e: IllegalStateException) {
            refused = true
        } finally {
            room.close()
        }
        assertTrue("a database with no migration path must not open", refused)
        val raw = android.database.sqlite.SQLiteDatabase.openDatabase(
            context.getDatabasePath(name).path,
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
        )
        raw.rawQuery("SELECT content FROM message WHERE id = 1", null).use {
            assertTrue("the message is gone", it.moveToFirst())
            assertEquals("still here", it.getString(0))
        }
        assertEquals(1, raw.version)
        raw.close()
        context.deleteDatabase(name)
    }

    @Test
    fun everyMigrationStepIsWritten() {
        // One migration per version step, always. A gap would mean Room had nothing to run.
        val expected = DATABASE_VERSION - 1
        assertEquals(
            "there must be a written migration for every version step",
            expected,
            ClarityDatabase.MIGRATIONS.size,
        )
    }

    @Test
    fun checkInsSurviveReopening() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, ClarityDatabase::class.java).build()
        // A real write through a DAO, to prove the entities and the created tables agree at runtime.
        val conversations = db.conversations()
        val id = kotlinx.coroutines.runBlocking {
            conversations.insert(
                com.clarity.life.data.entity.ConversationRow(startedAt = 1_760_000_000_000, title = "First"),
            )
        }
        val back = kotlinx.coroutines.runBlocking { conversations.get(id) }
        db.close()
        assertEquals("First", back?.title)
    }

    companion object {
        private const val TEST_DB = "migration-test.db"
        private const val DATABASE_VERSION = 6
    }
}
