package com.clarity.life.core

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LetterPeriodsTest {

    private fun d(s: String) = LocalDate.parse(s)

    @Test
    fun keysAndKinds() {
        assertEquals("letter:month:2026-10", LetterPeriods.monthKey(d("2026-10-01")))
        assertEquals("letter:year:2026", LetterPeriods.yearKey(2026))
        assertEquals(LetterPeriods.WEEK, LetterPeriods.kindOf(ReviewTimes.letterKey(d("2026-10-05"))))
        assertEquals(LetterPeriods.MONTH, LetterPeriods.kindOf("letter:month:2026-10"))
        assertEquals(LetterPeriods.YEAR, LetterPeriods.kindOf("letter:year:2026"))
        assertEquals("monthly", LetterPeriods.adjective("letter:month:2026-10"))
    }

    @Test
    fun aMonthsLetterWaitsOneDayAfterTheMonthEnds() {
        assertNull(LetterPeriods.dueMonth(d("2026-11-01")))
        assertEquals(d("2026-10-01"), LetterPeriods.dueMonth(d("2026-11-02")))
        assertEquals(d("2026-10-01"), LetterPeriods.dueMonth(d("2026-11-30")))
        // Across the new year.
        assertEquals(d("2026-12-01"), LetterPeriods.dueMonth(d("2027-01-02")))
    }

    @Test
    fun aYearsLetterWaitsForDecembersLetterButNotForever() {
        assertNull(LetterPeriods.dueYear(d("2027-01-02"), decemberSettled = true))
        assertEquals(2026, LetterPeriods.dueYear(d("2027-01-03"), decemberSettled = true))
        assertNull(LetterPeriods.dueYear(d("2027-01-03"), decemberSettled = false))
        assertNull(LetterPeriods.dueYear(d("2027-01-09"), decemberSettled = false))
        assertEquals(2026, LetterPeriods.dueYear(d("2027-01-10"), decemberSettled = false))
        assertEquals(2026, LetterPeriods.dueYear(d("2027-06-15"), decemberSettled = false))
    }

    @Test
    fun aWeekBelongsToTheMonthItsSundayIsIn() {
        // October 2026: Sundays on the 4th, 11th, 18th and 25th.
        assertEquals(
            listOf("2026-09-28", "2026-10-05", "2026-10-12", "2026-10-19").map(::d),
            LetterPeriods.mondaysOf(d("2026-10-01")),
        )
        // November 2026 starts on a Sunday and ends on a Monday: five Sundays.
        assertEquals(5, LetterPeriods.mondaysOf(d("2026-11-01")).size)
        assertEquals(d("2026-10-26"), LetterPeriods.mondaysOf(d("2026-11-01")).first())
    }

    @Test
    fun tooFewSourceLettersMeansNoLetter() {
        assertFalse(LetterPeriods.worthMonth(1))
        assertTrue(LetterPeriods.worthMonth(2))
        assertFalse(LetterPeriods.worthYear(1))
        assertTrue(LetterPeriods.worthYear(2))
    }

    @Test
    fun monthNamesAndSourcesAreEscaped() {
        assertEquals("October 2026", LetterPeriods.monthName(d("2026-10-01")))
        val s = LetterPeriods.source("October 2026", "A [[card]]", "## NOW\nfine")
        assertFalse(s.contains("[["))
        assertTrue(s.contains("  ## NOW"))
    }
}
