package com.clarity.life.writing

import com.clarity.life.ai.AiFailure
import com.clarity.life.core.Jv
import com.clarity.life.core.PlanBlocks
import com.clarity.life.core.Planner
import com.clarity.life.core.ReviewFacts
import com.clarity.life.core.ReviewTimes
import com.clarity.life.core.StrictJson
import com.clarity.life.core.TextJobCheck
import com.clarity.life.core.TimeFacts
import com.clarity.life.data.ClarityDatabase
import com.clarity.life.data.entity.ReviewRow
import com.clarity.life.planner.PlannerRepository
import com.clarity.life.prompts.PromptStore
import com.clarity.life.settings.SettingsRepository
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * Weekly and monthly reviews (spec section 8). The review row, with its facts counted in code, is written as
 * soon as the period is due, so the numbers always appear; then one model call writes the note under them,
 * which is published only if every number in it is in its inputs and every quotation is his (TextJobCheck).
 * A failed note is retried; after the last attempt the screen says the note is delayed, with Try again.
 *
 * Nothing here can change a goal, a plan, a target or a promise, and no card is ever made.
 */
@Singleton
class ReviewWriter @Inject constructor(
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

    suspend fun localNow(now: Long): LocalDateTime = TimeFacts.local(Instant.ofEpochMilli(now), settings.timeZone())

    /** Every review that is due and missing gets its row now. Returns the reviews still waiting for a note. */
    suspend fun catchUp(now: Long): List<ReviewRow> {
        val local = localNow(now)
        val first = db.planner().firstTargetDay()?.let(LocalDate::parse)
        for (monday in ReviewTimes.dueWeeks(local, first)) ensure("week", monday, monday.plusDays(6), now)
        val boundary = settings.dayBoundaryHour()
        for (month in ReviewTimes.dueMonths(local, first, boundary)) ensure("month", month, month.plusMonths(1).minusDays(1), now)
        val waiting = mutableListOf<ReviewRow>()
        for (monday in ReviewTimes.dueWeeks(local, first)) dao.reviewByKey(ReviewTimes.weekKey(monday))?.let { waiting += it }
        for (month in ReviewTimes.dueMonths(local, first, boundary)) dao.reviewByKey(ReviewTimes.monthKey(month))?.let { waiting += it }
        return waiting.filter { it.narrative.isBlank() }
    }

    /** The review of one period, made once (the unique key makes a second run a no-op). */
    suspend fun ensure(kind: String, first: LocalDate, last: LocalDate, now: Long): ReviewRow? {
        val key = if (kind == "week") ReviewTimes.weekKey(first) else ReviewTimes.monthKey(first)
        val facts = facts(kind, first, last)
        dao.reviewByKey(key)?.let { existing ->
            // Made at Sunday 19:00, before his day ended: the figures are brought up to date until the note exists.
            if (existing.narrative.isBlank()) {
                dao.refreshFacts(existing.id, ReviewFacts.toJson(facts), if (facts.replan) 1 else 0)
                return dao.reviewByKey(key)
            }
            return existing
        }
        dao.insertReview(
            ReviewRow(
                kind = kind,
                periodStart = first.toString(),
                periodEnd = last.toString(),
                factsJson = ReviewFacts.toJson(facts),
                replanFlag = if (facts.replan) 1 else 0,
                key = key,
                createdAt = now,
            ),
        )
        return dao.reviewByKey(key)
    }

    /** The facts, from the raw rows (so they can always be rebuilt). */
    suspend fun facts(kind: String, first: LocalDate, last: LocalDate): ReviewFacts.Facts {
        val p = db.planner()
        val targets = p.targetsDuring(first.toString(), last.toString())
        val byTarget = if (targets.isEmpty()) emptyMap() else p.checkinsOf(targets.map { it.id }).groupBy { it.targetId }
        // Schema 8: each target's values over time, so plan versus actual reads what was asked on each day.
        val versions = if (targets.isEmpty()) emptyMap() else p.targetVersions(targets.map { it.id }).groupBy { it.targetId }
        val tz = settings.timeZone()
        val boundary = settings.dayBoundaryHour()
        val previous = if (kind == "week") {
            dao.reviewBefore("week", first.toString())?.let { ReviewFacts.fromJson(it.factsJson)?.backlogs }.orEmpty()
        } else {
            emptyList()
        }
        return ReviewFacts.build(
            kind = kind,
            first = first,
            last = last,
            targets = targets.map { t ->
                PlanBlocks.Target(
                    t.id, t.name, t.goalId, t.backlogId, t.minimum, t.target, t.schedule,
                    (byTarget[t.id] ?: emptyList()).map { PlannerRepository.model(it) }, LocalDate.parse(t.startDay),
                    t.endDay?.let(LocalDate::parse),
                    versions = (versions[t.id] ?: emptyList()).map { PlannerRepository.version(it) },
                    unit = t.unit,
                    minutes = t.minutes,
                )
            },
            backlogs = p.activeBacklogs().map { b ->
                PlanBlocks.Backlog(
                    b.id, b.name, b.goalId, b.total, b.startDone, LocalDate.parse(b.deadline),
                    // Stored at creation (schema 3); worked out only for a backlog the planner has not read yet.
                    b.startDay?.let(LocalDate::parse)
                        ?: Planner.dayKey(Instant.ofEpochMilli(b.createdAt).toString(), tz, boundary),
                )
            },
            rest = p.restDays().map { LocalDate.parse(it.dayKey) }.toSet(),
            previous = previous,
            availability = settings.planHours()?.let { com.clarity.life.core.PlanLoad.hoursText(it) }
                ?: db.memory().profileEntry(PlannerRepository.AVAILABILITY)?.value?.takeIf { it.isNotBlank() } ?: "not set",
        )
    }

    /** The note under one review. [retry] is his Try again: a fresh set of attempts. */
    suspend fun writeNote(reviewId: Long, now: Long, retry: Boolean = false): Outcome {
        val review = dao.review(reviewId) ?: return Outcome.NothingToDo
        if (review.narrative.isNotBlank()) return Outcome.NothingToDo
        val type = if (review.kind == "week") "review_week" else "review_month"
        val key = "$type:${review.periodStart}"
        val facts = ReviewFacts.fromJson(review.factsJson) ?: return Outcome.Failed("facts_unreadable")
        if (facts.answered == 0) {
            // Nothing to say: no call is made, and the screen shows the figures alone. Settled for good only
            // once his last day of the period has ended; before that a late check-in may still come.
            if (planner.today(now).isAfter(LocalDate.parse(review.periodEnd))) jobs.skip(key, type, now)
            return Outcome.NothingToDo
        }
        val endpoint = settings.backgroundEndpoint(json = review.kind == "month") ?: return Outcome.NothingToDo
        val job = when (val c = jobs.claim(key, type, now, retry)) {
            is JobClaims.Claim.Settled -> return if (c.status == "failed") Outcome.Failed("failed") else Outcome.NothingToDo
            is JobClaims.Claim.Run -> c.job
        }
        try {
            val first = LocalDate.parse(review.periodStart)
            val last = LocalDate.parse(review.periodEnd)
            val today = planner.today(now)
            val tracker = planner.tracker(today)
            val planState = PlanBlocks.planState(tracker, today).items.joinToString("\n") { it.text }
            val notes = db.memory().activeNotes().joinToString("\n") { "${it.kind}: ${it.text}" }.ifBlank { "(none)" }
            val period = "${TimeFacts.shortDay(first)} to ${TimeFacts.shortDay(last)}"
            val missNotes = missNotes(first, last)
            val his = missNotes.map { it.second } + hisMessages(first, last, now)
            val previous = dao.reviewBefore(review.kind, review.periodStart)?.narrative?.takeIf { it.isNotBlank() } ?: "(none)"
            val month = today.toString().take(7)
            if (review.kind == "week") {
                val values = mapOf(
                    "USER_NAME" to settings.promptName(),
                    "PERIOD" to period,
                    "FACTS" to facts.text,
                    "PLAN_STATE" to planState,
                    "MISS_NOTES" to missNotes.joinToString("\n") { "${it.first}: ${it.second}" }.ifBlank { "(none)" },
                    "NOTES" to notes,
                    "PREVIOUS_REVIEW" to previous,
                )
                val raw = call.text(endpoint, prompts.fill(prompts.text(PROMPT_WEEKLY), values), month)
                val checked = TextJobCheck.check("review_weekly", raw, values.values.toList(), his)
                if (!checked.ok) return failed(job, "check_failed", now)
                dao.setNarrative(review.id, checked.body, "[]")
            } else {
                val weekly = dao.weeklyReviewsWithin(review.periodStart, review.periodEnd)
                    .filter { it.narrative.isNotBlank() }
                    .joinToString("\n\n") { "${TimeFacts.shortDay(LocalDate.parse(it.periodStart))}: ${it.narrative}" }
                    .ifBlank { "(none)" }
                // Plan versions made during the month, with the reasons he saved them for.
                val zone = java.time.ZoneId.of(settings.timeZone())
                val from = first.atStartOfDay(zone).toInstant().toEpochMilli()
                val to = last.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val history = db.planner().plansSince(from).filter { it.createdAt < to }
                    .joinToString("\n") { "G${it.goalId} plan v${it.version} (${it.status}): ${it.rationale}" }
                    .ifBlank { "(no plan changes this month)" }
                val values = mapOf(
                    "USER_NAME" to settings.promptName(),
                    "PERIOD" to period,
                    "FACTS" to facts.text,
                    "PLAN_STATE" to planState,
                    "GOAL_HISTORY" to history,
                    "WEEKLY_REVIEWS" to weekly,
                    "NOTES" to notes,
                    "PREVIOUS_REVIEW" to previous,
                )
                val known = PlanBlocks.known(tracker, today)
                val ids = (known.goals + known.targets + known.backlogs).toList()
                val raw = call.text(endpoint, prompts.fill(prompts.text(PROMPT_MONTHLY), values), month)
                val checked = TextJobCheck.monthly(raw, ids, values.values.toList(), his)
                if (!checked.ok) return failed(job, "check_failed", now)
                val revisit = StrictJson.write(
                    Jv.Arr(
                        checked.revisit.map { r ->
                            Jv.Obj(linkedMapOf("ref" to (r.ref?.let { Jv.Str(it) } ?: Jv.Null), "question" to Jv.Str(r.question)))
                        },
                    ),
                )
                dao.setNarrative(review.id, checked.headline + "\n\n" + checked.narrative, revisit)
            }
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
            jobs.fail(job, AREA, "error_" + e.javaClass.simpleName, now)
            return Outcome.Failed("error")
        }
    }

    private suspend fun failed(job: com.clarity.life.data.entity.JobRow, code: String, now: Long): Outcome =
        if (jobs.fail(job, AREA, code, now)) Outcome.Retry(code) else Outcome.Failed(code)

    /** What he wrote next to Not and Part answers in the period, with the day and the target. */
    private suspend fun missNotes(first: LocalDate, last: LocalDate): List<Pair<String, String>> {
        val p = db.planner()
        val names = p.targetsDuring(first.toString(), last.toString()).associate { it.id to it.name }
        return p.checkinsBetween(first.toString(), last.toString())
            .filter { it.level in Planner.SHORT_LEVELS && !it.note.isNullOrBlank() }
            .map { "${TimeFacts.shortDay(LocalDate.parse(it.dayKey))}, ${names[it.targetId] ?: "a target"}" to it.note!! }
    }

    /** His own messages from the period: the only words a quotation in the note may use. */
    private suspend fun hisMessages(first: LocalDate, last: LocalDate, now: Long): List<String> {
        val from = first.minusDays(1).atStartOfDay(java.time.ZoneId.of(settings.timeZone())).toInstant().toEpochMilli()
        val to = last.plusDays(2).atStartOfDay(java.time.ZoneId.of(settings.timeZone())).toInstant().toEpochMilli()
        return dao.hisMessagesBetween(from, minOf(to, now + 1))
    }

    suspend fun markRead(id: Long, now: Long) = dao.markReviewRead(id, now)

    fun watchAll() = dao.watchReviews()

    fun watch(id: Long) = dao.watchReview(id)

    suspend fun jobStatus(review: ReviewRow): String? {
        val type = if (review.kind == "week") "review_week" else "review_month"
        return jobs.status("$type:${review.periodStart}")?.status
    }

    companion object {
        const val AREA = "review"
        const val PROMPT_WEEKLY = "review_weekly.md"
        const val PROMPT_MONTHLY = "review_monthly.md"
    }
}
