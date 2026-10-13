package com.clarity.life.core

import java.time.LocalDate

/**
 * PLAN_STATE and CHECKIN_FACTS: the planner facts the coach reads with every message (contracts/context.md,
 * "PLAN_STATE line formats"). Every number here comes from [Planner] and every line from [PlanStateText], so
 * the coach only ever copies. Both blocks are volatile (rebuilt per message) and never trimmed.
 *
 * Ids are the database ids with a letter in front (G2 = goal 2, T3 = target 3, B1 = backlog 1), so an id the
 * coach copies into a card names exactly one row, and [known] lists exactly the ids sent.
 *
 * Pure Kotlin: the caller reads the rows and passes today's day key.
 */
object PlanBlocks {

    data class Goal(val id: Long, val title: String, val deadline: LocalDate?)

    data class Target(
        val id: Long,
        val name: String,
        val goalId: Long?,
        val backlogId: Long?,
        val minimum: Double,
        val target: Double,
        val schedule: String,
        val checkins: List<CheckIn>,
        /** First day it is tracked; before it, it has no "today" line. */
        val start: LocalDate? = null,
    )

    data class Backlog(
        val id: Long,
        val name: String,
        val goalId: Long?,
        val total: Double,
        val startDone: Double,
        val deadline: LocalDate,
        /** His day key when the backlog was made. Work before it is in [startDone], so it is not counted twice. */
        val createdDay: LocalDate,
        /** Check-ins of its paused or ended targets: work already done toward it, though no longer tracked. */
        val doneElsewhere: List<CheckIn> = emptyList(),
    )

    /** Everything active in the tracker, as the database holds it today. */
    data class Tracker(
        val goals: List<Goal>,
        val targets: List<Target>,
        val backlogs: List<Backlog>,
        val restDays: Set<LocalDate>,
        val availability: String = "not set",
        val replanFlag: Boolean = false,
    )

    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private val DAY_NAMES = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

    /** "20 Nov", the way PLAN_STATE and the review options write a date. */
    fun shortDate(d: LocalDate): String = "${d.dayOfMonth} ${MONTHS[d.monthValue - 1]}"

    /** A name from him or the model, kept on one line and inside its quotation marks. */
    fun clean(s: String): String = s.replace('"', '\'').replace(Regex("\\s+"), " ").trim()

    /**
     * The days the coach may offer as a review: those of the active day-based targets together (a review on a
     * day at least one of them is scheduled), or every day when there are none.
     */
    fun reviewSchedule(targets: List<Target>): String {
        val days = sortedSetOf<Int>()
        for (t in targets) {
            val s = Planner.parseSchedule(t.schedule)
            if (s is Schedule.Days) days.addAll(s.weekdays)
        }
        if (days.isEmpty() || days.size == 7) return "daily"
        return days.joinToString(",") { Planner.DAYS[it] }
    }

    fun reviewOptions(t: Tracker, today: LocalDate): List<ReviewOption> =
        Planner.reviewOptions(reviewSchedule(t.targets), today, t.restDays)

    /** The ids and review options sent, so the card parser can reject anything invented. */
    fun known(t: Tracker, today: LocalDate, focuses: Set<String> = emptySet()): KnownIds = KnownIds(
        goals = t.goals.map { "G${it.id}" }.toSet(),
        targets = t.targets.map { "T${it.id}" }.toSet(),
        backlogs = t.backlogs.map { "B${it.id}" }.toSet(),
        focuses = focuses,
        reviewOptions = reviewOptions(t, today).map { it.date }.toSet(),
    )

    /**
     * A backlog's progress comes from the targets linked to it. With more than one, their answers are added up
     * per day and the first target's schedule counts the days; with none, nothing is tracked yet.
     * Done = start_done + the amounts entered from the backlog's creation day on; the pace uses all history.
     */
    data class BacklogFacts(
        val done: Double,
        val schedule: String,
        val projection: Projection,
        val windowAnswered: Int,
        val answerRate: Double?,
    )

    fun backlogFacts(b: Backlog, linked: List<Target>, today: LocalDate, rest: Set<LocalDate>): BacklogFacts {
        val schedule = linked.firstOrNull()?.schedule ?: "daily"
        val merged = mergeByDay(linked.flatMap { it.checkins })
        val done = b.startDone + mergeByDay(linked.flatMap { it.checkins } + b.doneElsewhere)
            .filter { !it.day.isBefore(b.createdDay) }.sumOf { it.amount }
        val remaining = b.total - done
        val todayAnswered = merged.any { it.day == today }
        val left = Planner.daysLeft(schedule, today, b.deadline, todayAnswered, rest)
        // Pace counts only from the day tracking began, so a new plan never shows days before it as unanswered.
        val start = linked.mapNotNull { it.start }.minOrNull() ?: b.createdDay
        val typical = Planner.typicalPace(merged, schedule, today, rest, start)
        val best = Planner.bestPace(merged, schedule, today, rest, start)
        return BacklogFacts(
            done = done,
            schedule = schedule,
            projection = Planner.project(remaining, left, typical.pace, best),
            windowAnswered = typical.answered,
            answerRate = typical.answerRate,
        )
    }

    /** One row per day: amounts added, done when any was done, rest only when every answer was rest. */
    fun mergeByDay(rows: List<CheckIn>): List<CheckIn> = rows.groupBy { it.day }.map { (day, list) ->
        val level = when {
            list.all { it.level == "rest" } -> "rest"
            list.any { it.level in Planner.DONE_LEVELS } -> list.filter { it.level in Planner.DONE_LEVELS }
                .maxBy { LEVEL_ORDER.indexOf(it.level) }.level
            else -> "not"
        }
        CheckIn(day, level, list.filter { it.level != "rest" }.sumOf { it.amount }, list.firstNotNullOfOrNull { it.reason })
    }.sortedBy { it.day }

    private val LEVEL_ORDER = listOf("rest", "not", "minimum", "target", "beyond")

    /** PLAN_STATE: goals with their backlogs and targets, then the loose ones, the review options, the flag. */
    fun planState(t: Tracker, today: LocalDate): Block {
        val lines = mutableListOf<String>()
        fun backlogLines(b: Backlog) {
            val linked = t.targets.filter { it.backlogId == b.id }
            val f = backlogFacts(b, linked, today, t.restDays)
            lines += PlanStateText.backlogLines(
                "B${b.id}", clean(b.name), b.total, f.done, f.schedule, f.projection, f.windowAnswered,
                f.answerRate, t.availability,
            )
        }
        fun targetLine(x: Target) {
            val w = Planner.typicalPace(x.checkins, x.schedule, today, t.restDays, x.start)
            val owner = x.goalId?.let { "G$it" } ?: "no goal"
            lines += PlanStateText.targetLine(
                "T${x.id}", clean(x.name), owner, x.minimum, x.target, x.schedule, w.done, w.answered,
            )
        }
        for (g in t.goals) {
            lines += if (g.deadline != null) {
                PlanStateText.goalLine("G${g.id}", clean(g.title), shortDate(g.deadline))
            } else {
                "Goal G${g.id} \"${clean(g.title)}\", no deadline."
            }
            t.backlogs.filter { it.goalId == g.id }.forEach { backlogLines(it) }
            t.targets.filter { it.goalId == g.id }.forEach { targetLine(it) }
        }
        val goalIds = t.goals.map { it.id }.toSet()
        t.backlogs.filter { it.goalId == null || it.goalId !in goalIds }.forEach { backlogLines(it) }
        t.targets.filter { it.goalId == null || it.goalId !in goalIds }.forEach { targetLine(it) }
        if (lines.isEmpty()) lines += "No goals, targets or backlogs yet."
        lines += PlanStateText.reviewOptionsLine(reviewOptions(t, today))
        if (t.replanFlag) lines += "Re-plan flag: yes (below pace in two reviews in a row)."
        return Block("PLAN_STATE", lines.map { BlockItem(it, 0) })
    }

    /**
     * CHECKIN_FACTS: counted here, never by the model. Today's state per target, then per target over the
     * last 14 days: answers, done, and the reasons and weekdays behind Not and Part. Only lines with at least
     * [Planner.REASON_MIN_N] items behind a number are written. Omitted when there are no targets.
     */
    fun checkinFacts(t: Tracker, today: LocalDate): Block {
        val lines = mutableListOf<String>()
        if (t.targets.isEmpty()) return Block("CHECKIN_FACTS", emptyList())
        if (today in t.restDays) {
            lines += "Today (${DAY_NAMES[today.dayOfWeek.value - 1]}) is a planned rest day."
        } else {
            val parts = t.targets.mapNotNull { x -> todayState(x, today)?.let { "T${x.id} \"${clean(x.name)}\" $it" } }
            if (parts.isNotEmpty()) lines += "Today (${DAY_NAMES[today.dayOfWeek.value - 1]}): " + parts.joinToString("; ") + "."
        }
        val first = today.minusDays(Planner.WINDOW.toLong())
        val last = today.minusDays(1)
        for (x in t.targets) {
            val w = Planner.typicalPace(x.checkins, x.schedule, today, t.restDays, x.start)
            val head = "T${x.id} \"${clean(x.name)}\", last ${Planner.WINDOW} days"
            if (w.answered >= Planner.REASON_MIN_N) {
                val sched = if (w.scheduled != null) " of ${w.scheduled} scheduled" else ""
                lines += "$head: ${w.answered} answered$sched, done on ${w.done}."
            } else if (w.scheduled != null && w.scheduled >= Planner.REASON_MIN_N) {
                lines += "$head: ${w.answered} answered of ${w.scheduled} scheduled; too few answers to count."
            }
            val scheduled = scheduledIn(x, first, last, t.restDays)
            val rows = x.checkins.filter {
                !it.day.isBefore(first) && !it.day.isAfter(last) && it.level != "rest" &&
                    (scheduled == null || it.day in scheduled)
            }
            val short = rows.filter { it.level in Planner.SHORT_LEVELS }
            val reasons = short.mapNotNull { it.reason }.groupingBy { it }.eachCount()
                .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .take(3).map { it.key to it.value }
            Planner.reasonLines(reasons, short.size).forEach { lines += "$head: $it." }
            val missed = rows.filter { it.level == "not" }
            missed.groupingBy { it.day.dayOfWeek.value - 1 }.eachCount()
                .filter { it.value >= Planner.REASON_MIN_N }.toSortedMap()
                .forEach { (wd, n) -> lines += "$head: marked Not on $n ${DAY_NAMES[wd]}s." }
        }
        return Block("CHECKIN_FACTS", lines.map { BlockItem(it, 0) })
    }

    private fun scheduledIn(x: Target, first: LocalDate, last: LocalDate, rest: Set<LocalDate>): Set<LocalDate>? =
        if (Planner.parseSchedule(x.schedule) is Schedule.Days) {
            Planner.scheduledDates(x.schedule, first, last, rest).toSet()
        } else {
            null
        }

    /** "not answered yet", "done (target)", "marked Not, reason: tired", "rest", or null when not scheduled today. */
    fun todayState(x: Target, today: LocalDate): String? {
        if (x.start != null && today.isBefore(x.start)) return null
        val row = x.checkins.firstOrNull { it.day == today }
        val parsed = Planner.parseSchedule(x.schedule)
        if (row == null) {
            if (parsed is Schedule.Days && (today.dayOfWeek.value - 1) !in parsed.weekdays) return null
            return "not answered yet"
        }
        return when (row.level) {
            "rest" -> "rest"
            "not" -> "marked Not" + (row.reason?.let { ", reason: $it" } ?: "")
            "minimum" -> "marked Part (minimum)" + (row.reason?.let { ", reason: $it" } ?: "")
            else -> "done (${row.level})"
        }
    }
}
