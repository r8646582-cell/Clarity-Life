package com.clarity.life.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.clarity.life.data.entity.ConversationRow
import com.clarity.life.data.entity.JobRow
import com.clarity.life.data.entity.MessageRow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The process was killed in the middle of a reply and in the middle of a reflection (Max plan, Phase 1, step 6):
 * on the next start the reply is interrupted, with its Try again button, the reflection job goes back to pending,
 * and nothing that was saved is lost. A job another process may still be running is left alone.
 */
@RunWith(AndroidJUnit4::class)
class StartupRepairTest {

    private lateinit var db: ClarityDatabase
    private val now = System.currentTimeMillis()

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, ClarityDatabase::class.java).build()
    }

    @After
    fun close() = db.close()

    @Test
    fun aKilledReplyAndAKilledReflectionComeBackSafe() = runBlocking {
        val conv = db.conversations().insert(ConversationRow(startedAt = now, title = "Late"))
        val asked = db.messages().insert(MessageRow(conversationId = conv, role = "user", content = "Why do I stall?", createdAt = now))
        val half = db.messages().insert(MessageRow(conversationId = conv, role = "coach", content = "Because the first st", createdAt = now + 1, status = "streaming"))
        db.jobs().insertIfNew(JobRow(key = "reflection:$conv:old", type = "reflection", status = "running", attempts = 1, createdAt = now - 3_600_000, startedAt = now - 3_600_000))
        db.jobs().insertIfNew(JobRow(key = "reflection:$conv:live", type = "reflection", status = "running", attempts = 1, createdAt = now, startedAt = now - 60_000))

        val result = StartupRepair(db.repair(), db.diagnostics()).run(now)

        assertEquals(StartupRepair.Result(repairedMessages = 1, requeuedJobs = 1), result)
        assertEquals("interrupted", db.messages().get(half)?.status)
        assertEquals("the words so far are kept", "Because the first st", db.messages().get(half)?.content)
        assertEquals("Why do I stall?", db.messages().get(asked)?.content)
        assertEquals("pending", db.jobs().byKey("reflection:$conv:old")?.status)
        assertEquals("a job that may be running now is left alone", "running", db.jobs().byKey("reflection:$conv:live")?.status)
        // Counts only in the log.
        assertEquals("m=1 j=1", db.diagnostics().recent(1).single().detail)
        // Running it again finds nothing.
        assertEquals(StartupRepair.Result(0, 0), StartupRepair(db.repair(), db.diagnostics()).run(now))
    }
}
