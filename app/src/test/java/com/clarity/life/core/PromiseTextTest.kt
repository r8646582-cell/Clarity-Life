package com.clarity.life.core

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The promise lines are code output the prompts quote, so the open line is checked against the sample in
 * prompts/testbench.md ("Time awareness"), and the rest against fixed times, after midnight included.
 */
class PromiseTextTest {

    private val tz = "Asia/Karachi"

    // Sunday 4 October 2026, 12:40 am in Karachi: their day still counts as Saturday.
    private val lateNight = Instant.parse("2026-10-03T19:40:00Z")

    private fun p(id: Long, text: String, dueUtc: String, status: String = "open", reason: String? = null) =
        PromiseText.Promise(id, text, Instant.parse(dueUtc), status, reason)

    @Test
    fun theOpenLineIsTheOneTheTestBenchTeaches() {
        val line = PromiseText.openLine(p(4, "Study 1 hour at 8am", "2026-10-05T03:00:00Z"), lateNight, tz)
        assertEquals("P4 \"Study 1 hour at 8am\", due Monday 5 October, 8:00 am (in 1 day 7 hours).", line)
        assertTrue(Vectors.readText("prompts/testbench.md").contains("OPEN_PROMISES: $line"))
    }

    @Test
    fun anOverduePromiseHasNoAnswerYetNeverNotDone() {
        val line = PromiseText.openLine(p(2, "Call Mom", "2026-10-03T13:00:00Z"), lateNight, tz)
        assertEquals("P2 \"Call Mom\", was due Saturday 6:00 pm, 6 hours 40 minutes ago, no answer yet.", line)
    }

    @Test
    fun answeredPromisesFollowTheOpenOnesNewestFirstForTwoWeeks() {
        val open = listOf(p(9, "Walk 20 minutes", "2026-10-04T02:00:00Z"), p(4, "Study 1 hour at 8am", "2026-10-05T03:00:00Z"))
        val answered = listOf(
            p(3, "Study 20 minutes at 5pm", "2026-10-02T12:00:00Z", "not_done", "tired"),
            p(1, "Clean the desk", "2026-09-10T12:00:00Z", "let_go"),
            p(5, "Message Bilal about notes", "2026-10-01T12:00:00Z", "kept"),
            p(6, "Run", "2026-10-02T01:00:00Z", "moved"),
        )
        val block = PromiseText.block(open.reversed(), answered, lateNight, tz)
        assertEquals("OPEN_PROMISES", block.name)
        assertEquals(
            listOf(
                "P9 \"Walk 20 minutes\", due Sunday 4 October, 7:00 am (in 6 hours 20 minutes).",
                "P4 \"Study 1 hour at 8am\", due Monday 5 October, 8:00 am (in 1 day 7 hours).",
                "P3 \"Study 20 minutes at 5pm\", was due Friday 5:00 pm, 1 day 7 hours ago: not done, reason: tired.",
                "P6 \"Run\", was due Friday 6:00 am, 1 day 18 hours ago: moved to a new time.",
                "P5 \"Message Bilal about notes\", was due Thursday 5:00 pm, 2 days 7 hours ago: done.",
            ),
            block.items.map { it.text },
        )
        assertTrue("never trimmed", block.items.all { it.drop == 0 })
        assertTrue(PromiseText.block(emptyList(), emptyList(), lateNight, tz).items.isEmpty())
    }

    @Test
    fun theDueCardShowsHisDayAndEarlierUsingTheDayBoundary() {
        val open = listOf(
            p(1, "Call Mom", "2026-10-03T13:00:00Z"), // Saturday 6 pm: their day is still Saturday
            p(2, "Walk", "2026-10-04T02:00:00Z"), // Sunday 7 am: tomorrow for him, not shown yet
            p(3, "Pay the bill", "2026-10-01T05:00:00Z"), // Thursday: overdue
        )
        assertEquals(listOf(3L, 1L), PromiseText.dueNow(open, lateNight, tz, 4).map { it.id })
        // At 9 am on Sunday, Sunday's promise is due today.
        assertEquals(listOf(3L, 1L, 2L), PromiseText.dueNow(open, Instant.parse("2026-10-04T04:00:00Z"), tz, 4).map { it.id })
    }

    @Test
    fun theDueKicker() {
        val now = Instant.parse("2026-10-09T16:00:00Z") // Friday 9 pm
        assertEquals("Was due 6:00 pm, 3 hours ago", PromiseText.dueKicker(Instant.parse("2026-10-09T13:00:00Z"), now, tz, 4))
        assertEquals("Due 11:30 pm, in 2 hours 30 minutes", PromiseText.dueKicker(Instant.parse("2026-10-09T18:30:00Z"), now, tz, 4))
        assertEquals("Was due Wed 7 Oct, 6:00 pm", PromiseText.dueKicker(Instant.parse("2026-10-07T13:00:00Z"), now, tz, 4))
    }

    @Test
    fun theReasonChipsAreTheSpecList() {
        assertEquals(9, PromiseText.REASONS.size)
        assertEquals("tired", PromiseText.REASONS.first())
        assertEquals("other", PromiseText.REASONS.last())
    }
}
