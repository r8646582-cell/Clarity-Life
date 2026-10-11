package com.clarity.life.core

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10

/**
 * The PLAN_STATE text the coach reads: written here, in code, so the coach only ever copies it
 * (contracts/context.md, "PLAN_STATE line formats"). A 1:1 port of the text section of tools/planner.py,
 * pinned by the backlog_lines cases in contracts/planner_vectors.json and by the samples inside
 * prompts/examples.md and prompts/testbench.md.
 *
 * Display rounding never flatters the plan: required pace is rounded up, "reaches" is rounded down,
 * and the Status line is decided from the same numbers that are shown.
 */
object PlanStateText {

    val STATUS_TEXT = mapOf(
        "ok" to "Status: reaches the total at the typical pace.",
        "needs_best" to "Status: reaches the total only at the best-fortnight pace, not at the typical pace.",
        "short" to "Status: does not reach the total, not even at the best-fortnight pace.",
        "short_no_best" to "Status: does not reach the total at the typical pace.",
    )

    /** Python's %g for the magnitudes this app deals in: 6 significant digits, trailing zeros stripped. */
    fun g(x: Double): String {
        if (x == 0.0) return "0"
        val a = abs(x)
        if (a >= 1e-4 && a < 1e6) {
            val digits = (6 - (floor(log10(a)).toInt() + 1)).coerceAtLeast(0)
            var s = BigDecimal(x).setScale(digits, RoundingMode.HALF_EVEN).toPlainString()
            if (s.contains('.')) s = s.trimEnd('0').trimEnd('.')
            return s
        }
        return BigDecimal(x).round(java.math.MathContext(6)).toString()
    }

    private fun fixed(x: Double, places: Int): String =
        BigDecimal(x).setScale(places, RoundingMode.HALF_EVEN).toPlainString()

    /** A per-day figure with one decimal; below 0.1 two decimals, so a slow pace never shows as 0.0. */
    private fun fmt(x: Double): String =
        if (x > 0 && x < 0.1 - Planner.EPS) fixed(x, 2) else fixed(x, 1)

    /** Typical and best pace: a description of the past, rounded half up. */
    fun num(x: Double): String =
        fmt(if (x > 0 && x < 0.1 - Planner.EPS) Planner.roundHalfUp(x, 0.01) else Planner.roundHalfUp(x, 0.1))

    /** Required pace: rounded up, so the shown pace really reaches the total. */
    fun numUp(x: Double): String =
        fmt(if (x > 0 && x < 0.1 - Planner.EPS) Planner.roundUp(x, 0.01) else Planner.roundUp(x, 0.1))

    fun offDaysPhrase(schedule: String): String {
        val parsed = Planner.parseSchedule(schedule)
        if (parsed !is Schedule.Days) return ""
        val off = (0..6).filter { it !in parsed.weekdays }
        if (off.isEmpty() || off.size > 3) return ""
        val names = listOf("Mondays", "Tuesdays", "Wednesdays", "Thursdays", "Fridays", "Saturdays", "Sundays")
        return " (" + off.map { names[it] }.joinToString(" and ") + " off)"
    }

    /**
     * The backlog block: the head line plus the pace, status and answer-rate lines.
     * [windowAnswered] and [answerRate] come from [Planner.typicalPace]; [availability] from his profile.
     */
    fun backlogLines(
        bid: String,
        name: String,
        total: Double,
        done: Double,
        schedule: String,
        p: Projection,
        windowAnswered: Int,
        answerRate: Double?,
        availability: String = "not set",
    ): List<String> {
        val remaining = total - done
        var head = "Backlog $bid \"$name\": total ${g(total)}, done ${g(done)}, remaining ${g(remaining)}"
        if (p.status == "done" || p.status == "past_deadline") {
            return listOf(head + if (p.status == "done") ", complete." else ", the deadline has passed.")
        }
        head += ", scheduled days left ${p.daysLeft}${offDaysPhrase(schedule)}, " +
            "required ${numUp(p.requiredPerDay!!)} per day."
        val lines = mutableListOf(head)
        if (p.status == "no_data") {
            lines.add(
                "  Not enough data yet: fewer than ${Planner.MIN_ANSWERED} answered days " +
                    "in the last ${Planner.WINDOW} days.",
            )
        } else {
            val tailT = if (p.shortTypical!! > 0) ", short by ${g(p.shortTypical)}." else "."
            lines.add(
                "  Typical pace (last ${Planner.WINDOW} days, $windowAnswered answered days): " +
                    "${num(p.typicalPerDay!!)} per day, reaches ${g(p.reachesTypical!!)} of ${g(remaining)}$tailT",
            )
            if (p.bestPerDay != null) {
                val tailB = if (p.shortBest!! > 0) ", short by ${g(p.shortBest)}." else "."
                lines.add(
                    "  Best fortnight so far: ${num(p.bestPerDay)} per day, " +
                        "reaches ${g(p.reachesBest!!)} of ${g(remaining)}$tailB",
                )
            }
            val key = if (p.status != "short" || p.bestPerDay != null) p.status else "short_no_best"
            lines.add("  " + STATUS_TEXT.getValue(key))
        }
        val ar = if (answerRate != null) "Answer rate ${answerRate.toInt()}%." else "Answer rate not tracked."
        lines.add("  $ar Availability: $availability.")
        return lines
    }

    fun reviewOptionsLine(options: List<ReviewOption>): String =
        "Review options: " + options.joinToString(", ") { "${it.date} (${it.weekday}, ${it.after})" } + "."

    fun goalLine(gid: String, title: String, deadline: String): String = "Goal $gid \"$title\", deadline $deadline."

    fun targetLine(
        tid: String,
        title: String,
        gid: String,
        minimum: Double,
        target: Double,
        schedule: String,
        done: Int,
        answered: Int,
    ): String = "Target $tid \"$title\" ($gid): minimum ${g(minimum)}, target ${g(target)}, $schedule. " +
        "Last ${Planner.WINDOW} days: done $done of $answered answered days."
}
