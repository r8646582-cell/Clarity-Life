package com.clarity.life.promise

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.clarity.life.data.ClarityDatabase
import com.clarity.life.data.entity.PromiseRow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An alarm waits only for an open promise that asked for a reminder and is still ahead, and goes away
 * as soon as the promise is answered. syncAll (what runs after a phone restart) sets them all again.
 */
@RunWith(AndroidJUnit4::class)
class ReminderTest {

    private lateinit var db: ClarityDatabase
    private lateinit var reminders: Reminders
    private val now = System.currentTimeMillis()

    @Before
    fun open() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, ClarityDatabase::class.java).build()
        reminders = Reminders(context, db)
    }

    @After
    fun close() = db.close()

    private suspend fun promise(dueIn: Long, remind: Int, status: String = "open"): Long =
        db.promises().insert(
            PromiseRow(
                text = "Walk 20 minutes", dueAt = now + dueIn, dueText = "soon", remind = remind,
                status = status, createdAt = now,
            ),
        )

    @Test
    fun anAlarmWaitsOnlyForAnOpenPromiseWithAReminder() = runBlocking {
        val withReminder = promise(3_600_000, 1)
        val without = promise(3_600_000, 0)
        val past = promise(-3_600_000, 1)
        try {
            reminders.sync(withReminder, now)
            reminders.sync(without, now)
            reminders.sync(past, now)
            assertTrue(reminders.isSet(withReminder))
            assertFalse(reminders.isSet(without))
            assertFalse(reminders.isSet(past))

            // He marks it done: the alarm goes.
            db.promises().resolve(withReminder, "kept", null, now)
            reminders.sync(withReminder, now)
            assertFalse(reminders.isSet(withReminder))

            // After a restart only the open, ahead, reminded ones come back.
            val again = promise(7_200_000, 1)
            assertEquals(1, reminders.syncAll(now))
            assertTrue(reminders.isSet(again))
            assertFalse(reminders.isSet(withReminder))
            reminders.cancel(again)
            assertFalse(reminders.isSet(again))
        } finally {
            for (id in listOf(withReminder, without, past)) reminders.cancel(id)
        }
    }

    @Test
    fun theSelfCheckSetsALostAlarmAgain() = runBlocking {
        val a = promise(3_600_000, 1)
        val b = promise(7_200_000, 1)
        try {
            assertEquals(2, reminders.syncAll(now))
            assertEquals(2 to 0, reminders.status(now))
            assertEquals("nothing to heal", 0, reminders.heal(now))
            // The phone dropped one alarm (a force stop, a cleaner app): the check finds it and sets it again.
            reminders.cancel(b)
            assertEquals(2 to 1, reminders.status(now))
            assertEquals(1, reminders.heal(now))
            assertTrue(reminders.isSet(b))
            assertEquals(2 to 0, reminders.status(now))
            // The count is logged, never the promise's words.
            val details = db.query("SELECT code, detail FROM error_log WHERE area = 'reminder'", null).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) }
            }
            assertEquals(listOf("alarm_missing" to "n=1"), details)
        } finally {
            reminders.cancel(a)
            reminders.cancel(b)
        }
    }
}
