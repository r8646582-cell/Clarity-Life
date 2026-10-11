package com.clarity.life.core

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GardeningInputTest {

    private val today = LocalDate.of(2026, 10, 10)

    private fun note(id: Long, lastSeen: String, edited: Boolean = false, confidence: String = "guess") =
        GardeningInput.Note(id, "pattern", confidence, 1, "2026-06-01", lastSeen, 1, edited, "You start late")

    @Test
    fun quietIsSixtyDaysOrMoreWorkedOutByCode() {
        assertTrue(GardeningInput.quiet("2026-08-11", today)) // exactly 60 days
        assertFalse(GardeningInput.quiet("2026-08-12", today)) // 59
        assertFalse(GardeningInput.quiet("not a day", today))
    }

    @Test
    fun theNotesBlockMarksWhatTheModelMustNotWorkOut() {
        val text = GardeningInput.notes(
            listOf(note(3, "2026-07-01"), note(4, "2026-10-09", edited = true, confidence = "confirmed")),
            today,
        )
        val lines = text.lines()
        assertEquals("NOTES", lines[0])
        assertTrue(lines[1], lines[1].startsWith("- [id 3] (pattern, guess; seen 1 time;"))
        assertTrue(lines[1], lines[1].contains("quiet 60+ days"))
        assertTrue(lines[2], lines[2].contains("edited by them") && lines[2].contains("confirmed by them"))
        assertFalse(lines[2], lines[2].contains("quiet"))
    }

    @Test
    fun emptyBlocksSayNone() {
        assertEquals("NOTES\n(none)", GardeningInput.notes(emptyList(), today))
        assertEquals("PEOPLE\n(none)", GardeningInput.people(emptyList()))
        assertEquals("PROFILE\n(none)", GardeningInput.profile(emptyList()))
        assertEquals("SUMMARIES\n(none)", GardeningInput.summaries(emptyList()))
    }

    @Test
    fun peopleCarryTheirIdsForMergePeople() {
        val text = GardeningInput.people(listOf(GardeningInput.Person(8, "Father", "father", "")))
        assertEquals("PEOPLE\n- [id 8] Father (father)", text)
    }

    @Test
    fun nothingToTidyMeansNoCall() {
        assertFalse(GardeningInput.worthRunning(listOf(note(1, "2026-10-01")), emptyList(), emptyList()))
        // Two notes he made his own cannot be touched, so they are not worth a call either.
        assertFalse(
            GardeningInput.worthRunning(
                listOf(note(1, "2026-10-01", edited = true), note(2, "2026-10-01", confidence = "confirmed")),
                emptyList(),
                listOf(GardeningInput.Profile("job", "student", true)),
            ),
        )
        assertTrue(GardeningInput.worthRunning(listOf(note(1, "2026-10-01"), note(2, "2026-10-01")), emptyList(), emptyList()))
        assertTrue(GardeningInput.worthRunning(emptyList(), emptyList(), listOf(GardeningInput.Profile("job", "student", false))))
    }

    @Test
    fun oneRunPerMonthAndOneExtraPerWeekWhenFull() {
        assertEquals("gardening:2026-10", GardeningInput.monthKey(today))
        // Saturday 10 Oct 2026 belongs to the week starting Monday 5 Oct.
        assertEquals("gardening:full:2026-10-05", GardeningInput.capKey(today))
        assertEquals("gardening:full:2026-10-05", GardeningInput.capKey(LocalDate.of(2026, 10, 5)))
    }
}
