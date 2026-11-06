package com.clarity.life.core

import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * v2 chat-day rules (docs/CLARITY-V2-BUILD-SPEC.md sections 3 and 4), kept pure so they can be tested:
 * one conversation per local day, and the one quiet line at the top of the chat.
 */
object DayChat {
    private val DAY_NAME = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)

    /** When the given day starts, as UTC milliseconds: its date at the day boundary hour in the owner's zone. */
    fun dayStartMillis(day: LocalDate, boundaryHour: Int, timeZone: String): Long =
        day.atTime(boundaryHour.coerceIn(0, 23), 0).atZone(ZoneId.of(timeZone)).toInstant().toEpochMilli()

    /** `Friday 6 November` or `Friday 6 November · Working on 2 things`; "Working on" is hidden when there are none. */
    fun topLine(day: LocalDate, activeItems: Int): String {
        val date = day.format(DAY_NAME)
        return when {
            activeItems <= 0 -> date
            activeItems == 1 -> "$date \u00b7 Working on 1 thing"
            else -> "$date \u00b7 Working on $activeItems things"
        }
    }
}
