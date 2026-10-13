package com.clarity.life.writing

import com.clarity.life.ai.AiFailure
import com.clarity.life.core.BriefInput
import com.clarity.life.core.PlanBlocks
import com.clarity.life.core.PromiseText
import com.clarity.life.core.TextJobCheck
import com.clarity.life.core.TimeFacts
import com.clarity.life.data.ClarityDatabase
import com.clarity.life.data.entity.BriefRow
import com.clarity.life.planner.PlannerRepository
import com.clarity.life.promise.PromiseRepository
import com.clarity.life.prompts.PromptStore
import com.clarity.life.settings.DefaultSettings
import com.clarity.life.settings.SettingsRepository
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

/**
 * The morning brief (spec section 8, the 0.1.6 redesign): a few lines from the coach each morning, shown on
 * Talk home and Plan, Today. Written from finished facts only (prompts/brief_daily.md) and published only if
 * TextJobCheck passes (kind "brief": plain text, quotes his, every number in its inputs). One per day (the key).
 * It never creates a card and never changes a goal, plan, target, promise or note: it writes its own table only.
 * A brief that fails its check twice is not shown; tomorrow has a new one.
 */
@Singleton
class BriefWriter @Inject constructor(
    private val db: ClarityDatabase,
    private val settings: SettingsRepository,
    private val prompts: PromptStore,
    private val call: BackgroundCall,
    private val jobs: JobClaims,
    private val planner: PlannerRepository,
) {
    private val dao get() = db.brief()

    sealed interface Outcome {
        object Written : Outcome
        object NothingToDo : Outcome
        data class Retry(val code: String) : Outcome
        data class Failed(val code: String) : Outcome
    }

    suspend fun on(): Boolean = settings.getBool(SettingsRepository.Keys.MORNING_BRIEF_ON, DefaultSettings.MORNING_BRIEF_ON)

    /** Writes today's brief if it is on, due, worth writing and not written yet. */
    suspend fun writeDue(now: Long): Outcome {
        if (!on()) return Outcome.NothingToDo
        val tz = settings.timeZone()
        val boundary = settings.dayBoundaryHour()
        val today = planner.today(now)
        if (!BriefInput.due(TimeFacts.local(Instant.ofEpochMilli(now), tz), today, boundary)) return Outcome.NothingToDo
        val key = BriefInput.key(today)
        if (dao.byKey(key) != null) return Outcome.NothingToDo
        val tracker = planner.tracker(today)
        val open = db.promises().open().map { PromiseRepository.model(it) }
        if (!BriefInput.worthWriting(tracker, today, open.size)) {
            jobs.skip(key, TYPE, now)
            return Outcome.NothingToDo
        }
        val endpoint = settings.backgroundEndpoint(json = false) ?: return Outcome.NothingToDo
        val job = when (val c = jobs.claim(key, TYPE, now, retry = false, max = MAX_CHECKS)) {
            is JobClaims.Claim.Settled -> return if (c.status == "failed") Outcome.Failed("failed") else Outcome.NothingToDo
            is JobClaims.Claim.Run -> c.job
        }
        try {
            val at = Instant.ofEpochMilli(now)
            val todayLines = buildList {
                addAll(PlanBlocks.planState(tracker, today).items.map { it.text })
                addAll(PlanBlocks.checkinFacts(tracker, today).items.map { it.text })
            }
            val values = mapOf(
                "USER_NAME" to settings.promptName(),
                "NOW" to TimeFacts.nowSentence(at, tz, boundary),
                "TODAY_STEPS" to todayLines.joinToString("\n").ifBlank { "(nothing scheduled today)" },
                "YESTERDAY" to BriefInput.yesterday(tracker, today.minusDays(1)).joinToString("\n").ifBlank { "(nothing was scheduled)" },
                "OPEN_PROMISES" to open.joinToString("\n") { PromiseText.openLine(it, at, tz) }.ifBlank { "(none)" },
                "NOTES" to db.memory().activeNotes().joinToString("\n") { "${it.kind}: ${it.text}" }.ifBlank { "(none)" },
                "PREVIOUS_BRIEF" to (dao.before(today.toString())?.body ?: "(none)"),
            )
            // His own words from the last two days, so a quotation of him can be checked word for word.
            val since = now - java.time.Duration.ofDays(2).toMillis()
            val his = db.messages().userTextSince(since)
            val raw = call.text(endpoint, prompts.fill(prompts.text(PROMPT), values), today.toString().take(7))
            val checked = TextJobCheck.check("brief", raw, values.values.toList(), his)
            if (!checked.ok) return failed(job, "check_failed", now)
            dao.insert(BriefRow(dayKey = today.toString(), body = checked.body, createdAt = now, key = key))
            jobs.succeed(job, now)
            return Outcome.Written
        } catch (e: CancellationException) {
            jobs.release(job)
            throw e
        } catch (e: BackgroundCall.CutOff) {
            return failed(job, "cut_off", now)
        } catch (e: AiFailure) {
            return failed(job, e.code, now)
        } catch (e: Throwable) {
            jobs.fail(job, AREA, "error_" + e.javaClass.simpleName, now, MAX_CHECKS)
            return Outcome.Failed("error")
        }
    }

    private suspend fun failed(job: com.clarity.life.data.entity.JobRow, code: String, now: Long): Outcome =
        if (jobs.fail(job, AREA, code, now, MAX_CHECKS)) Outcome.Retry(code) else Outcome.Failed(code)

    /** Today's brief, or nothing. An old brief is never shown as today's. */
    fun watch(day: LocalDate): Flow<BriefRow?> = dao.watchFor(day.toString())

    companion object {
        const val TYPE = "brief"
        const val AREA = "brief"
        const val PROMPT = "brief_daily.md"

        /** A brief that fails its check twice is not shown (spec section 8). */
        const val MAX_CHECKS = 2
    }
}
