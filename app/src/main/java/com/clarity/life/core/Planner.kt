package com.clarity.life.core

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The planner math: everything the coach is ever told about pace, days left, projections, review dates
 * and weekly facts. Rule 2 of CLAUDE.md: code does all dates, counts, pace and math; the AI only copies
 * finished facts.
 *
 * This is a 1:1 port of tools/planner.py and it must keep passing every case in
 * contracts/planner_vectors.json (PlannerTest reads that very file).
 *
 * Conventions
 * - A "day" is a local calendar date. [dayKey] turns a UTC instant into the date it belongs to, with a day
 *   boundary hour (default 4) so a 12:30 am check-in counts for the day before.
 * - Weekdays are Mon=0 ... Sun=6.
 * - Levels: rest, not, minimum, target, beyond. "Done" = minimum, target or beyond. Rest is neither done
 *   nor missed, and a missing row is "no answer", never a miss.
 * - Honest display numbers: required pace is rounded up, "reaches" is rounded down, and the status is
 *   decided from the same numbers that are shown, so the text and the verdict can never disagree.
 *
 * Pure Kotlin (java.time only): no Android, no database, no clock of its own.
 */
class PlannerError(message: String) : Exception(message)

/** A check-in row as the planner sees it. A row only exists when he answered. */
data class CheckIn(val day: LocalDate, val level: String, val amount: Double = 0.0, val reason: String? = null)

sealed class Schedule {
    /** daily, weekdays, a day list (Mon,Wed,Fri) or a range (Sat-Thu): fixed weekdays. */
    data class Days(val weekdays: Set<Int>) : Schedule()

    /** Nx/week: a count per Monday-to-Sunday week, with no fixed dates. */
    data class Count(val perWeek: Int) : Schedule()
}

data class WindowFacts(val answered: Int, val scheduled: Int?, val amount: Double, val done: Int)

data class PaceWindow(
    val answered: Int,
    val scheduled: Int?,
    val amount: Double,
    val done: Int,
    val pace: Double?,
    val answerRate: Double?,
)

/** The result of [Planner.project]. A null field is one the Python reference leaves out of its dict. */
data class Projection(
    val remaining: Double,
    val daysLeft: Int,
    val status: String,
    val requiredPerDay: Double? = null,
    val typicalPerDay: Double? = null,
    val reachesTypical: Double? = null,
    val shortTypical: Double? = null,
    val bestPerDay: Double? = null,
    val reachesBest: Double? = null,
    val shortBest: Double? = null,
)

/** One weekly review's figures, as the re-plan trigger reads them. */
data class ReviewPace(val projectedDone: Double, val remaining: Double, val answeredDays: Int)

data class ReviewOption(val date: String, val weekday: String, val after: String)

data class WeeklyFacts(
    val scheduled: Int?,
    val answered: Int,
    val done: Int,
    val missed: Int,
    val part: Int,
    val doneRate: Double?,
    val answerRate: Double?,
    val minimum: Int,
    val target: Int,
    val topReasons: List<Pair<String, Int>>,
    val missedByWeekday: Map<String, Int>,
)

object Planner {
    val DAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    val DONE_LEVELS = setOf("minimum", "target", "beyond")

    /** The only levels that may carry a reason chip: Not and Part. */
    val SHORT_LEVELS = setOf("not", "minimum")

    const val MIN_ANSWERED = 7
    const val WINDOW = 14
    const val REPLAN_RATIO = 0.85
    const val REASON_MIN_N = 3
    const val EPS = 1e-9

    private val DAY_NAME = DAYS.withIndex().associate { (i, n) -> n.lowercase() to i }
    private val DAY_RE = "(?:mon|tue|wed|thu|fri|sat|sun)"
    private val SCHEDULE_RE = Regex(
        "^(?:daily|weekdays|[1-7]x/week|$DAY_RE(?:,$DAY_RE)*|$DAY_RE-$DAY_RE)$",
        RegexOption.IGNORE_CASE,
    )

    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private val WEEKDAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

    fun roundHalfUp(x: Double, step: Double = 1.0): Double = floor(x / step + 0.5 + EPS) * step

    fun roundUp(x: Double, step: Double = 1.0): Double = ceil(x / step - EPS) * step

    fun roundDown(x: Double, step: Double = 1.0): Double = floor(x / step + EPS) * step

    /** Mon=0 ... Sun=6, like the Python reference. */
    private fun weekday(d: LocalDate): Int = d.dayOfWeek.value - 1

    /**
     * The local date a UTC instant belongs to, shifted by the day boundary hour.
     * The shift is wall-clock arithmetic, so a clock change never moves a result to another date.
     */
    fun dayKey(utcIso: String, tz: String, boundaryHour: Int = 4): LocalDate {
        if (boundaryHour < 0 || boundaryHour > 23) throw PlannerError("boundary hour must be 0 to 23")
        val instant = try {
            Instant.parse(utcIso)
        } catch (e: Exception) {
            try {
                OffsetDateTime.parse(utcIso).toInstant()
            } catch (e2: Exception) {
                throw PlannerError("not a UTC time stamp")
            }
        }
        val local = instant.atZone(ZoneId.of(tz)).toLocalDateTime()
        return local.minusHours(boundaryHour.toLong()).toLocalDate()
    }

    /** The check-in level for an amount. Rest is chosen by him, never derived. */
    fun levelFor(amount: Double, minimum: Double, target: Double): String {
        if (amount < 0 || target <= 0 || minimum < 0 || minimum > target) {
            throw PlannerError("bad amount or levels")
        }
        if (amount > target + EPS) return "beyond"
        if (amount >= target - EPS) return "target"
        if (amount > EPS && amount >= minimum - EPS) return "minimum"
        return "not"
    }

    fun isSchedule(s: String?): Boolean = s != null && SCHEDULE_RE.matches(s.trim())

    /** The one schedule grammar, shared with the card parser. Anything else is rejected, never guessed. */
    fun parseSchedule(s: String?): Schedule {
        if (!isSchedule(s)) throw PlannerError("bad schedule")
        val low = s!!.trim().lowercase()
        if (low == "daily") return Schedule.Days((0..6).toSet())
        if (low == "weekdays") return Schedule.Days((0..4).toSet())
        if (low.endsWith("x/week")) return Schedule.Count(low.dropLast(6).toInt())
        if (low.contains("-")) {
            val (a, b) = low.split("-")
            val i = DAY_NAME.getValue(a)
            val j = DAY_NAME.getValue(b)
            if (i == j) throw PlannerError("a range needs two different days")
            val out = mutableListOf<Int>()
            var k = i
            while (true) {
                out.add(k)
                if (k == j) break
                k = (k + 1) % 7
            }
            return Schedule.Days(out.toSet())
        }
        return Schedule.Days(low.split(",").map { DAY_NAME.getValue(it) }.toSet())
    }

    /** Scheduled dates in [start, end] for a day-based schedule, minus planned rest dates. */
    fun scheduledDates(
        schedule: String,
        start: LocalDate,
        end: LocalDate,
        restDates: Set<LocalDate> = emptySet(),
    ): List<LocalDate> {
        val parsed = parseSchedule(schedule)
        if (parsed !is Schedule.Days) throw PlannerError("count schedules have no fixed dates")
        val out = mutableListOf<LocalDate>()
        var cur = start
        while (!cur.isAfter(end)) {
            if (weekday(cur) in parsed.weekdays && cur !in restDates) out.add(cur)
            cur = cur.plusDays(1)
        }
        return out
    }

    /**
     * Number of scheduled sessions in [start, end].
     * For Nx/week: per Monday-to-Sunday week, min(N, days of that week inside the range).
     */
    fun scheduledCount(
        schedule: String,
        start: LocalDate,
        end: LocalDate,
        restDates: Set<LocalDate> = emptySet(),
    ): Int {
        val parsed = parseSchedule(schedule)
        if (end.isBefore(start)) return 0
        if (parsed is Schedule.Days) return scheduledDates(schedule, start, end, restDates).size
        val perWeek = (parsed as Schedule.Count).perWeek
        val weeks = HashMap<LocalDate, Int>()
        var cur = start
        while (!cur.isAfter(end)) {
            if (cur !in restDates) {
                val monday = cur.minusDays(weekday(cur).toLong())
                weeks[monday] = (weeks[monday] ?: 0) + 1
            }
            cur = cur.plusDays(1)
        }
        return weeks.values.sumOf { minOf(perWeek, it) }
    }

    /** Scheduled sessions from today (only when today is not answered yet) through the deadline, inclusive. */
    fun daysLeft(
        schedule: String,
        today: LocalDate,
        deadline: LocalDate,
        todayAnswered: Boolean = false,
        restDates: Set<LocalDate> = emptySet(),
    ): Int = scheduledCount(schedule, if (todayAnswered) today.plusDays(1) else today, deadline, restDates)

    private fun window(
        checkins: List<CheckIn>,
        schedule: String,
        first: LocalDate,
        last: LocalDate,
        restDates: Set<LocalDate>,
    ): WindowFacts {
        val parsed = parseSchedule(schedule)
        val sched = if (parsed is Schedule.Days) scheduledDates(schedule, first, last, restDates).toSet() else null
        val answered = checkins.filter {
            !it.day.isBefore(first) && !it.day.isAfter(last) && it.level != "rest" &&
                (sched == null || it.day in sched)
        }
        return WindowFacts(
            answered = answered.size,
            scheduled = sched?.size,
            amount = answered.sumOf { it.amount },
            done = answered.count { it.level in DONE_LEVELS },
        )
    }

    /**
     * Pace over the 14 days before today. Null pace when fewer than [MIN_ANSWERED] answered scheduled days.
     * With [start] (the first day it was tracked) the window never reaches before it: days before a plan existed
     * are not unanswered days. A plan that starts today or later has no window, so no answer rate yet.
     */
    fun typicalPace(
        checkins: List<CheckIn>,
        schedule: String,
        today: LocalDate,
        restDates: Set<LocalDate> = emptySet(),
        start: LocalDate? = null,
    ): PaceWindow {
        var first = today.minusDays(WINDOW.toLong())
        if (start != null && start.isAfter(first)) first = start
        val last = today.minusDays(1)
        if (first.isAfter(last)) {
            return PaceWindow(answered = 0, scheduled = 0, amount = 0.0, done = 0, pace = null, answerRate = null)
        }
        val w = window(checkins, schedule, first, last, restDates)
        return PaceWindow(
            answered = w.answered,
            scheduled = w.scheduled,
            amount = w.amount,
            done = w.done,
            pace = if (w.answered >= MIN_ANSWERED) w.amount / w.answered else null,
            answerRate = w.scheduled?.takeIf { it > 0 }?.let { roundHalfUp(100.0 * w.answered / it) },
        )
    }

    /**
     * Best pace over any 14-day window that ends before today and holds at least [MIN_ANSWERED] answered days.
     * The typical window is always one of the candidates, so best is never below typical when both exist.
     */
    fun bestPace(
        all: List<CheckIn>,
        schedule: String,
        today: LocalDate,
        restDates: Set<LocalDate> = emptySet(),
        start: LocalDate? = null,
    ): Double? {
        // Check-ins from before the start are not counted, the same as in typicalPace.
        val checkins = if (start == null) all else all.filter { !it.day.isBefore(start) }
        val days = checkins.map { it.day }.filter { it.isBefore(today) }
        if (days.isEmpty()) return null
        val lastEnd = today.minusDays(1)
        var end = minOf(days.min().plusDays((WINDOW - 1).toLong()), lastEnd)
        var best: Double? = null
        while (!end.isAfter(lastEnd)) {
            val w = window(checkins, schedule, end.minusDays((WINDOW - 1).toLong()), end, restDates)
            if (w.answered >= MIN_ANSWERED) {
                val p = w.amount / w.answered
                if (best == null || p > best) best = p
            }
            end = end.plusDays(1)
        }
        return best
    }

    private fun reach(pace: Double, left: Int, remaining: Double, step: Double): Double {
        val raw = pace * left
        if (raw >= remaining - EPS) return remaining
        return minOf(remaining, roundDown(raw, step))
    }

    /** Where the backlog ends up at the typical and the best pace, never rounded in the plan's favour. */
    fun project(remaining: Double, left: Int, typical: Double?, best: Double?, step: Double = 1.0): Projection {
        if (remaining <= 0) return Projection(remaining, left, "done")
        if (left <= 0) return Projection(remaining, left, "past_deadline")
        val required = remaining / left
        if (typical == null) return Projection(remaining, left, "no_data", requiredPerDay = required)
        val reachT = reach(typical, left, remaining, step)
        var reachB: Double? = null
        if (best != null) reachB = reach(best, left, remaining, step)
        val status = when {
            reachT >= remaining -> "ok"
            reachB != null && reachB >= remaining -> "needs_best"
            else -> "short"
        }
        return Projection(
            remaining = remaining,
            daysLeft = left,
            status = status,
            requiredPerDay = required,
            typicalPerDay = typical,
            reachesTypical = reachT,
            shortTypical = remaining - reachT,
            bestPerDay = best,
            reachesBest = reachB,
            shortBest = reachB?.let { remaining - it },
        )
    }

    /** True when the last two weekly reviews both had enough data and both projected below 85% of what remained. */
    fun replanFlag(reviews: List<ReviewPace>): Boolean {
        if (reviews.size < 2) return false
        return reviews.takeLast(2).all {
            it.answeredDays >= MIN_ANSWERED && it.projectedDone < REPLAN_RATIO * it.remaining
        }
    }

    /**
     * The first scheduled day on or after today+7 and today+14, as the coach may offer them.
     * English names come from fixed tables, never from the phone's locale.
     */
    fun reviewOptions(
        schedule: String,
        today: LocalDate,
        restDates: Set<LocalDate> = emptySet(),
    ): List<ReviewOption> {
        val parsed = parseSchedule(schedule)
        val out = mutableListOf<ReviewOption>()
        for (weeks in 1..2) {
            var cur = today.plusDays(7L * weeks)
            if (parsed is Schedule.Days) {
                var found = false
                for (step in 0 until 366) {
                    if (weekday(cur) in parsed.weekdays && cur !in restDates) { found = true; break }
                    cur = cur.plusDays(1)
                }
                if (!found) throw PlannerError("no scheduled day within a year")
            }
            out.add(
                ReviewOption(
                    date = "${cur.dayOfMonth} ${MONTHS[cur.monthValue - 1]}",
                    weekday = WEEKDAYS[weekday(cur)],
                    after = if (weeks == 1) "after one week" else "after two weeks",
                ),
            )
        }
        return out
    }

    /** Facts for the Monday-to-Sunday week starting [weekStart] (which must be a Monday). */
    fun weeklyFacts(
        checkins: List<CheckIn>,
        schedule: String,
        weekStart: LocalDate,
        restDates: Set<LocalDate> = emptySet(),
    ): WeeklyFacts {
        if (weekday(weekStart) != 0) throw PlannerError("week must start on a Monday")
        val weekEnd = weekStart.plusDays(6)
        val parsed = parseSchedule(schedule)
        val w = window(checkins, schedule, weekStart, weekEnd, restDates)
        var rows = checkins.filter {
            !it.day.isBefore(weekStart) && !it.day.isAfter(weekEnd) && it.level != "rest"
        }
        if (parsed is Schedule.Days) {
            val sched = scheduledDates(schedule, weekStart, weekEnd, restDates).toSet()
            rows = rows.filter { it.day in sched }
        }
        val missed = rows.filter { it.level == "not" }
        val short = rows.filter { it.level in SHORT_LEVELS }
        val reasons = short.mapNotNull { it.reason }.groupingBy { it }.eachCount()
        val byWeekday = missed.map { DAYS[weekday(it.day)] }.groupingBy { it }.eachCount()
        val levels = rows.groupingBy { it.level }.eachCount()
        val minimum = levels["minimum"] ?: 0
        return WeeklyFacts(
            scheduled = w.scheduled,
            answered = w.answered,
            done = w.done,
            missed = missed.size,
            part = minimum,
            doneRate = if (w.answered > 0) roundHalfUp(100.0 * w.done / w.answered) else null,
            answerRate = w.scheduled?.takeIf { it > 0 }?.let { roundHalfUp(100.0 * w.answered / it) },
            minimum = minimum,
            target = (levels["target"] ?: 0) + (levels["beyond"] ?: 0),
            // ties broken by name, so this order matches the Python reference exactly
            topReasons = reasons.entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .take(3)
                .map { it.key to it.value },
            missedByWeekday = byWeekday.toSortedMap(),
        )
    }

    /**
     * Only reasons with at least [REASON_MIN_N] items behind them are ever shown to the coach.
     * [shortDays] = days marked Not or Part, so a count can never exceed it.
     */
    fun reasonLines(reasons: List<Pair<String, Int>>, shortDays: Int): List<String> =
        reasons.filter { (_, n) -> n >= REASON_MIN_N && n <= shortDays }
            .map { (r, n) -> "$r on $n of $shortDays days marked Not or Part" }

    internal fun almostEqual(a: Double, b: Double) = abs(a - b) < EPS
}
