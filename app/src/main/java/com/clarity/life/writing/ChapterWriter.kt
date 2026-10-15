package com.clarity.life.writing

import com.clarity.life.ai.AiFailure
import com.clarity.life.core.Chapters
import com.clarity.life.core.KnowText
import com.clarity.life.core.PlanBlocks
import com.clarity.life.core.ReviewFacts
import com.clarity.life.core.TextJobCheck
import com.clarity.life.data.ClarityDatabase
import com.clarity.life.data.entity.ChapterRow
import com.clarity.life.planner.PlannerRepository
import com.clarity.life.prompts.PromptStore
import com.clarity.life.settings.SettingsRepository
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * The quarterly chapter (spec section 8, prompts/chapter.md): after a calendar quarter ends, one chapter is
 * written from its session summaries, monthly reviews, letters, his saved words, goals, focus summaries and
 * notes. It is published only if every quotation is his and every number is in its inputs (TextJobCheck,
 * "chapter"); after two failed checks it is not published. One chapter per quarter (the unique period and the
 * job key). Only the latest finished quarter is written, and only when it had at least
 * [Chapters.MIN_SESSIONS] reflected conversations; a quiet quarter costs no call.
 */
@Singleton
class ChapterWriter @Inject constructor(
    private val db: ClarityDatabase,
    private val settings: SettingsRepository,
    private val prompts: PromptStore,
    private val call: BackgroundCall,
    private val jobs: JobClaims,
    private val planner: PlannerRepository,
) {
    private val dao get() = db.writing()

    sealed interface Outcome {
        object Written : Outcome
        object NothingToDo : Outcome
        data class Retry(val code: String) : Outcome
        data class Failed(val code: String) : Outcome
    }

    suspend fun dueQuarter(now: Long): Chapters.Quarter = Chapters.latestFinished(planner.today(now))

    suspend fun write(q: Chapters.Quarter, now: Long): Outcome {
        if (dao.chapterStarting(q.start.toString()) != null) return Outcome.NothingToDo
        val zone = ZoneId.of(settings.timeZone())
        val from = q.start.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = q.end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val sessions = dao.summarisedBetween(from, to)
        if (!Chapters.worthWriting(sessions.size)) {
            jobs.skip(q.key, TYPE, now)
            return Outcome.NothingToDo
        }
        val endpoint = settings.backgroundEndpoint(json = false) ?: return Outcome.NothingToDo
        val job = when (val c = jobs.claim(q.key, TYPE, now, false, MAX_CHECKS)) {
            is JobClaims.Claim.Settled -> return if (c.status == "failed") Outcome.Failed("failed") else Outcome.NothingToDo
            is JobClaims.Claim.Run -> c.job
        }
        try {
            val first = q.start.toString()
            val last = q.end.toString()
            val today = planner.today(now)
            val quotes = dao.quotesWithin(first, last)
            val m = db.memory()
            val values = mapOf(
                "USER_NAME" to settings.promptName(),
                "PERIOD" to q.label,
                "PREVIOUS_CHAPTER" to (dao.chapterBefore(first)?.let { "${it.title}\n${it.body}" } ?: "(none)"),
                "REVIEWS" to dao.monthlyReviewsWithin(first, last).joinToString("\n\n") { r ->
                    val facts = ReviewFacts.fromJson(r.factsJson)?.text.orEmpty()
                    "${KnowText.shortDay(r.periodStart)} to ${KnowText.shortDay(r.periodEnd)}\n$facts\n${r.narrative}".trim()
                }.ifBlank { "(none)" },
                "LETTERS" to dao.lettersWithin(first, last).joinToString("\n\n") { "${it.title}\n${it.body}" }
                    .ifBlank { "(none)" },
                "SUMMARIES" to sessions.joinToString("\n") { c ->
                    val day = com.clarity.life.core.TimeFacts.local(java.time.Instant.ofEpochMilli(c.startedAt), zone.id)
                        .toLocalDate().toString()
                    val title = if (c.title.isNotBlank()) " \"${c.title}\"" else ""
                    "${KnowText.shortDay(day)}$title: ${c.summary}"
                },
                "QUOTES" to quotes.joinToString("\n") { "${KnowText.shortDay(it.saidDay)}: \"${it.text}\"" }
                    .ifBlank { "(none)" },
                "GOALS" to PlanBlocks.planState(planner.tracker(today), today).items.joinToString("\n") { it.text },
                "FOCUS_SUMMARIES" to dao.allFocuses().joinToString("\n") {
                    "${it.name} (${it.status}): ${it.summary.ifBlank { it.why }}"
                }.ifBlank { "(none)" },
                "NOTES" to m.activeNotes().joinToString("\n") { "${it.kind} (${it.confidence}): ${it.text}" }
                    .ifBlank { "(none)" },
            )
            // His own words: the quotes saved from the period and every message he wrote in it.
            val his = quotes.map { it.text } + dao.hisMessagesBetween(from, to)
            val raw = call.text(endpoint, prompts.fill(prompts.text(PROMPT), values), today.toString().take(7), com.clarity.life.data.UsageKind.CHAPTER)
            val checked = TextJobCheck.check("chapter", raw, values.values.toList(), his)
            if (!checked.ok) return failed(job, "check_failed", now)
            dao.insertChapter(
                ChapterRow(periodStart = first, periodEnd = last, title = checked.title, body = checked.body, createdAt = now),
            )
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

    companion object {
        const val TYPE = "chapter"
        const val AREA = "chapter"
        const val PROMPT = "chapter.md"

        /** Like the letter: two failed checks and it is not published. */
        const val MAX_CHECKS = 2
    }
}
