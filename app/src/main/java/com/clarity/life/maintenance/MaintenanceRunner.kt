package com.clarity.life.maintenance

import android.content.Context
import com.clarity.life.backup.BackupRepository
import com.clarity.life.core.Maintenance
import com.clarity.life.data.ClarityDatabase
import com.clarity.life.data.entity.ErrorLogRow
import com.clarity.life.settings.SettingsRepository
import com.clarity.life.writing.JobClaims
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The nightly maintenance (docs/MAX-PLAN.md, Phase 1, steps 4 and 9): the app looking after itself, without
 * being asked and without the AI. Each step only checks, repairs derived data, or re-sets what is already saved;
 * no raw row (a message, a check-in, a promise, a note) is ever changed or deleted here.
 *
 * 1. Database check: SQLite's integrity check, and foreign keys that point at nothing (counted, never deleted).
 * 2. Stuck states: a job left running by a dead process goes back to pending; a reply marked streaming for hours
 *    becomes interrupted, with its Try again button (both moves are in contracts/states.json).
 * 3. Search: anything missing from the keyword index rebuilds it; missing meaning vectors are made.
 * 4. Reminders: every reminder ahead must have an alarm; a lost one is set again.
 * 5. Housekeeping: content-free log lines older than a year go.
 * 6. Catch-up: conversations not reflected yet, and reviews, letters and gardening that are due.
 * 7. Backup, when one is due, but never after a failed database check: a backup of a damaged database would
 *    push the good older ones out of the folder.
 *
 * One run a day: the job key is the day. A step that fails is logged by a code and the others still run.
 */
@Singleton
class MaintenanceRunner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: ClarityDatabase,
    private val settings: SettingsRepository,
    private val claims: JobClaims,
    private val search: com.clarity.life.memory.SearchIndex,
    private val indexer: com.clarity.life.memory.EmbeddingIndexer,
    private val reminders: com.clarity.life.promise.Reminders,
    private val reflections: com.clarity.life.memory.ReflectionScheduler,
    private val writing: com.clarity.life.writing.WritingScheduler,
    private val backups: BackupRepository,
) {

    /** What one run found, in counts only. */
    data class Report(
        val databaseOk: Boolean,
        val brokenLinks: Int,
        val jobsRequeued: Int,
        val repliesInterrupted: Int,
        val searchMissing: Int,
        val alarmsReset: Int,
        val backup: String,
        val failedSteps: List<String>,
    )

    /** Runs once per day; a second call the same day returns null without doing anything. */
    suspend fun runOnce(now: Long = System.currentTimeMillis()): Report? {
        val day = com.clarity.life.core.TimeFacts.local(Instant.ofEpochMilli(now), settings.timeZone()).toLocalDate()
        val key = "maintenance:$day"
        val job = when (val claim = claims.claim(key, TYPE, now)) {
            is JobClaims.Claim.Run -> claim.job
            is JobClaims.Claim.Settled -> return null
        }
        return try {
            val report = run(now)
            claims.succeed(job, System.currentTimeMillis())
            report
        } catch (e: kotlinx.coroutines.CancellationException) {
            claims.release(job)
            throw e
        } catch (e: Exception) {
            claims.fail(job, "maintenance", "maintenance_failed", System.currentTimeMillis())
            null
        }
    }

    /** Every step, whatever the day; each one on its own, so one failure never stops the rest. */
    suspend fun run(now: Long = System.currentTimeMillis()): Report = withContext(Dispatchers.IO) {
        val failed = mutableListOf<String>()
        suspend fun <T> step(name: String, fallback: T, block: suspend () -> T): T = try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            failed.add(name)
            log("maintenance_step_failed", "$name ${e.javaClass.simpleName}", now)
            fallback
        }

        val sqlite = db.openHelper.writableDatabase
        val integrity = step("integrity", listOf("not checked")) {
            buildList { sqlite.query("PRAGMA integrity_check($INTEGRITY_LINES)").use { c -> while (c.moveToNext()) add(c.getString(0)) } }
        }
        val databaseOk = Maintenance.integrityOk(integrity)
        if (!databaseOk) log("integrity_failed", "lines=${integrity.size}", now)
        val brokenLinks = step("links", 0) {
            sqlite.query("PRAGMA foreign_key_check").use { c -> var n = 0; while (c.moveToNext() && n < MAX_COUNT) n++; n }
        }
        if (brokenLinks > 0) log("broken_links", "n=$brokenLinks", now)

        val jobs = step("jobs", 0) { db.repair().requeueRunningJobs(now - STALE_RUNNING_MS) }
        val replies = step("replies", 0) { db.repair().interruptStaleStreaming(now - Maintenance.STALE_STREAMING_MS) }
        val searchMissing = step("search", 0) { search.repairIfIncomplete() }
        step("vectors", Unit) { indexer.schedule() }
        val alarms = step("reminders", 0) { reminders.heal(now) }
        step("logs", Unit) { db.diagnostics().prune(now - Maintenance.LOG_KEEP_DAYS * DAY) }
        step("catch_up", Unit) {
            reflections.sweep(now)
            writing.onStart(now)
        }

        val backup = step("backup", "failed") {
            when {
                !backups.ready() -> "not set up"
                !backups.due(now) -> "not due"
                !databaseOk -> "held back"
                else -> when (backups.backUp(now)) {
                    is BackupRepository.Outcome.Saved -> "made"
                    else -> "failed"
                }
            }
        }

        val report = Report(databaseOk, brokenLinks, jobs, replies, searchMissing, alarms, backup, failed)
        step("record", Unit) {
            settings.set(SettingsRepository.Keys.MAINTENANCE_AT, now.toString())
            settings.set(SettingsRepository.Keys.MAINTENANCE_DB_OK, databaseOk.toString())
            settings.set(SettingsRepository.Keys.MAINTENANCE_LINE, line(report))
        }
        report
    }

    /** The plain line Settings, Health shows: counts only, never a word of what was said. */
    fun line(r: Report): String = buildList {
        add(if (r.databaseOk) "database ok" else "database check found a problem")
        if (r.brokenLinks > 0) add("${r.brokenLinks} broken links")
        if (r.jobsRequeued > 0) add("${r.jobsRequeued} stuck jobs restarted")
        if (r.repliesInterrupted > 0) add("${r.repliesInterrupted} stuck replies stopped")
        if (r.searchMissing > 0) add("search index rebuilt")
        if (r.alarmsReset > 0) add("${r.alarmsReset} reminders set again")
        when (r.backup) {
            "made" -> add("backup made")
            "held back" -> add("backup held back")
            "failed" -> add("backup failed")
        }
        if (r.failedSteps.isNotEmpty()) add("${r.failedSteps.size} steps failed")
    }.joinToString(", ")

    private suspend fun log(code: String, detail: String, now: Long) {
        try {
            db.diagnostics().log(ErrorLogRow(at = now, area = "maintenance", code = code, detail = detail))
        } catch (e: Exception) {
            // The log is best effort; the run goes on.
        }
    }

    companion object {
        const val TYPE = "maintenance"
        private const val DAY = 86_400_000L
        private const val INTEGRITY_LINES = 20
        private const val MAX_COUNT = 1000
        private const val STALE_RUNNING_MS = 30 * 60 * 1000L
    }
}
