package com.clarity.life.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * What the morning brief is written from (spec section 8, prompts/brief_daily.md), decided in code. Every line
 * here is a finished fact: the coach copies, it never counts.
 *
 * Pure Kotlin: the caller reads the rows and passes his day keys.
 */
object BriefInput {

    /** The brief is written from this hour of his local day (or the day boundary, if that is later). */
    val BRIEF_AT: LocalTime = LocalTime.of(6, 0)

    private val DAY_NAMES = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

    fun key(day: LocalDate) = "brief:$day"

    /** True once [today]'s brief may be written at local time [now]: not before [BRIEF_AT] or the boundary. */
    fun due(now: LocalDateTime, today: LocalDate, boundaryHour: Int): Boolean {
        val from = maxOf(BRIEF_AT, LocalTime.of(boundaryHour.coerceIn(0, 23), 0))
        // After midnight but before the boundary, his "today" is still yesterday: that day's brief is long past.
        return now.toLocalDate() == today && !now.toLocalTime().isBefore(from)
    }

    /** The next local time a brief is due after [now]: [BRIEF_AT] (or the boundary) today or tomorrow. */
    fun nextRun(now: LocalDateTime, boundaryHour: Int): LocalDateTime {
        val from = maxOf(BRIEF_AT, LocalTime.of(boundaryHour.coerceIn(0, 23), 0))
        val todayAt = now.toLocalDate().atTime(from)
        return if (todayAt.isAfter(now)) todayAt else todayAt.plusDays(1)
    }

    /** "Yesterday (Monday): T1 "CAF lectures" done (target)." One line, or a line saying no answer was given. */
    fun yesterday(t: PlanBlocks.Tracker, yesterday: LocalDate): List<String> {
        val day = DAY_NAMES[yesterday.dayOfWeek.value - 1]
        if (yesterday in t.restDays) return listOf("Yesterday ($day) was a planned rest day.")
        val parts = t.targets.mapNotNull { x ->
            PlanBlocks.todayState(x, yesterday)?.let { state ->
                val said = if (state == "not answered yet") "no answer" else state
                "T${x.id} \"${PlanBlocks.clean(x.name)}\" $said"
            }
        }
        return if (parts.isEmpty()) emptyList() else listOf("Yesterday ($day): " + parts.joinToString("; ") + ".")
    }

    /** True when at least one target asks something of him today (not a rest day, not before its start). */
    fun somethingToday(t: PlanBlocks.Tracker, today: LocalDate): Boolean =
        today !in t.restDays && t.targets.any { PlanBlocks.todayState(it, today) != null }

    /**
     * Worth a model call only when the day has something in it: a target scheduled today, an open promise, or an
     * answer from yesterday. Otherwise the brief is skipped with no call (spec section 8).
     */
    fun worthWriting(t: PlanBlocks.Tracker, today: LocalDate, openPromises: Int): Boolean {
        val answeredYesterday = t.targets.any { x -> x.checkins.any { it.day == today.minusDays(1) } }
        return somethingToday(t, today) || openPromises > 0 || answeredYesterday
    }
}
