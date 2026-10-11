package com.clarity.life.core

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChaptersTest {

    @Test
    fun quartersAreCalendarQuarters() {
        val q = Chapters.quarterOf(LocalDate.of(2026, 8, 15))
        assertEquals(LocalDate.of(2026, 7, 1), q.start)
        assertEquals(LocalDate.of(2026, 9, 30), q.end)
        assertEquals("chapter:2026-07-01", q.key)
        assertEquals("Jul to Sep 2026", q.label)
        assertEquals(LocalDate.of(2026, 12, 31), Chapters.quarterOf(LocalDate.of(2026, 10, 1)).end)
        assertEquals(LocalDate.of(2024, 3, 31), Chapters.quarterOf(LocalDate.of(2024, 2, 29)).end)
    }

    @Test
    fun theDueQuarterIsTheOneThatEndedLast() {
        assertEquals(LocalDate.of(2026, 7, 1), Chapters.latestFinished(LocalDate.of(2026, 10, 10)).start)
        assertEquals(LocalDate.of(2026, 7, 1), Chapters.latestFinished(LocalDate.of(2026, 10, 1)).start)
        assertEquals(LocalDate.of(2026, 4, 1), Chapters.latestFinished(LocalDate.of(2026, 9, 30)).start)
        // Across the new year.
        val q = Chapters.latestFinished(LocalDate.of(2027, 1, 3))
        assertEquals(LocalDate.of(2026, 10, 1), q.start)
        assertEquals("Oct to Dec 2026", q.label)
    }

    @Test
    fun aQuietQuarterGetsNoChapter() {
        assertFalse(Chapters.worthWriting(2))
        assertTrue(Chapters.worthWriting(3))
    }

    @Test
    fun theBlockKeepsTheLastTwoOlderFirstAndDropsTheOlderFirst() {
        val block = Chapters.block(
            listOf(
                Chapters.Chapter("2026-07-01", "2026-09-30", "Third", "c"),
                Chapters.Chapter("2026-04-01", "2026-06-30", "Second", "b"),
                Chapters.Chapter("2026-01-01", "2026-03-31", "First", "a"),
            ),
        )
        assertEquals("CHAPTERS", block.name)
        assertEquals(listOf("Apr to Jun 2026: Second\nb", "Jul to Sep 2026: Third\nc"), block.items.map { it.text })
        assertTrue(block.items[0].drop > block.items[1].drop)
        assertTrue(block.items.all { it.drop > 0 })
    }
}
