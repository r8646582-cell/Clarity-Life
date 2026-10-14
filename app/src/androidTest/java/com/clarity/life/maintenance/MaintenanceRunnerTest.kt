package com.clarity.life.maintenance

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.clarity.life.data.ClarityDatabase
import com.clarity.life.data.entity.ConversationRow
import com.clarity.life.data.entity.ErrorLogRow
import com.clarity.life.data.entity.JobRow
import com.clarity.life.data.entity.MessageRow
import com.clarity.life.data.entity.PromiseRow
import com.clarity.life.security.SecretStore
import com.clarity.life.settings.SettingsRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The nightly maintenance on a real database (docs/MAX-PLAN.md, Phase 1, steps 4 and 9): stuck states are
 * repaired, a hole in the search index is found and filled, a lost alarm is set again, old log lines go, the
 * database check passes, no raw row is touched, and a second run the same day does nothing.
 */
@RunWith(AndroidJUnit4::class)
class MaintenanceRunnerTest {

    private lateinit var db: ClarityDatabase
    private lateinit var runner: MaintenanceRunner
    private lateinit var reminders: com.clarity.life.promise.Reminders
    private lateinit var settings: SettingsRepository
    private val now = System.currentTimeMillis()
    private val hour = 3_600_000L
    private var promiseId = 0L

    @Before
    fun open() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, ClarityDatabase::class.java).build()
        val secrets = SecretStore(context)
        settings = SettingsRepository(db.settings(), secrets)
        val embedder = com.clarity.life.memory.Embedder(context)
        val search = com.clarity.life.memory.SearchIndex(db, embedder)
        val indexer = com.clarity.life.memory.EmbeddingIndexer(context, db, embedder)
        reminders = com.clarity.life.promise.Reminders(context, db)
        val backups = com.clarity.life.backup.BackupRepository(context, db, secrets, settings, search, indexer, reminders)
        runner = MaintenanceRunner(
            context = context,
            db = db,
            settings = settings,
            claims = com.clarity.life.writing.JobClaims(db),
            search = search,
            indexer = indexer,
            reminders = reminders,
            reflections = com.clarity.life.memory.ReflectionScheduler(context, db),
            writing = com.clarity.life.writing.WritingScheduler(context, settings),
            backups = backups,
        )
    }

    @After
    fun close() {
        if (promiseId != 0L) reminders.cancel(promiseId)
        db.close()
    }

    private fun count(sql: String): Int = db.query(sql, null).use { it.moveToFirst(); it.getInt(0) }

    @Test
    fun aNightRepairsWhatIsStuckAndTouchesNothingElse() = runBlocking {
        val conv = db.conversations().insert(ConversationRow(startedAt = now - 5 * hour, title = "Evening"))
        val said = db.messages().insert(MessageRow(conversationId = conv, role = "user", content = "I walked after dinner", createdAt = now - 5 * hour))
        val stuck = db.messages().insert(MessageRow(conversationId = conv, role = "coach", content = "That", createdAt = now - 3 * hour, status = "streaming"))
        val live = db.messages().insert(MessageRow(conversationId = conv, role = "coach", content = "Now", createdAt = now - 60_000, status = "streaming"))
        db.jobs().insertIfNew(JobRow(key = "reflection:$conv:1", type = "reflection", status = "running", attempts = 1, createdAt = now - 2 * hour, startedAt = now - 2 * hour))
        db.diagnostics().log(ErrorLogRow(at = now - 400L * 24 * hour, area = "chat", code = "card_dropped"))
        db.diagnostics().log(ErrorLogRow(at = now - 24 * hour, area = "chat", code = "card_dropped"))
        // The index holds everything, then loses his message (a write that failed).
        com.clarity.life.memory.SearchIndex(db, com.clarity.life.memory.Embedder(InstrumentationRegistry.getInstrumentation().targetContext)).rebuild()
        db.search().delete("message", said.toString())
        // A reminder ahead whose alarm the phone dropped.
        promiseId = db.promises().insert(PromiseRow(text = "Walk", dueAt = now + 5 * hour, dueText = "tonight", remind = 1, createdAt = now))
        val messagesBefore = count("SELECT COUNT(*) FROM message")

        val report = runner.runOnce(now)!!

        assertTrue("the database check passes", report.databaseOk)
        assertEquals(0, report.brokenLinks)
        assertEquals(1, report.jobsRequeued)
        assertEquals(1, report.repliesInterrupted)
        assertEquals(1, report.searchMissing)
        assertEquals(1, report.alarmsReset)
        assertEquals("not set up", report.backup)
        assertEquals(emptyList<String>(), report.failedSteps)
        assertEquals("interrupted", db.messages().get(stuck)?.status)
        assertEquals("a reply that may still be running is left alone", "streaming", db.messages().get(live)?.status)
        assertEquals("pending", db.jobs().byKey("reflection:$conv:1")?.status)
        assertEquals(1, count("SELECT COUNT(*) FROM search_doc WHERE kind = 'message' AND ref_id = '$said'"))
        assertTrue(reminders.isSet(promiseId))
        // The year-old log line went; last night's stays, with the night's own content-free lines.
        assertEquals(0, count("SELECT COUNT(*) FROM error_log WHERE at < ${now - 365L * 24 * hour}"))
        assertTrue(count("SELECT COUNT(*) FROM error_log WHERE code = 'card_dropped'") == 1)
        assertEquals("no message was added or removed", messagesBefore, count("SELECT COUNT(*) FROM message"))
        assertEquals("I walked after dinner", db.messages().get(said)?.content)
        // What Settings, Health shows.
        assertEquals("true", settings.get(SettingsRepository.Keys.MAINTENANCE_DB_OK, ""))
        val line = settings.get(SettingsRepository.Keys.MAINTENANCE_LINE, "")
        assertTrue(line, line.startsWith("database ok") && "1 reminders set again" in line && "search index rebuilt" in line)
        assertEquals("succeeded", db.jobs().withStatus("succeeded").single { it.type == "maintenance" }.status)

        // The same night again (WorkManager ran it twice): nothing happens.
        assertNull(runner.runOnce(now + 60_000))
    }

    @Test
    fun aCleanNightChangesNothing() = runBlocking {
        val report = runner.run(now)
        assertTrue(report.databaseOk)
        assertEquals(listOf(0, 0, 0, 0, 0), listOf(report.brokenLinks, report.jobsRequeued, report.repliesInterrupted, report.searchMissing, report.alarmsReset))
        assertEquals("database ok", runner.line(report))
    }
}
