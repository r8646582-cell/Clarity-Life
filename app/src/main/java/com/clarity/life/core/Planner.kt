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
/**
 * One answer. [minute]: his local time when he answered, minutes after midnight, only when he answered on the
 * day itself (schema 6); null when not known or given on a later day, which says nothing about the day's time.
 */
data class CheckIn(
    val day: LocalDate,
    val level: String,
    val amount: Double = 0.0,
    val reason: String? = null,
    val minute: Int? = null,
)

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
/** One step of a plan, as plan_finish reads it: a target on its schedule from [start] to [end] (null = open). */
data class PlanStep(val target: Double, val schedule: String, val start: LocalDate, val end: LocalDate?)

/** Where a plan's own steps finish a backlog (tools/planner.py, plan_finish). */
data class PlanFinish(
    val remaining: Double,
    val status: String,
    val reachesByDeadline: Double? = null,
    val finish: LocalDate? = null,
    val daysSpare: Int? = null,
)

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

/**
 * Schema 8: a target's values from [from] on (tools/planner.py, the target history section). [status] is active,
 * paused or ended.
 */
data class TargetVersion(
    val from: LocalDate,
    val minimum: Double,
    val target: Double,
    val schedule: String,
    val status: String,
)

/** A target as the plan history reads it: its dates and its versions, oldest first. */
data class TargetSpan(val start: LocalDate, val end: LocalDate?, val versions: List<TargetVersion>)

data class Planned(val asked: Double, val days: Int)

/** How far a plan's steps have got (plan_progress): [from]..[to], what they asked for and what was done. */
data class PlanProgress(val from: LocalDate, val to: LocalDate, val asked: Double, val done: Double, val days: Int)

data class ChangeSide(val answered: Int, val done: Int, val amount: Double)

/** The latest change to a target and how it went before and since (change_compare). */
data class ChangeCompare(
    val day: LocalDate,
    val fields: List<String>,
    val beforeDays: Int,
    val before: ChangeSide,
    val after: ChangeSide,
)

/** One 7-day stretch of a done-rate trend (done_trend). [rate] is null with fewer than 3 answered days. */
data class TrendWeek(val first: LocalDate, val last: LocalDate, val answered: Int, val done: Int, val rate: Double?)

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
     * A stored day key ("2026-10-14") as a date. The same as LocalDate.parse, read directly (Max plan Phase 5):
     * the general parser cost about a second for ten years of check-ins on a slower phone. Anything else goes
     * to LocalDate.parse, so a bad key fails exactly as before.
     */
    fun day(key: String): LocalDate {
        if (key.length == 10 && key[4] == '-' && key[7] == '-') {
            var ok = true
            for (i in 0 until 10) if (i != 4 && i != 7 && key[i] !in '0'..'9') ok = false
            if (ok) {
                val y = key.substring(0, 4).toInt()
                val m = key.substring(5, 7).toInt()
                val d = key.substring(8, 10).toInt()
                if (m in 1..12 && d in 1..java.time.YearMonth.of(y, m).lengthOfMonth()) return LocalDate.of(y, m, d)
            }
        }
        return LocalDate.parse(key)
    }

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
        // Each window reads only the check-ins inside it (Max plan Phase 5): ten years of daily answers made this
        // about 13 million steps. The ones inside are counted in their given order, as [window] counts them, so
        // every sum is the same to the last digit (BestPaceTest compares it with the plain way).
        val inOrder = checkins.zipWithNext().all { (a, b) -> !b.day.isBefore(a.day) }
        val byDay = if (inOrder) checkins.withIndex().toList() else checkins.withIndex().sortedBy { it.value.day }
        val sortedDays = byDay.map { it.value.day }
        val weekdays = (parseSchedule(schedule) as? Schedule.Days)?.weekdays
        while (!end.isAfter(lastEnd)) {
            val first = end.minusDays((WINDOW - 1).toLong())
            var inside: List<IndexedValue<CheckIn>> =
                byDay.subList(firstAtOrAfter(sortedDays, first), firstAtOrAfter(sortedDays, end.plusDays(1)))
            if (!inOrder) inside = inside.sortedBy { it.index }
            // As [window]: not a rest answer, and on a scheduled day (a fixed weekday that is not a rest day).
            var answered = 0
            var amount = 0.0
            for ((_, c) in inside) {
                if (c.level == "rest") continue
                if (weekdays != null && (weekday(c.day) !in weekdays || c.day in restDates)) continue
                answered++
                amount += c.amount
            }
            if (answered >= MIN_ANSWERED) {
                val p = amount / answered
                if (best == null || p > best) best = p
            }
            end = end.plusDays(1)
        }
        return best
    }

    /** The position of the first day in [sorted] on or after [day] ([sorted].size when there is none). */
    private fun firstAtOrAfter(sorted: List<LocalDate>, day: LocalDate): Int {
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid].isBefore(day)) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun reach(pace: Double, left: Int, remaining: Double, step: Double): Double {
        val raw = pace * left
        if (raw >= remaining - EPS) return remaining
        return minOf(remaining, roundDown(raw, step))
    }

    /** A plan that has not finished a year after its deadline is reported as not finishing. */
    const val FINISH_HORIZON = 366L

    /**
     * Where the plan's own steps finish a backlog, counted day by day: each step adds its target on every
     * scheduled day from its start to its end (null = open). Counting starts today, or tomorrow when today is
     * already answered. Overlapping steps add up; rest dates add nothing. An Nx/week step has no fixed days, so
     * the plan cannot be dated ("count_schedule"). Statuses: done, finishes, late, never. A 1:1 port of
     * tools/planner.py plan_finish.
     */
    fun planFinish(
        steps: List<PlanStep>,
        remaining: Double,
        today: LocalDate,
        deadline: LocalDate,
        todayAnswered: Boolean = false,
        restDates: Set<LocalDate> = emptySet(),
    ): PlanFinish {
        if (remaining <= EPS) return PlanFinish(remaining, "done")
        if (steps.isEmpty()) return PlanFinish(remaining, "never")
        val days = steps.map { st ->
            when (val s = parseSchedule(st.schedule)) {
                is Schedule.Days -> s.weekdays
                is Schedule.Count -> return PlanFinish(remaining, "count_schedule")
            }
        }
        var cur = if (todayAnswered) today.plusDays(1) else today
        val last = deadline.plusDays(FINISH_HORIZON)
        var total = 0.0
        var byDeadline: Double? = null
        var finish: LocalDate? = null
        while (!cur.isAfter(last)) {
            if (cur !in restDates) {
                for ((i, st) in steps.withIndex()) {
                    if (!st.start.isAfter(cur) && (st.end == null || !cur.isAfter(st.end)) && weekday(cur) in days[i]) {
                        total += st.target
                    }
                }
            }
            if (cur == deadline) byDeadline = total
            if (total >= remaining - EPS) {
                finish = cur
                break
            }
            if (steps.all { it.end != null && it.end.isBefore(cur) }) break // every step has ended short of the total
            cur = cur.plusDays(1)
        }
        // Never reached the deadline day in the loop: counting began after it (its day is answered), so nothing
        // more lands by it; or it stopped before it, finished early or with every step ended.
        val atDeadline = byDeadline ?: if ((if (todayAnswered) today.plusDays(1) else today).isAfter(deadline)) 0.0 else total
        val reaches = if (atDeadline < remaining - EPS) minOf(remaining, roundDown(atDeadline)) else remaining
        if (finish == null) return PlanFinish(remaining, "never", reaches)
        val spare = java.time.temporal.ChronoUnit.DAYS.between(finish, deadline).toInt()
        return PlanFinish(remaining, if (!finish.isAfter(deadline)) "finishes" else "late", reaches, finish, spare)
    }

    /**
     * Where the backlog ends up at the typical and the best pace, never rounded in the plan's favour.
     * With no scheduled day left: past_deadline, or no_days_left when [deadlinePassed] is false (the deadline is
     * today and answered, or no scheduled day falls before it), so the deadline day never reads as passed.
     * With no typical pace (after a gap) the best fortnight is still given; the status stays no_data.
     */
    fun project(
        remaining: Double,
        left: Int,
        typical: Double?,
        best: Double?,
        step: Double = 1.0,
        deadlinePassed: Boolean? = null,
    ): Projection {
        if (remaining <= 0) return Projection(remaining, left, "done")
        if (left <= 0) return Projection(remaining, left, if (deadlinePassed == false) "no_days_left" else "past_deadline")
        val required = remaining / left
        if (typical == null) {
            val reachB = best?.let { reach(it, left, remaining, step) }
            return Projection(
                remaining, left, "no_data", requiredPerDay = required,
                bestPerDay = best, reachesBest = reachB, shortBest = reachB?.let { remaining - it },
            )
        }
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

    /**
     * The days a backlog is worked on, from the schedules of its live linked targets (tools/planner.py
     * backlog_schedule): one distinct schedule as written; several with fixed days, the days of any of them
     * (daily when every day); any Nx/week among them, a count of all their sessions a week, at most 7; none, daily.
     */
    fun backlogSchedule(schedules: List<String>): String {
        val distinct = schedules.distinct()
        if (distinct.isEmpty()) return "daily"
        if (distinct.size == 1) return distinct[0]
        val parsed = distinct.map { parseSchedule(it) }
        if (parsed.all { it is Schedule.Days }) {
            val days = parsed.flatMap { (it as Schedule.Days).weekdays }.toSortedSet()
            return if (days.size == 7) "daily" else days.joinToString(",") { DAYS[it] }
        }
        val n = parsed.sumOf {
            when (it) {
                is Schedule.Days -> it.weekdays.size
                is Schedule.Count -> it.perWeek
            }
        }
        return "${minOf(7, n)}x/week"
    }

    // ---------- target history (schema 8): what a plan asked for, and before/after a change ----------

    /** The version in force on [day], or null before the first one. */
    fun versionOn(versions: List<TargetVersion>, day: LocalDate): TargetVersion? {
        var out: TargetVersion? = null
        for (v in versions) {
            if (!v.from.isAfter(day)) out = v else break
        }
        return out
    }

    /**
     * What the plan asked for from [first] to [last]: on every day that is not a planned rest day, each target
     * adds the target amount in force that day when it is within its start and end, active then, and scheduled
     * then. Null when a version in force on one of those days has an Nx/week schedule (no fixed days).
     */
    fun plannedAmount(
        targets: List<TargetSpan>,
        first: LocalDate,
        last: LocalDate,
        restDates: Set<LocalDate> = emptySet(),
    ): Planned? {
        var asked = 0.0
        val days = mutableSetOf<LocalDate>()
        var cur = first
        while (!cur.isAfter(last)) {
            if (cur !in restDates) {
                for (t in targets) {
                    if (t.start.isAfter(cur) || (t.end != null && t.end.isBefore(cur))) continue
                    val v = versionOn(t.versions, cur)
                    if (v == null || v.status != "active") continue
                    val s = parseSchedule(v.schedule) as? Schedule.Days ?: return null
                    if (weekday(cur) in s.weekdays) {
                        asked += v.target
                        days += cur
                    }
                }
            }
            cur = cur.plusDays(1)
        }
        return Planned(asked, days.size)
    }

    /**
     * How far the plan's steps have got: from the first step's start to yesterday (today when answered), what
     * they asked for against every amount entered on those days. Null before a scheduled day has passed, or
     * when it cannot be counted.
     */
    fun planProgress(
        targets: List<TargetSpan>,
        checkins: List<CheckIn>,
        today: LocalDate,
        todayAnswered: Boolean = false,
        restDates: Set<LocalDate> = emptySet(),
    ): PlanProgress? {
        if (targets.isEmpty()) return null
        val first = targets.minOf { it.start }
        val last = if (todayAnswered) today else today.minusDays(1)
        if (last.isBefore(first)) return null
        val p = plannedAmount(targets, first, last, restDates) ?: return null
        if (p.days == 0) return null
        val done = checkins.filter { !it.day.isBefore(first) && !it.day.isAfter(last) && it.level != "rest" }.sumOf { it.amount }
        return PlanProgress(first, last, p.asked, done, p.days)
    }

    /** A change older than this many days is no longer news. */
    const val CHANGE_LOOKBACK = 90L

    private fun changedFields(a: TargetVersion, b: TargetVersion): List<String> = buildList {
        if (abs(a.target - b.target) > EPS) add("target ${PlanStateText.g(a.target)} \u2192 ${PlanStateText.g(b.target)}")
        if (abs(a.minimum - b.minimum) > EPS) add("minimum ${PlanStateText.g(a.minimum)} \u2192 ${PlanStateText.g(b.minimum)}")
        if (a.schedule != b.schedule) add("${a.schedule} \u2192 ${b.schedule}")
    }

    private fun side(checkins: List<CheckIn>, schedule: String, first: LocalDate, last: LocalDate, rest: Set<LocalDate>): ChangeSide {
        if (first.isAfter(last)) return ChangeSide(0, 0, 0.0)
        val w = window(checkins, schedule, first, last, rest)
        return ChangeSide(w.answered, w.done, w.amount)
    }

    /**
     * The latest change to a target's numbers or schedule in the last [CHANGE_LOOKBACK] days, with how it went
     * in the [WINDOW] days before (old schedule) and since (new schedule, up to yesterday). Null when there is
     * none, or fewer than [REASON_MIN_N] answered days on either side. A pause or resume is not a change.
     */
    fun changeCompare(
        checkins: List<CheckIn>,
        versions: List<TargetVersion>,
        start: LocalDate,
        today: LocalDate,
        restDates: Set<LocalDate> = emptySet(),
    ): ChangeCompare? {
        for (i in versions.indices.reversed()) {
            if (i == 0) break
            val before = versions[i - 1]
            val after = versions[i]
            val fields = changedFields(before, after)
            if (fields.isEmpty()) continue
            val day = after.from
            if (!day.isBefore(today) || day.isBefore(today.minusDays(CHANGE_LOOKBACK))) return null
            val bFirst = maxOf(day.minusDays(WINDOW.toLong()), start)
            val b = side(checkins, before.schedule, bFirst, day.minusDays(1), restDates)
            val a = side(checkins, after.schedule, day, today.minusDays(1), restDates)
            if (b.answered < REASON_MIN_N || a.answered < REASON_MIN_N) return null
            return ChangeCompare(day, fields, java.time.temporal.ChronoUnit.DAYS.between(bFirst, day).toInt(), b, a)
        }
        return null
    }

    const val TREND_WEEKS = 4

    /**
     * The done rate of each of the last [weeks] 7-day stretches ending on [last], oldest first (spec 6.2, the
     * 28-day trend per target; rest days left out). A rate needs [REASON_MIN_N] answered scheduled days; days
     * before [start] are not part of a stretch.
     */
    fun doneTrend(
        checkins: List<CheckIn>,
        schedule: String,
        last: LocalDate,
        restDates: Set<LocalDate> = emptySet(),
        weeks: Int = TREND_WEEKS,
        start: LocalDate? = null,
    ): List<TrendWeek> = (weeks - 1 downTo 0).map { k ->
        val b = last.minusDays(7L * k)
        var a = b.minusDays(6)
        if (start != null && start.isAfter(a)) a = start
        if (a.isAfter(b)) {
            TrendWeek(b.minusDays(6), b, 0, 0, null)
        } else {
            val w = window(checkins, schedule, a, b, restDates)
            TrendWeek(a, b, w.answered, w.done, if (w.answered >= REASON_MIN_N) roundHalfUp(100.0 * w.done / w.answered) else null)
        }
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
