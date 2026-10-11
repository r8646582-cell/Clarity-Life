package com.clarity.life.core

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class DayCountsTest {

    private val today = LocalDate.of(2026, 10, 11) // a Sunday

    @Test
    fun aDateInHisWordsGetsItsDayCount() {
        assertEquals(
            listOf("1 February 2027 is a Monday, 113 days after today (counted by the app)."),
            DayCounts.lines(listOf("i want 300 lectures done by 1 feb"), today),
        )
    }

    @Test
    fun monthFirstWorksAndAYearIsNotReadAsADay() {
        assertEquals(
            listOf("20 November 2026 is a Friday, 40 days after today (counted by the app)."),
            DayCounts.lines(listOf("exam on Nov 20, starting oct 2026"), today),
        )
    }

    @Test
    fun noDatesNoLinesAndNoDateTwice() {
        assertEquals(emptyList<String>(), DayCounts.lines(listOf("2 lectures for 2 weeks then 3"), today))
        assertEquals(1, DayCounts.lines(listOf("by 1 feb", "the 1st of february"), today).size)
    }

    @Test
    fun oneDayIsSingular() {
        assertEquals("12 October 2026 is a Monday, 1 day after today (counted by the app).", DayCounts.line(today.plusDays(1), today))
    }
}
