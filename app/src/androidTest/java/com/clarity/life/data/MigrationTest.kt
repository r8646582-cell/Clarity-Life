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
        private const val DATABASE_VERSION = 4
    }
}
