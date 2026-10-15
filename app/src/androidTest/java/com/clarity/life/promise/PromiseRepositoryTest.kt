package com.clarity.life.promise

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.clarity.life.data.ClarityDatabase
import com.clarity.life.data.entity.PromiseRow
import com.clarity.life.memory.SearchIndex
import com.clarity.life.security.SecretStore
import com.clarity.life.settings.SettingsRepository
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Done, Not done, Let go, Moved and Undo: each a move the promise state machine allows, and nothing else. */
@RunWith(AndroidJUnit4::class)
class PromiseRepositoryTest {

    private lateinit var db: ClarityDatabase
    private lateinit var promises: PromiseRepository
    private lateinit var reminders: Reminders
    private val now = System.currentTimeMillis()

    @Before
    fun open() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, ClarityDatabase::class.java).build()
        reminders = Reminders(context, db)
        promises = PromiseRepository(
            db,
            SettingsRepository(db.settings(), SecretStore(context)),
            reminders,
            SearchIndex(db, com.clarity.life.memory.TestEmbedder.shared),
        )
    }

    @After
    fun close() = db.close()

    private suspend fun promise(): Long = db.promises().insert(
        PromiseRow(text = "Call Mom", dueAt = now + 3_600_000, dueText = "soon", remind = 1, createdAt = now),
    )

    @Test
    fun answersFollowTheStateMachine() = runBlocking {
        val id = promise()
        reminders.sync(id, now)
        assertTrue(reminders.isSet(id))

        assertTrue(promises.done(id, now))
        assertEquals("kept", db.promises().get(id)!!.status)
        assertFalse("an answered promise has no alarm", reminders.isSet(id))
        assertFalse("answered twice", promises.done(id, now))

        assertTrue(promises.undo(id, now))
        assertEquals("open", db.promises().get(id)!!.status)
        assertTrue("the alarm is back", reminders.isSet(id))

        promises.notDone(id, "tired", now)
        val row = db.promises().get(id)!!
        assertEquals("not_done", row.status)
        assertEquals("tired", row.reason)
        reminders.cancel(id)
    }

    @Test
    fun movedKeepsTheOldOneAndStartsItAgain() = runBlocking {
        val id = promise()
        assertNull("never into the past", promises.move(id, Instant.ofEpochMilli(now - 1), now))
        val to = Instant.ofEpochMilli(now + 86_400_000)
        val newId = promises.move(id, to, now)!!
        assertEquals("moved", db.promises().get(id)!!.status)
        val fresh = db.promises().get(newId)!!
        assertEquals("open", fresh.status)
        assertEquals("Call Mom", fresh.text)
        assertEquals(to.toEpochMilli(), fresh.dueAt)
        assertEquals(1, fresh.remind)
        assertFalse("a move cannot be undone", promises.undo(id, now))
        assertTrue(reminders.isSet(newId))
        assertFalse(reminders.isSet(id))
        reminders.cancel(newId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aReasonIsOnlyOneOfTheChips() = runBlocking {
        promises.notDone(promise(), "made up", now)
        Unit
    }
}
