package com.clarity.life.ui.plan

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class WeekHeadingTest {

    @Test
    fun theHeadingCountsEveryBoxDrawnNotOnlyScheduledDays() {
        // Mon/Wed/Fri: three scheduled days, four with nothing scheduled, seven boxes on screen.
        val monday = LocalDate.of(2026, 10, 5)
        val marks = (0L..6L).map { i ->
            val level = when (i) {
                0L -> "target"
                2L -> null
                4L -> "minimum"
                else -> "off"
            }
            PlanViewModel.DayMark(monday.plusDays(i), level)
        }
        assertEquals("Last 7 days", weekHeading(marks))
    }
}
