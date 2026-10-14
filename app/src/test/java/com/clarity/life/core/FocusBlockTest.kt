package com.clarity.life.core

import org.junit.Assert.assertEquals
import org.junit.Test

class FocusBlockTest {

    private val focus = FocusBlock.Focus(1, "Driving again", "To drive to college\non his own", "active", "")

    @Test
    fun aNewFocusSaysSoInsteadOfLeavingGaps() {
        val block = FocusBlock.block(focus, null, emptyList())
        assertEquals("FOCUS", block.name)
        assertEquals(
            listOf(
                "F1 \"Driving again\". Why: To drive to college on his own",
                "Summary: none yet, this is the first conversation in this focus",
                "Current step: none set",
                "Last steps: none answered yet",
            ),
            block.items.map { it.text },
        )
        // FOCUS is never trimmed.
        assertEquals(setOf(0), block.items.map { it.drop }.toSet())
    }

    @Test
    fun stepsAndResultsComeFromHisAnswers() {
        val block = FocusBlock.block(
            focus.copy(status = "paused", summary = "Freezes behind the wheel since a minor accident."),
            FocusBlock.Step("Drive to the market at a quiet hour", "Sat 17 Oct, 7 am", "open", null),
            listOf(
                FocusBlock.Step("Drive around the block", "Wed", "kept", null),
                FocusBlock.Step("Sit in the parked car", "Mon", "not_done", "tired"),
                FocusBlock.Step("Start the engine", "Sun", "let_go", null),
                FocusBlock.Step("A fourth one", "Sat", "kept", null),
            ),
        )
        assertEquals(
            listOf(
                "F1 \"Driving again\" (paused). Why: To drive to college on his own",
                "Summary: Freezes behind the wheel since a minor accident.",
                "Current step: Drive to the market at a quiet hour, due Sat 17 Oct, 7 am",
                "Last steps: Drive around the block: done; Sit in the parked car: not done (reason: tired); " +
                    "Start the engine: let go",
            ),
            block.items.map { it.text },
        )
    }

    @Test
    fun theWeekCountsByDayKeys() {
        val start = java.time.LocalDate.parse("2026-10-05")
        assertEquals(1L, FocusBlock.week(start, start))
        assertEquals(1L, FocusBlock.week(start, java.time.LocalDate.parse("2026-10-11"))) // day 7
        assertEquals(2L, FocusBlock.week(start, java.time.LocalDate.parse("2026-10-12"))) // day 8
        assertEquals(3L, FocusBlock.week(start, java.time.LocalDate.parse("2026-10-19")))
        // A clock set back never shows week 0.
        assertEquals(1L, FocusBlock.week(start, java.time.LocalDate.parse("2026-10-01")))
    }

    @Test
    fun justAfterMidnightBeforeTheDayEndsItIsStillYesterdaysWeek() {
        // Made on Mon 5 Oct at 10:00 in Karachi; day ends at 4 am. Mon 12 Oct at 1:30 am is still Sun 11 Oct.
        val tz = "Asia/Karachi"
        val start = Planner.dayKey("2026-10-05T05:00:00Z", tz, 4)
        val lateNight = Planner.dayKey("2026-10-11T20:30:00Z", tz, 4)
        assertEquals(1L, FocusBlock.week(start, lateNight))
    }
}

