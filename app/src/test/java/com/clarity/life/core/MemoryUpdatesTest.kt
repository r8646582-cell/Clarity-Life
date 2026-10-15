package com.clarity.life.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryUpdatesTest {

    private val built = 1_000L
    private val noon = MemoryBlocks.Session("2026-10-15", "Revision", "Planned two lectures.", "today", "Thu 15 Oct, 9:05 am to 9:40 am")

    private fun note(id: Long, confidence: String = "guess", seen: Int = 1, text: String = "Studies better in the morning") =
        MemoryBlocks.Note(id, "pattern", confidence, seen, "2026-10-15", "2026-10-15", text, emptyList())

    /** A frozen part as ChatRepository builds it, with notes 1 and 2 and the one session. */
    private val prefix = "persona...\n\n" + listOf(
        MemoryBlocks.notes(listOf(note(1), note(2, text = "Walks help"))),
        MemoryBlocks.recentSessions(listOf(noon)),
    ).joinToString("\n\n") { ContextBuilder.renderBlock(it.name, it.items) }
    private val stamp = MemoryUpdates.stamp(built, listOf(MemoryUpdates.SessionNow(7, noon)))

    private fun block(
        notes: List<MemoryUpdates.NoteNow> = emptyList(),
        people: List<MemoryUpdates.PersonNow> = emptyList(),
        profile: List<MemoryUpdates.ProfileNow> = emptyList(),
        sessions: List<MemoryUpdates.SessionNow> = listOf(MemoryUpdates.SessionNow(7, noon)),
    ) = MemoryUpdates.block(prefix, stamp, notes, people, profile, sessions).items.map { it.text }

    @Test
    fun nothingChangedGivesNoBlock() {
        assertEquals(emptyList<String>(), block(notes = listOf(MemoryUpdates.NoteNow(note(1), 10), MemoryUpdates.NoteNow(note(2, text = "Walks help"), 10))))
    }

    @Test
    fun aNewNoteAndARaisedOneComeButAnOldNoteLeftOutForRoomDoesNot() {
        val lines = block(
            notes = listOf(
                MemoryUpdates.NoteNow(note(1, confidence = "likely", seen = 2), 10),
                MemoryUpdates.NoteNow(note(3, text = "Phone in another room helps"), built + 5),
                MemoryUpdates.NoteNow(note(4, text = "Left out for room"), built - 5),
            ),
        )
        assertEquals(3, lines.size)
        assertEquals(MemoryUpdates.HEAD, lines[0])
        assertTrue(lines[1], lines[1].startsWith("Note, changed: note 1, pattern, likely"))
        assertTrue(lines[2], lines[2].startsWith("Note, new: note 3, pattern, guess"))
    }

    @Test
    fun aNewSummaryAndARewrittenOneComeButOneTheFrozenPartHadDoesNot() {
        val lines = block(
            sessions = listOf(
                MemoryUpdates.SessionNow(8, noon.copy(title = "Power cut", summary = "Slept until 11.")),
                MemoryUpdates.SessionNow(7, noon),
            ),
        )
        assertEquals(2, lines.size)
        assertTrue(lines[1], lines[1].startsWith("Conversation summary, new: ") && lines[1].endsWith("\"Power cut\": Slept until 11."))
        val rewritten = block(sessions = listOf(MemoryUpdates.SessionNow(7, noon.copy(summary = "Planned three lectures."))))
        assertEquals(2, rewritten.size)
    }

    @Test
    fun peopleAndProfileComeWhenWrittenAfterTheFrozenPart() {
        val lines = block(
            people = listOf(
                MemoryUpdates.PersonNow(MemoryBlocks.Person("Hina", "sister", "helps him revise", built + 1)),
                MemoryUpdates.PersonNow(MemoryBlocks.Person("Ali", "friend", "", built - 1)),
            ),
            profile = listOf(MemoryUpdates.ProfileNow(MemoryBlocks.Profile("Work", "Studying full time", false), built)),
        )
        assertEquals(
            listOf(MemoryUpdates.HEAD, "Person, new or changed: Hina (sister): helps him revise", "Profile, new or changed: Work (guess): Studying full time"),
            lines,
        )
    }

    @Test
    fun atMostTwentyLinesTheNewestKept() {
        val many = (10L..40L).map { MemoryUpdates.NoteNow(note(it, text = "n$it"), built + it) }
        val lines = block(notes = many)
        assertEquals(1 + MemoryUpdates.MAX_ITEMS, lines.size)
        assertTrue(lines.last(), lines.last().contains("n40"))
    }
}
