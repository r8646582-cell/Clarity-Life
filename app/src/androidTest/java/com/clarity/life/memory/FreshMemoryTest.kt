package com.clarity.life.memory

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.clarity.life.data.ClarityDatabase
import com.clarity.life.data.entity.ConversationRow
import com.clarity.life.data.entity.NoteRow
import com.clarity.life.data.entity.PersonRow
import com.clarity.life.data.entity.ProfileEntryRow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What he changes in What I know reaches a conversation that is already open: its frozen prefix (which holds
 * PROFILE, PEOPLE and NOTES) is dropped, so the next message builds it again without the old words (rule 5).
 */
@RunWith(AndroidJUnit4::class)
class FreshMemoryTest {

    private lateinit var db: ClarityDatabase
    private lateinit var memory: MemoryRepository
    private val now = 1_791_648_000_000L
    private var conv = 0L

    @Before
    fun open() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, ClarityDatabase::class.java)
            .addCallback(
                object : RoomDatabase.Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        db.execSQL("PRAGMA foreign_keys = ON")
                    }
                },
            )
            .build()
        memory = MemoryRepository(db, SearchIndex(db, Embedder(context)))
        conv = db.conversations().insert(ConversationRow(startedAt = now))
        frozen()
    }

    @After
    fun close() = db.close()

    private suspend fun frozen() = db.conversations().setPrefix(conv, "{\"system\":\"old memory\"}", "2026-10-14")

    private suspend fun prefix() = db.conversations().get(conv)!!.prefixJson

    private suspend fun note() = db.memory().insertNote(
        NoteRow(kind = "pattern", text = "You study late", confidence = "guess", firstSeen = "2026-10-01", lastSeen = "2026-10-01", createdAt = now),
    )

    @Test
    fun deletingANoteDropsTheFrozenPrefix() = runBlocking {
        val id = note()
        assertNotNull(prefix())
        memory.deleteNote(id)
        assertNull(prefix())
    }

    @Test
    fun editingOrConfirmingANoteDropsTheFrozenPrefix() = runBlocking {
        val id = note()
        memory.editNote(id, "You study in the morning")
        assertNull(prefix())
        frozen()
        memory.confirmNote(id)
        assertNull(prefix())
    }

    @Test
    fun profileAndPeopleChangesDropTheFrozenPrefix() = runBlocking {
        db.memory().putProfile(ProfileEntryRow(key = "city", value = "Lahore", updatedAt = now))
        memory.editProfile("city", "Karachi", now)
        assertNull(prefix())
        frozen()
        memory.deleteProfile("city")
        assertNull(prefix())
        frozen()
        val p = db.memory().insertPerson(PersonRow(name = "Ali", updatedAt = now))
        memory.editPerson(p, "brother", "", now)
        assertNull(prefix())
        frozen()
        memory.deletePerson(p)
        assertNull(prefix())
    }
}
