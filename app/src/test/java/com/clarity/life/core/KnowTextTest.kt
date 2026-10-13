package com.clarity.life.core

import org.junit.Assert.assertEquals
import org.junit.Test

class KnowTextTest {

    @Test
    fun seenLines() {
        assertEquals("seen once · Oct", KnowText.seen(1, "2026-10-01", "2026-10-09"))
        assertEquals("seen 4 times · Sep to Oct", KnowText.seen(4, "2026-09-14", "2026-10-10"))
        assertEquals("seen 2 times · Dec 2025 to Jan", KnowText.seen(2, "2025-12-30", "2026-01-02"))
        assertEquals("seen 3 times", KnowText.seen(3, "", "2026-10-01"))
    }

    @Test
    fun evidenceLines() {
        assertEquals(
            "You said, 7 Oct: “I can do it when I'm fresh”",
            KnowText.evidence("quote", "I can do it when I'm fresh", "2026-10-07"),
        )
        assertEquals(
            "tired on 9 of 12 missed evening targets",
            KnowText.evidence("count", "tired on 9 of 12 missed evening targets", "2026-10-10"),
        )
    }

    @Test
    fun grades() {
        assertEquals("Confirmed by you", KnowText.grade("confirmed"))
        assertEquals("Likely", KnowText.grade("likely"))
        assertEquals("Guess", KnowText.grade("guess"))
    }

    @Test
    fun theDrawerGroupsByDayInPlainWords() {
        val today = java.time.LocalDate.of(2026, 10, 10)
        assertEquals("Today", KnowText.drawerDay(today, today))
        assertEquals("Yesterday", KnowText.drawerDay(today.minusDays(1), today))
        assertEquals("Monday", KnowText.drawerDay(java.time.LocalDate.of(2026, 10, 5), today))
        assertEquals("3 Oct", KnowText.drawerDay(java.time.LocalDate.of(2026, 10, 3), today))
        assertEquals("28 Dec 2025", KnowText.drawerDay(java.time.LocalDate.of(2025, 12, 28), today))
    }
}
