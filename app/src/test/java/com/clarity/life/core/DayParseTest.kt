package com.clarity.life.core

import java.time.LocalDate
import java.time.format.DateTimeParseException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Planner.day] reads a stored day key exactly as LocalDate.parse does, and fails on the same bad ones. */
class DayParseTest {

    @Test
    fun everyDayOfTwoCenturiesReadsTheSame() {
        var d = LocalDate.parse("1950-01-01")
        while (d.year < 2150) {
            assertEquals(d, Planner.day(d.toString()))
            d = d.plusDays(1)
        }
    }

    @Test
    fun badKeysFailAsBefore() {
        for (bad in listOf("2026-02-30", "2025-02-29", "2026-13-01", "2026-00-10", "2026-10-00", "2026-1-14", "26-10-14", "2026/10/14", "", "abcd-ef-gh", "2026-10-1x")) {
            var failed = false
            try {
                Planner.day(bad)
            } catch (e: DateTimeParseException) {
                failed = true
            }
            assertTrue("$bad must fail", failed)
        }
    }
}
