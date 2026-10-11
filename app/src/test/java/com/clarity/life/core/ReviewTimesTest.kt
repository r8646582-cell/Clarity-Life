package com.clarity.life.core

import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

/** When the weekly review, the letter and the monthly review are due. 2026-10-11 is a Sunday. */
class ReviewTimesTest {
    private fun at(s: String) = LocalDateTime.parse(s)
    private fun d(s: String) = LocalDate.parse(s)

    @Test
    fun theWeekIsDueOnSundayEvening() {
        assertEquals(d("2026-09-28"), ReviewTimes.latestWeek(at("2026-10-11T18:59")))
        assertEquals(d("2026-10-05"), ReviewTimes.latestWeek(at("2026-10-11T19:00")))
        // the letter waits until 19:30
        assertEquals(d("2026-09-28"), ReviewTimes.latestWeek(at("2026-10-11T19:10"), ReviewTimes.LETTER_AT))
        assertEquals(d("2026-10-05"), ReviewTimes.latestWeek(at("2026-10-11T19:30"), ReviewTimes.LETTER_AT))
        // Monday after midnight: last week is done
        assertEquals(d("2026-10-05"), ReviewTimes.latestWeek(at("2026-10-12T00:30")))
    }

    @Test
    fun catchUpOnlyCoversTrackedWeeks() {
        val now = at("2026-10-14T09:00")
        assertEquals(emptyList<LocalDate>(), ReviewTimes.dueWeeks(now, null))
        assertEquals(listOf(d("2026-10-05")), ReviewTimes.dueWeeks(now, d("2026-10-10")))
        assertEquals(
            listOf(d("2026-09-14"), d("2026-09-21"), d("2026-09-28"), d("2026-10-05")),
            ReviewTimes.dueWeeks(now, d("2026-01-01")),
        )
    }

    @Test
    fun aMonthIsDueOnceItHasEnded() {
        assertEquals(listOf(d("2026-09-01")), ReviewTimes.dueMonths(at("2026-10-01T08:00"), d("2026-09-30")))
        assertEquals(listOf(d("2026-08-01"), d("2026-09-01")), ReviewTimes.dueMonths(at("2026-10-01T08:00"), d("2026-01-01")))
        assertEquals(emptyList<LocalDate>(), ReviewTimes.dueMonths(at("2026-10-20T08:00"), d("2026-10-02")))
        // Just after midnight on the 1st his day is still the 31st: October is not over until 4 am.
        assertEquals(listOf(d("2026-09-01")), ReviewTimes.dueMonths(at("2026-11-01T00:30"), d("2026-01-01"), back = 1))
        assertEquals(listOf(d("2026-10-01")), ReviewTimes.dueMonths(at("2026-11-01T04:00"), d("2026-01-01"), back = 1))
        assertEquals(listOf(d("2026-10-01")), ReviewTimes.dueMonths(at("2026-11-01T00:30"), d("2026-01-01"), boundaryHour = 0, back = 1))
    }

    @Test
    fun theNextRunIsTheComingSunday() {
        assertEquals(at("2026-10-11T19:00"), ReviewTimes.nextSunday(at("2026-10-09T21:40")))
        assertEquals(at("2026-10-18T19:00"), ReviewTimes.nextSunday(at("2026-10-11T19:00")))
        assertEquals(at("2026-10-11T19:30"), ReviewTimes.nextSunday(at("2026-10-11T19:00"), ReviewTimes.LETTER_AT))
    }
}
