package com.clarity.life.core

import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The morning brief's code-written inputs and timing (spec section 8). */
class BriefInputTest {

    private val tue = LocalDate.parse("2026-10-20")
    private val mon = tue.minusDays(1)

    private fun tracker(checkins: List<CheckIn> = emptyList(), schedule: String = "daily", rest: Set<LocalDate> = emptySet()) =
        PlanBlocks.Tracker(
            goals = emptyList(),
            targets = listOf(PlanBlocks.Target(1, "CAF lectures", null, null, 1.0, 2.0, schedule, checkins)),
            backlogs = emptyList(),
            restDays = rest,
        )

    @Test
    fun yesterdaySaysWhatHeAnswered() {
        val t = tracker(listOf(CheckIn(mon, "target", 2.0)))
        assertEquals(listOf("Yesterday (Monday): T1 \"CAF lectures\" done (target)."), BriefInput.yesterday(t, mon))
    }

    @Test
    fun noAnswerIsNeverAMiss() {
        assertEquals(listOf("Yesterday (Monday): T1 \"CAF lectures\" no answer."), BriefInput.yesterday(tracker(), mon))
    }

    @Test
    fun aRestDayIsSaidAsOne() {
        assertEquals(listOf("Yesterday (Monday) was a planned rest day."), BriefInput.yesterday(tracker(rest = setOf(mon)), mon))
    }

    @Test
    fun nothingInTheDayMeansNoCall() {
        val weekends = tracker(schedule = "Sat,Sun")
        assertFalse(BriefInput.worthWriting(weekends, tue, openPromises = 0))
        assertTrue(BriefInput.worthWriting(weekends, tue, openPromises = 1))
        assertTrue(BriefInput.worthWriting(tracker(), tue, openPromises = 0))
        assertFalse(BriefInput.worthWriting(tracker(rest = setOf(tue)), tue, openPromises = 0))
    }

    @Test
    fun dueFromSixOrTheBoundaryNeverBefore() {
        assertFalse(BriefInput.due(LocalDateTime.parse("2026-10-20T05:59"), tue, 4))
        assertTrue(BriefInput.due(LocalDateTime.parse("2026-10-20T06:00"), tue, 4))
        assertFalse(BriefInput.due(LocalDateTime.parse("2026-10-20T06:30"), tue, 7))
        assertTrue(BriefInput.due(LocalDateTime.parse("2026-10-20T07:00"), tue, 7))
        // At 1 am his day is still Monday: Monday's brief is long past, and Tuesday's is not due yet.
        assertFalse(BriefInput.due(LocalDateTime.parse("2026-10-21T01:00"), tue, 4))
    }

    @Test
    fun nextRunIsTheNextMorning() {
        assertEquals(LocalDateTime.parse("2026-10-20T06:00"), BriefInput.nextRun(LocalDateTime.parse("2026-10-20T03:00"), 4))
        assertEquals(LocalDateTime.parse("2026-10-21T06:00"), BriefInput.nextRun(LocalDateTime.parse("2026-10-20T06:00"), 4))
        assertEquals(LocalDateTime.parse("2026-10-21T07:00"), BriefInput.nextRun(LocalDateTime.parse("2026-10-20T09:00"), 7))
    }

    @Test
    fun theKeyIsOnePerDay() = assertEquals("brief:2026-10-20", BriefInput.key(tue))
}
