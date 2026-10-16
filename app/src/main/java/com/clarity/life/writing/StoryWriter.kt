package com.clarity.life.writing

import androidx.room.withTransaction
import com.clarity.life.ai.AiFailure
import com.clarity.life.core.Chapters
import com.clarity.life.core.Jv
import com.clarity.life.core.KnowText
import com.clarity.life.core.PlanBlocks
import com.clarity.life.core.ReviewFacts
import com.clarity.life.core.StrictJson
import com.clarity.life.core.Story
import com.clarity.life.core.TextJobCheck
import com.clarity.life.data.ClarityDatabase
import com.clarity.life.data.entity.JobRow
import com.clarity.life.data.entity.StoryRow
import com.clarity.life.planner.PlannerRepository
import com.clarity.life.prompts.PromptStore
import com.clarity.life.settings.SettingsRepository
import com.clarity.life.story.StoryRepository
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * The monthly story (spec 2.5 and 8, prompts/story.md): after each calendar month ends, one story is written
 * for the top of Your story: the short version, a then and a now quote picked by id, and at most three things
 * he overcame. It is published only when [TextJobCheck.story] passes (his words word for word, every number in
 * the facts the app counted); after two failed checks it is not published and the page keeps the last story.
 * One story per month (the unique key). A month with no reflected conversation and no check-in costs no call.
 * The job writes only its own row: never a card, a goal, a plan, a target, a promise or a note.
 */
@Singleton
class StoryWriter @Inject constructor(
    private val db: ClarityDatabase,
    private val settings: SettingsRepository,
    private val prompts: PromptStore,
    private val call: BackgroundCall,
    private val jobs: JobClaims,
    private val planner: PlannerRepository,
    private val stories: StoryRepository,
) {
    private val dao get() = db.story()

    sealed interface Outcome {
        object Written : Outcome
        object NothingToDo : Outcome
        data class Retry(val code: String) : Outcome
        data class Failed(val code: String) : Outcome
    }

    /** The month that ended most recently: the only one a story is written after. */
    suspend fun dueMonth(now: Long): YearMonth = YearMonth.from(planner.today(now)).minusMonths(1)

    suspend fun write(month: YearMonth, now: Long): Outcome {
        val key = "story:$month"
        if (dao.storyByKey(key) != null) return Outcome.NothingToDo
        val first = stories.first() ?: return Outcome.NothingToDo
        if (month.atEndOfMonth().isBefore(first)) return Outcome.NothingToDo
        val zone = ZoneId.of(settings.timeZone())
        val from = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val sessions = db.writing().summarisedBetween(from, to)
        val checkinDays = dao.checkinDays(month.atDay(1).toString(), month.atEndOfMonth().toString())
        if (sessions.isEmpty() && checkinDays == 0) {
            jobs.skip(key, TYPE, now)
            return Outcome.NothingToDo
        }
        val endpoint = settings.backgroundEndpoint(json = true) ?: return Outcome.NothingToDo
        val fallback = settings.fallbackBackgroundEndpoint(json = true)
        val job = when (val c = jobs.claim(key, TYPE, now, false, MAX_CHECKS)) {
            is JobClaims.Claim.Settled -> return if (c.status == "failed") Outcome.Failed("failed") else Outcome.NothingToDo
            is JobClaims.Claim.Run -> c.job
        }
        try {
            val facts = stories.facts(now) ?: run {
                jobs.release(job)
                return Outcome.NothingToDo
            }
            val today = facts.today
            // His saved words, the oldest and the newest, so a then and a now can both be chosen.
            val quotes = (dao.earliestQuotes(EARLY_QUOTES) + dao.latestQuotes(LATE_QUOTES))
                .distinctBy { it.id }
                .sortedWith(compareBy({ it.saidDay }, { it.id }))
            val m = db.memory()
            val w = db.writing()
            val values = mapOf(
                "USER_NAME" to settings.promptName(),
                "SINCE" to "${KnowText.shortDay(facts.first.toString())} ${facts.first.year}",
                "LENGTH" to Story.length(facts.first, today),
                "FACTS" to stories.factsText(facts),
                "PLAN_STATE" to PlanBlocks.planState(planner.tracker(today), today).items.joinToString("\n") { it.text }
                    .ifBlank { "(none)" },
                "CHAPTERS" to w.latestChapters(MAX_CHAPTERS).reversed().joinToString("\n\n") { c ->
                    val q = Chapters.Quarter(java.time.LocalDate.parse(c.periodStart), java.time.LocalDate.parse(c.periodEnd))
                    "${q.label}: ${c.title}\n${c.body}"
                }.ifBlank { "(none)" },
                "MONTHLY_REVIEWS" to w.monthlyReviewsWithin(month.minusMonths(2).atDay(1).toString(), month.atEndOfMonth().toString())
                    .joinToString("\n\n") { r ->
                        val f = ReviewFacts.fromJson(r.factsJson)?.text.orEmpty()
                        "${KnowText.shortDay(r.periodStart)} to ${KnowText.shortDay(r.periodEnd)}\n$f\n${r.narrative}".trim()
                    }.ifBlank { "(none)" },
                "FOCUSES" to w.allFocuses().joinToString("\n") { f ->
                    val done = facts.doneFocuses.firstOrNull { it.focus.id == f.id }
                    val kept = done?.stepsKept?.joinToString("; ")?.let { " Steps kept: $it." } ?: ""
                    "${f.name} (${f.status}): ${f.summary.ifBlank { f.why }}$kept"
                }.ifBlank { "(none)" },
                "NOTES" to m.activeNotes().joinToString("\n") { "${it.kind} (${it.confidence}, seen ${it.timesSeen}): ${it.text}" }
                    .ifBlank { "(none)" },
                "QUOTES" to quotes.joinToString("\n") { "id ${it.id}, ${KnowText.shortDay(it.saidDay)} ${it.saidDay.take(4)}: \"${it.text}\"" }
                    .ifBlank { "(none)" },
                "PREVIOUS_STORY" to (dao.storyBefore(month.atDay(1).toString())?.short ?: "(none)"),
            )
            // His own words: the quotes he was shown and every message he wrote in the month.
            val his = quotes.map { it.text } + w.hisMessagesBetween(from, to)
            val raw = call.text(endpoint, prompts.fill(prompts.text(PROMPT), values), today.toString().take(7), job = "story", fallback = fallback)
            val checked = TextJobCheck.story(raw, quotes.associate { it.id to it.saidDay }, values.values.toList(), his)
            if (!checked.ok) return failed(job, "check_failed", now)
            val overcame = StrictJson.write(
                Jv.Arr(
                    checked.overcame.map { o ->
                        Jv.Obj(
                            linkedMapOf(
                                "title" to Jv.Str(o.title),
                                "then_quote_id" to (o.thenQuote?.let { Jv.Num(it.toDouble(), true) } ?: Jv.Null),
                                "did" to Jv.Str(o.did),
                                "now" to Jv.Str(o.now),
                            ),
                        )
                    },
                ),
            )
            db.withTransaction {
                dao.insertStory(
                    StoryRow(
                        periodStart = month.atDay(1).toString(),
                        periodEnd = month.atEndOfMonth().toString(),
                        short = checked.short,
                        thenQuoteId = checked.thenQuote,
                        nowQuoteId = checked.nowQuote,
                        overcameJson = overcame,
                        createdAt = now,
                        key = key,
                    ),
                )
                jobs.succeed(job, now)
            }
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

    private suspend fun failed(job: JobRow, code: String, now: Long): Outcome =
        if (jobs.fail(job, AREA, code, now, MAX_CHECKS)) Outcome.Retry(code) else Outcome.Failed(code)

    companion object {
        const val TYPE = "story"
        const val AREA = "story"
        const val PROMPT = "story.md"

        /** Like the letter and the chapter: two failed checks and it is not published. */
        const val MAX_CHECKS = 2
        const val EARLY_QUOTES = 40
        const val LATE_QUOTES = 60
        const val MAX_CHAPTERS = 8
    }
}
