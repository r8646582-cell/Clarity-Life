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
    fun withAMidnightBoundaryTheSmallHoursAreStillLateNight() {
        // The app's default since 13 Oct 2026: 12:30 am said "morning" before this.
        assertEquals("late night", TimeFacts.band(0, 0))
        assertEquals("late night", TimeFacts.band(3, 0))
        assertEquals("morning", TimeFacts.band(4, 0))
        assertEquals("late night", TimeFacts.band(5, 6))
        val bench = Vectors.readText("prompts/testbench.md")
        val lateNight = TimeFacts.nowSentence(Instant.parse("2026-10-03T19:40:00Z"), tz, 0)
        // No "still counts as" note: with a midnight boundary it is already Sunday.
        assertEquals("It's Sunday 4 October 2026, 12:40 am (late night).", lateNight)
        assertTrue("testbench.md no longer contains: $lateNight", bench.contains(lateNight))
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

    @Test
    fun aPastMomentCarriesItsTimeAndHowLongAgo() {
        val now = Instant.parse("2026-10-14T06:20:00Z") // Wed 11:20 am in Karachi
        // Earlier the same day: the time and the hours, so the coach can see which came first.
        assertEquals(
            "Wed 14 Oct, 9:10 am (today, 2 hours 10 minutes ago)",
            TimeFacts.pastStamp(Instant.parse("2026-10-14T04:10:00Z"), now, tz, 0),
        )
        // Longer ago than a day: the day count alone.
        assertEquals("Mon 12 Oct, 9:10 pm (2 days ago)", TimeFacts.pastStamp(Instant.parse("2026-10-12T16:10:00Z"), now, tz, 0))
        // "Today" follows his day: at 1:00 am with a 4 am boundary, 11:30 pm the evening before is still today.
        assertEquals(
            "Tue 13 Oct, 11:30 pm (today, 1 hour 30 minutes ago)",
            TimeFacts.pastStamp(Instant.parse("2026-10-13T18:30:00Z"), Instant.parse("2026-10-13T20:00:00Z"), tz, 4),
        )
        // A moment still ahead (a promise's due time) names only its day.
        assertEquals("Thu 15 Oct, 8:00 am (tomorrow)", TimeFacts.pastStamp(Instant.parse("2026-10-15T03:00:00Z"), now, tz, 0))
    }

    @Test
    fun aConversationSpanNamesItsFirstAndLastTimes() {
        assertEquals(
            "Wed 14 Oct, 9:05 am to 9:40 am",
            TimeFacts.span(Instant.parse("2026-10-14T04:05:00Z"), Instant.parse("2026-10-14T04:40:00Z"), tz),
        )
        assertEquals(
            "Tue 13 Oct, 10:30 pm to Wed 14 Oct, 12:40 am",
            TimeFacts.span(Instant.parse("2026-10-13T17:30:00Z"), Instant.parse("2026-10-13T19:40:00Z"), tz),
        )
    }
}
