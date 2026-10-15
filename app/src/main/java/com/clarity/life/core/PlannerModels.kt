package com.clarity.life.core

import java.time.LocalDate

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
