package com.clarity.life.core

import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * How many days it is to each date the owner mentioned ("by 1 feb", "nov 20"), counted in code and given to
 * the coach in NOW. The coach may do rough sums while a plan is being talked through (the owner's decision,
 * 11 Oct 2026), but it never counts days on a calendar: that is where a model goes wrong, so the app does it.
 *
 * Pure Kotlin, no clock: [today] is the day key. Dates are read by [PlanDates], which never guesses.
 */
object DayCounts {

    const val MAX = 4

    private const val MONTH = "january|jan|february|feb|march|mar|april|apr|may|june|jun|july|jul|august|aug|" +
        "september|sept|sep|october|oct|november|nov|december|dec"
    private val DAY_MONTH = Regex("\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+(?:of\\s+)?($MONTH)\\b(?:\\s+(20\\d\\d)\\b)?", RegexOption.IGNORE_CASE)
    private val MONTH_DAY = Regex("\\b($MONTH)\\s+(\\d{1,2})(?:st|nd|rd|th)?\\b(?:,?\\s+(20\\d\\d)\\b)?", RegexOption.IGNORE_CASE)

    /** One line for each future date found in [texts] (newest text first), at most [MAX], no date twice. */
    fun lines(texts: List<String>, today: LocalDate): List<String> {
        val days = LinkedHashSet<LocalDate>()
        for (text in texts) {
            val found = DAY_MONTH.findAll(text).map { listOf(it.groupValues[1], it.groupValues[2], it.groupValues[3]) } +
                MONTH_DAY.findAll(text).map { listOf(it.groupValues[2], it.groupValues[1], it.groupValues[3]) }
            for (parts in found) {
                val day = PlanDates.readDay(parts.filter { it.isNotEmpty() }.joinToString(" "), today)
                if (day != null && day.isAfter(today)) days.add(day)
            }
        }
        return days.take(MAX).map { line(it, today) }
    }

    fun line(day: LocalDate, today: LocalDate): String {
        val n = ChronoUnit.DAYS.between(today, day)
        val month = day.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        val weekday = day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        return "${day.dayOfMonth} $month ${day.year} is a $weekday, $n ${if (n == 1L) "day" else "days"} after today (counted by the app)."
    }
}
