package com.clarity.life.core

import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * The words What I know shows around a note (docs/mockups/Know.dc.html), written by code from the stored
 * days, so the screen and the coach never disagree about when something was seen.
 */
object KnowText {

    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    private fun day(key: String): LocalDate? = try {
        Planner.day(key)
    } catch (e: DateTimeParseException) {
        null
    }

    /**
     * The date heading a conversation sits under in the drawer (spec section 2.1, grouped by date): "Today",
     * "Yesterday", a weekday within the last week, "7 Oct" this year, "7 Oct 2025" before.
     */
    fun drawerDay(day: LocalDate, today: LocalDate): String {
        val ago = java.time.temporal.ChronoUnit.DAYS.between(day, today)
        return when {
            ago == 0L -> "Today"
            ago == 1L -> "Yesterday"
            ago in 2..6 -> day.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)
            day.year == today.year -> "${day.dayOfMonth} ${MONTHS[day.monthValue - 1]}"
            else -> "${day.dayOfMonth} ${MONTHS[day.monthValue - 1]} ${day.year}"
        }
    }

    /** "7 Oct", or the stored text as it is when it is not a day key. */
    fun shortDay(key: String): String = day(key)?.let { "${it.dayOfMonth} ${MONTHS[it.monthValue - 1]}" } ?: key

    /** "seen once", "seen 4 times · Sep to Oct", "seen 2 times · Oct", "... · Dec 2025 to Jan". */
    fun seen(timesSeen: Int, firstSeen: String, lastSeen: String): String {
        val count = if (timesSeen <= 1) "seen once" else "seen $timesSeen times"
        val a = day(firstSeen) ?: return count
        val b = day(lastSeen) ?: return count
        fun month(d: LocalDate, withYear: Boolean) = MONTHS[d.monthValue - 1] + if (withYear) " ${d.year}" else ""
        val span = when {
            a.year == b.year && a.monthValue == b.monthValue -> month(a, false)
            a.year == b.year -> "${month(a, false)} to ${month(b, false)}"
            else -> "${month(a, true)} to ${month(b, false)}"
        }
        return "$count · $span"
    }

    /** One evidence line: his words with the day he said them, or the app's own count. */
    fun evidence(kind: String, text: String, day: String): String =
        if (kind == "count") text else "You said, ${shortDay(day)}: “$text”"

    /** The grade tag. "Confirmed by you" covers both his tap and his edit. */
    fun grade(confidence: String): String = when (confidence) {
        "confirmed" -> "Confirmed by you"
        "likely" -> "Likely"
        else -> "Guess"
    }
}
