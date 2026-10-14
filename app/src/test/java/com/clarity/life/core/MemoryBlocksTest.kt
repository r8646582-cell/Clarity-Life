package com.clarity.life.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryBlocksTest {

    private fun note(id: Long, confidence: String, seen: Int) = MemoryBlocks.Note(
        id, "pattern", confidence, seen, "2026-09-14", "2026-10-10", "Note $id",
        listOf(
            MemoryBlocks.Evidence("quote", "old words", "2026-09-14"),
            MemoryBlocks.Evidence("count", "tired on 9 of 12 missed evening targets", "2026-10-01"),
            MemoryBlocks.Evidence("quote", "I just never am by 8", "2026-10-07"),
        ),
    )

    @Test
    fun aNoteShowsIdKindGradeSeenAndTwoEvidenceLines() {
        val text = MemoryBlocks.notes(listOf(note(4, "likely", 3))).items.single().text
        assertEquals(
            "note 4, pattern, likely, seen 3 times · Sep to Oct: Note 4\n" +
                "  counted by the app: tired on 9 of 12 missed evening targets\n" +
                "  their words, 7 Oct: \"I just never am by 8\"",
            text,
        )
    }

    @Test
    fun evidenceAndSessionsSayHowLongAgoWhenTheAppCountedIt() {
        val n = MemoryBlocks.Note(
            5, "strength", "likely", 1, "2026-10-13", "2026-10-13", "Held the ramp",
            listOf(MemoryBlocks.Evidence("quote", "keep my 2-3-4", "2026-10-13", "today")),
        )
        assertEquals(
            "note 5, strength, likely, seen once · Oct: Held the ramp\n  their words, 13 Oct (today): \"keep my 2-3-4\"",
            MemoryBlocks.notes(listOf(n)).items.single().text,
        )
        val s = MemoryBlocks.recentSessions(listOf(MemoryBlocks.Session("2026-10-12", "Ramp", "Set a ramp.", "yesterday")))
        assertEquals("12 Oct (yesterday) \"Ramp\": Set a ramp.", s.items.single().text)
        // With the times the app counted, the line says when, not only which day.
        val timed = MemoryBlocks.recentSessions(
            listOf(MemoryBlocks.Session("2026-10-14", "Nap", "Nap until 11.", "today", "Wed 14 Oct, 9:05 am to 9:40 am")),
        )
        assertEquals("Wed 14 Oct, 9:05 am to 9:40 am (today) \"Nap\": Nap until 11.", timed.items.single().text)
    }

    @Test
    fun underPressureSessionsGoFirstThenTheWeakestNotesAndConfirmedLast() {
        val blocks = listOf(
            MemoryBlocks.profile(listOf(MemoryBlocks.Profile("Goal", "Clear the exam", true))),
            MemoryBlocks.notes(listOf(note(1, "confirmed", 1), note(2, "guess", 5), note(3, "guess", 1), note(4, "likely", 1))),
            MemoryBlocks.recentSessions(
                listOf(
                    MemoryBlocks.Session("2026-10-09", "Newest", "b".repeat(200)),
                    MemoryBlocks.Session("2026-10-01", "Oldest", "a".repeat(200)),
                ),
            ),
        )
        val order = mutableListOf<String>()
        var current = blocks
        // Shrink the budget step by step and record what goes, in order.
        for (budget in generateSequence(ContextBuilder.estimateTokens(ContextBuilderRender.render(blocks))) { it - 5 }.take(200)) {
            val kept = ContextBuilder.trimBlocks(current, ContextBuilder.PART2_ORDER, budget).blocks
            val before = current.flatMap { b -> b.items.map { b.name + ":" + it.text.lineSequence().first() } }
            val after = kept.flatMap { b -> b.items.map { b.name + ":" + it.text.lineSequence().first() } }
            order += before - after.toSet()
            current = kept
        }
        assertEquals(
            listOf(
                "RECENT_SESSIONS:1 Oct \"Oldest\": ${"a".repeat(200)}",
                "RECENT_SESSIONS:9 Oct \"Newest\": ${"b".repeat(200)}",
                "NOTES:note 3, pattern, guess, seen once · Sep to Oct: Note 3",
                "NOTES:note 2, pattern, guess, seen 5 times · Sep to Oct: Note 2",
                "NOTES:note 4, pattern, likely, seen once · Sep to Oct: Note 4",
                "NOTES:note 1, pattern, confirmed, seen once · Sep to Oct: Note 1",
                "PROFILE:Goal (confirmed): Clear the exam",
            ),
            order,
        )
    }

    @Test
    fun peopleAndProfileLines() {
        val people = MemoryBlocks.people(
            listOf(
                MemoryBlocks.Person("Hina", "sister", "helps you revise on Sundays", 2),
                MemoryBlocks.Person("Bilal", "", "", 1),
            ),
        )
        assertEquals(listOf("Hina (sister): helps you revise on Sundays", "Bilal"), people.items.map { it.text })
        assertTrue("the least recently mentioned drops first", people.items[1].drop > people.items[0].drop)
        val profile = MemoryBlocks.profile(listOf(MemoryBlocks.Profile("Study time", "Mornings", false)))
        assertEquals("Study time (guess): Mornings", profile.items.single().text)
    }

    @Test
    fun ideasAreDistinctNewestTwentyShownOldestFirst() {
        val tags = (1..25).map { "idea $it" } + "IDEA 1"
        val block = MemoryBlocks.ideas(tags)
        assertEquals(20, block.items.size)
        assertEquals("idea 20", block.items.first().text)
        assertEquals("idea 1", block.items.last().text)
        assertFalse(block.items.any { it.text == "IDEA 1" })
    }

    @Test
    fun sessionsWithoutASummaryAreLeftOut() {
        val block = MemoryBlocks.recentSessions(listOf(MemoryBlocks.Session("2026-10-09", "t", "")))
        assertTrue(block.items.isEmpty())
    }

    @Test
    fun notesAboutTheConversationAreTheLastOfTheirGradeToGo() {
        val notes = listOf(
            MemoryBlocks.Note(1, "pattern", "likely", 9, "2026-09-01", "2026-10-01", "Phone in bed costs the morning", emptyList()),
            MemoryBlocks.Note(2, "what_helps", "likely", 1, "2026-09-01", "2026-10-01", "Lectures go better on the roof", emptyList()),
            MemoryBlocks.Note(3, "pattern", "guess", 1, "2026-09-01", "2026-10-01", "Lectures stall on Tuesdays", emptyList()),
        )
        val relevant = MemoryBlocks.relevantNotes(notes, "how do I get through two lectures today?")
        assertEquals(setOf(2L, 3L), relevant)
        val ranks = MemoryBlocks.notes(notes, relevant).items.map { it.drop }
        assertTrue("a relevant likely note stays longer than one seen more often: $ranks", ranks[1] < ranks[0])
        assertTrue("a relevant guess still goes before any likely note: $ranks", ranks[2] > ranks[0])
        assertEquals(emptySet<Long>(), MemoryBlocks.relevantNotes(notes, "ok"))
    }

    @Test
    fun theNotesAStoredPrefixSendsAreReadBack() {
        val system = "persona text\nnote 9, not a note line outside NOTES\n\n## PROFILE\nname (confirmed): Rafi\n\n" +
            "## NOTES\nnote 4, pattern, likely, seen 3 times: Phone in bed\n  their words, 4 Oct: \"note 7, quoted\"\n" +
            "note 12, what_helps, guess, seen once: The roof\n\n## RECENT_SESSIONS\nnote 5, a summary line"
        assertEquals(setOf(4L, 12L), MemoryBlocks.idsIn(system))
    }
}

/** Renders blocks the way the prefix does, to measure them. */
private object ContextBuilderRender {
    fun render(blocks: List<Block>): String =
        blocks.filter { it.items.isNotEmpty() }.joinToString("\n\n") { ContextBuilder.renderBlock(it.name, it.items) }
}
