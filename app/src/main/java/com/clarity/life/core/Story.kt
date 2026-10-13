package com.clarity.life.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Your story (spec section 2.5): every number and date the page shows, counted here and never by the AI.
 * A 1:1 port of tools/story.py; StoryTest runs contracts/story_vectors.json.
 *
 * Story weeks run seven days from the backlog's own start day, so week one is the plan's first week. Past
 * [MAX_WEEK_BARS] weeks the chart shows calendar months. The plan line adds each step's target on each of its
 * scheduled days that is not a planned rest day, so a ramp of 2, then 3, then 4 a day draws as stairs.
 */
object Story {

    const val MAX_WEEK_BARS = 26
    const val FIRST_PAGES_DAYS = 28
    private val NUMBER_WORDS = listOf(
        "Zero", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten", "Eleven", "Twelve",
    )

    /** One bar of a goal's chart: what he did from [start] to [end] (both included) and what the plan asked for. */
    data class Bar(val start: LocalDate, val end: LocalDate, val done: Double, val planned: Double)

    data class Series(val by: String, val bars: List<Bar>)

    /** A day's amount, whatever its level (a rest day has amount 0). */
    data class Amount(val day: LocalDate, val amount: Double)

    enum class View(val label: String) { MONTH("This month"), YEAR("This year"), ALL("All of it") }

    /** The chart's stretches from [first] to [last]: story weeks, or calendar months past [MAX_WEEK_BARS]. */
    fun buckets(first: LocalDate, last: LocalDate): Pair<String, List<Pair<LocalDate, LocalDate>>> {
        if (last.isBefore(first)) return "week" to emptyList()
        val weeks = ChronoUnit.DAYS.between(first, last) / 7 + 1
        val out = mutableListOf<Pair<LocalDate, LocalDate>>()
        var cur = first
        if (weeks <= MAX_WEEK_BARS) {
            while (!cur.isAfter(last)) {
                out.add(cur to minOf(cur.plusDays(6), last))
                cur = cur.plusDays(7)
            }
            return "week" to out
        }
        while (!cur.isAfter(last)) {
            val next = cur.withDayOfMonth(1).plusMonths(1)
            out.add(cur to minOf(next.minusDays(1), last))
            cur = next
        }
        return "month" to out
    }

    private fun planned(steps: List<PlanStep>, a: LocalDate, b: LocalDate, rest: Set<LocalDate>): Double {
        var total = 0.0
        for (st in steps) {
            val parsed = Planner.parseSchedule(st.schedule)
            val lo = maxOf(a, st.start)
            val hi = if (st.end == null) b else minOf(b, st.end)
            if (hi.isBefore(lo)) continue
            when (parsed) {
                is Schedule.Days -> {
                    var cur = lo
                    while (!cur.isAfter(hi)) {
                        if (cur.dayOfWeek.value - 1 in parsed.weekdays && cur !in rest) total += st.target
                        cur = cur.plusDays(1)
                    }
                }
                is Schedule.Count -> {
                    // Per seven days counted from lo; a planned rest day is a day it cannot be done.
                    var cur = lo
                    while (!cur.isAfter(hi)) {
                        val end = minOf(cur.plusDays(6), hi)
                        val free = (0..ChronoUnit.DAYS.between(cur, end)).count { cur.plusDays(it) !in rest }
                        total += st.target * minOf(parsed.perWeek, free)
                        cur = cur.plusDays(7)
                    }
                }
            }
        }
        return total
    }

    /** The bars and the plan line for one backlog. [first] = its start day; [last] = today, or the day it was done. */
    fun series(
        first: LocalDate,
        last: LocalDate,
        steps: List<PlanStep>,
        amounts: List<Amount>,
        restDates: Set<LocalDate> = emptySet(),
    ): Series {
        steps.forEach { Planner.parseSchedule(it.schedule) } // a bad schedule fails here, never guessed
        val (by, spans) = buckets(first, last)
        return Series(
            by,
            spans.map { (a, b) ->
                val done = amounts.filter { !it.day.isBefore(a) && !it.day.isAfter(b) }.sumOf { it.amount }
                Bar(a, b, done, planned(steps, a, b, restDates))
            },
        )
    }

    /** The first day the running total reached [total], or null while it is not reached. */
    fun finishedOn(total: Double, startDone: Double, amounts: List<Amount>): LocalDate? {
        var run = startDone
        if (run >= total - Planner.EPS) return null
        for (a in amounts.sortedBy { it.day }) {
            run += a.amount
            if (run >= total - Planner.EPS) return a.day
        }
        return null
    }

    private fun monthsBetween(first: LocalDate, today: LocalDate): Int {
        var m = (today.year - first.year) * 12 + today.monthValue - first.monthValue
        if (today.dayOfMonth < first.dayOfMonth) m -= 1
        return m
    }

    private fun words(n: Int): String = NUMBER_WORDS.getOrNull(n) ?: n.toString()

    /** How long his story is: Day 3, Two weeks, Five months, Two years. */
    fun length(first: LocalDate, today: LocalDate): String {
        val days = ChronoUnit.DAYS.between(first, today) + 1
        if (days < 1) throw PlannerError("the story cannot start after today")
        if (days < 14) return "Day $days"
        if (days < 63) return "${words((days / 7).toInt())} weeks"
        val months = monthsBetween(first, today)
        if (months < 24) return if (months == 1) "One month" else "${words(months)} months"
        return "${words(months / 12)} years"
    }

    /** The first four weeks are the story's first pages. */
    fun headline(first: LocalDate, today: LocalDate): String =
        if (ChronoUnit.DAYS.between(first, today) + 1 < FIRST_PAGES_DAYS) "The first pages." else "Your story so far."

    /** The days a view covers, both included, never before the story began. */
    fun viewRange(view: View, first: LocalDate, today: LocalDate): Pair<LocalDate, LocalDate> {
        val a = when (view) {
            View.MONTH -> today.withDayOfMonth(1)
            View.YEAR -> LocalDate.of(today.year, 1, 1)
            View.ALL -> first
        }
        return maxOf(a, first) to today
    }

    /** The vectors name views as tools/story.py does. */
    fun view(name: String): View = when (name) {
        "month" -> View.MONTH
        "year" -> View.YEAR
        "all" -> View.ALL
        else -> throw PlannerError("unknown view")
    }
}
