package com.clarity.life.backup

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.clarity.life.data.ClarityDatabase
import com.clarity.life.data.entity.ConversationRow
import com.clarity.life.data.entity.GoalRow
import com.clarity.life.data.entity.MessageRow
import com.clarity.life.data.entity.NoteRow
import com.clarity.life.data.entity.SettingRow
import com.clarity.life.security.SecretStore
import com.clarity.life.settings.SettingsRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stage E's "done when": a backup made on one phone restores on another. Two separate databases stand in for
 * the two phones: everything written on the first comes back on the second, word for word, and a wrong
 * passphrase changes nothing.
 */
@RunWith(AndroidJUnit4::class)
class RestoreTest {

    private lateinit var first: ClarityDatabase
    private lateinit var second: ClarityDatabase
    private val now = 1_791_600_000_000L
    private val pass = "paper passphrase"

    private fun database(): ClarityDatabase {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return Room.inMemoryDatabaseBuilder(context, ClarityDatabase::class.java)
            .addCallback(
                object : RoomDatabase.Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        db.execSQL("PRAGMA foreign_keys = ON")
                    }
                },
            )
            .build()
    }

    private fun backups(db: ClarityDatabase): BackupRepository {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val secrets = SecretStore(context)
        val embedder = com.clarity.life.memory.Embedder(context)
        return BackupRepository(
            context = context,
            db = db,
            secrets = secrets,
            settings = SettingsRepository(db.settings(), secrets),
            search = com.clarity.life.memory.SearchIndex(db, embedder),
            indexer = com.clarity.life.memory.EmbeddingIndexer(context, db, embedder),
            reminders = com.clarity.life.promise.Reminders(context, db),
        )
    }

    @Before
    fun open() {
        first = database()
        second = database()
    }

    @After
    fun close() {
        first.close()
        second.close()
    }

    private suspend fun fill() {
        val conv = first.conversations().insert(ConversationRow(startedAt = now, title = "Driving", summary = "He drove."))
        first.messages().insert(MessageRow(conversationId = conv, role = "user", content = "I drove to the market — alone ✓", createdAt = now))
        first.messages().insert(MessageRow(conversationId = conv, role = "coach", content = "That is new.", createdAt = now + 1, model = "deepseek-flash, thinking low"))
        first.planner().insertGoal(GoalRow(title = "Clear the chemistry lectures", deadline = "2026-11-20", createdAt = now))
        first.memory().insertNote(NoteRow(kind = "what_helps", text = "Quiet hours help", firstSeen = "2026-10-01", lastSeen = "2026-10-08", createdAt = now))
        first.settings().put(SettingRow("user_name", "Alex"))
    }

    @Test
    fun aBackupFromOnePhoneRestoresOnAnother() = runBlocking {
        fill()
        // Something already on the second phone is replaced, not merged.
        second.planner().insertGoal(GoalRow(title = "Old goal on the new phone", createdAt = now))
        val file = backups(first).sealNow(pass.toCharArray(), now, iterations = 100_000)

        val outcome = backups(second).restoreBytes(file, pass.toCharArray(), now)
        assertTrue("restore said $outcome", outcome is BackupRepository.Outcome.Restored)
        assertEquals(now, (outcome as BackupRepository.Outcome.Restored).createdAt)

        val msgs = second.messages().ofConversation(second.conversations().searchByTitle("", 5).single().id)
        assertEquals(listOf("I drove to the market — alone ✓", "That is new."), msgs.map { it.content })
        assertEquals("deepseek-flash, thinking low", msgs[1].model)
        val goals = second.query("SELECT title, deadline FROM goal", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) }
        }
        assertEquals(listOf("Clear the chemistry lectures" to "2026-11-20"), goals)
        assertEquals(listOf("Quiet hours help"), second.memory().activeNotes().map { it.text })
        assertEquals("Alex", second.settings().get("user_name"))
        // The search index is rebuilt from the restored rows.
        assertTrue(second.query("SELECT COUNT(*) FROM search_doc", null).use { it.moveToFirst(); it.getInt(0) } > 0)
    }

    @Test
    fun aWrongPassphraseChangesNothing() = runBlocking {
        fill()
        second.planner().insertGoal(GoalRow(title = "Stays", createdAt = now))
        val file = backups(first).sealNow(pass.toCharArray(), now, iterations = 100_000)
        val outcome = backups(second).restoreBytes(file, "not the passphrase".toCharArray(), now)
        assertTrue(outcome is BackupRepository.Outcome.Failed)
        val titles = second.query("SELECT title FROM goal", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        assertEquals(listOf("Stays"), titles)
    }

    @Test
    fun aBackupMadeBeforeStreamingStillRestores() = runBlocking {
        fill()
        // Format 1: the whole database as one sealed piece, as every backup before this version was written.
        val old = com.clarity.life.core.BackupFormat.seal(
            backups(first).dump(), pass.toCharArray(), first.openHelper.readableDatabase.version, now, 100_000,
        )
        val outcome = backups(second).restoreBytes(old, pass.toCharArray(), now)
        assertTrue("restore said $outcome", outcome is BackupRepository.Outcome.Restored)
        assertEquals(listOf("Quiet hours help"), second.memory().activeNotes().map { it.text })
    }

    @Test
    fun aBackupCutShortChangesNothing() = runBlocking {
        fill()
        // Many rows, so most are already inserted when the cut at the very end is found: the transaction must
        // take them all back.
        val conv = first.conversations().searchByTitle("", 1).single().id
        for (i in 0 until 3000) {
            first.messages().insert(
                MessageRow(conversationId = conv, role = "user", content = "message $i " + "x".repeat(i % 97), createdAt = now + 10 + i),
            )
        }
        second.planner().insertGoal(GoalRow(title = "Stays", createdAt = now))
        val file = backups(first).sealNow(pass.toCharArray(), now, iterations = 100_000)
        val cut = file.copyOfRange(0, file.size - 40)
        val outcome = backups(second).restoreBytes(cut, pass.toCharArray(), now)
        assertTrue("restore said $outcome", outcome is BackupRepository.Outcome.Failed)
        val titles = second.query("SELECT title FROM goal", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        assertEquals(listOf("Stays"), titles)
        assertEquals(0, second.query("SELECT COUNT(*) FROM message", null).use { it.moveToFirst(); it.getInt(0) })
    }
}
