package com.clarity.life.core

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The time sentences are code output that the prompts quote, so this test takes the two NOW samples out of
 * prompts/testbench.md and checks the app really writes them that way. If a sentence here and a sample
 * there ever drift apart, the coach would be shown a shape it was never taught to read.
 */
class TimeFactsTest {

    private val tz = "Asia/Karachi"

    @Test
    fun theNowSentenceMatchesTheSamplesInTheTestBench() {
        val bench = Vectors.readText("prompts/testbench.md")

        // 9:40 pm local, an ordinary evening.
        val night = TimeFacts.nowSentence(Instant.parse("2026-10-09T16:40:00Z"), tz, 4)
        assertEquals("It's Friday 9 October 2026, 9:40 pm (night).", night)
        assertTrue("testbench.md no longer contains: $night", bench.contains(night))

        // 12:40 am local: their day still counts as the day before until the boundary.
        val lateNight = TimeFacts.nowSentence(Instant.parse("2026-10-03T19:40:00Z"), tz, 4)
        assertEquals(
            "It's Sunday 4 October 2026, 12:40 am (late night; their day still counts as Saturday until 4 am).",
            lateNight,
        )
        assertTrue("testbench.md no longer contains: $lateNight", bench.contains(lateNight))
    }

    @Test
    fun theBandsFollowTheBoundaryHour() {
        assertEquals("late night", TimeFacts.band(1, 4))
        assertEquals("morning", TimeFacts.band(4, 4))
        assertEquals("morning", TimeFacts.band(11, 4))
        assertEquals("afternoon", TimeFacts.band(12, 4))
        assertEquals("evening", TimeFacts.band(17, 4))
        assertEquals("night", TimeFacts.band(21, 4))
    }

    @Test
    fun theDayKeyAndTheNowSentenceAgreeAboutWhichDayItIs() {
        val lateNight = Instant.parse("2026-10-03T19:40:00Z")
        assertEquals("2026-10-03", Planner.dayKey(lateNight.toString(), tz, 4).toString())
        assertTrue(TimeFacts.nowSentence(lateNight, tz, 4).contains("counts as Saturday"))
        assertTrue(TimeFacts.isAmbiguousNow(lateNight, tz, 4))
        assertFalse(TimeFacts.isAmbiguousNow(Instant.parse("2026-10-09T16:40:00Z"), tz, 4))
    }

    @Test
    fun aCardShowsHimTheExactDayAndTimeBeforeHeTaps() {
        assertEquals("Sat 10 Oct, 3:00 pm", TimeFacts.cardTime(Instant.parse("2026-10-10T10:00:00Z"), tz))
    }

    @Test
    fun hisMessagesCarryTheStampTheHistoryWindowExpects() {
        assertEquals("Fri 9 Oct, 21:40", TimeFacts.stamp(Instant.parse("2026-10-09T16:40:00Z"), tz))
    }

    @Test
    fun howLongAgoIsCountedByCodeSoTheCoachNeverGuessesYesterday() {
        val today = java.time.LocalDate.parse("2026-10-13")
        assertEquals("today", TimeFacts.ago(today, today))
        assertEquals("yesterday", TimeFacts.ago(today.minusDays(1), today))
        assertEquals("3 days ago", TimeFacts.ago(today.minusDays(3), today))
        assertEquals("tomorrow", TimeFacts.ago(today.plusDays(1), today))
        assertEquals("in 2 days", TimeFacts.ago(today.plusDays(2), today))
    }

    @Test
    fun duePhrasesAreWrittenByCodeInBothDirections() {
        val now = Instant.parse("2026-10-04T03:00:00Z")
        val soon = now.plus(Duration.ofHours(31))
        assertEquals(
            "due Monday 5 October, 3:00 pm (in 1 day 7 hours)",
            TimeFacts.duePhrase(soon, now, tz),
        )
        val past = now.minus(Duration.ofDays(2))
        assertTrue(TimeFacts.duePhrase(past, now, tz).startsWith("was due Friday 8:00 am, 2 days ago"))
    }

    @Test
    fun gapsAreSpelledInWholeUnits() {
        assertEquals("40 minutes", TimeFacts.spell(Duration.ofMinutes(40)))
        assertEquals("1 day", TimeFacts.spell(Duration.ofDays(1)))
        assertEquals("2 hours 5 minutes", TimeFacts.spell(Duration.ofMinutes(125)))
        assertEquals("less than a minute", TimeFacts.spell(Duration.ofSeconds(20)))
    }
}
