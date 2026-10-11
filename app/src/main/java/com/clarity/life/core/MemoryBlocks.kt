package com.clarity.life.core

/**
 * The memory blocks of Part 2 of the context packet (contracts/context.md): PROFILE, PEOPLE, NOTES,
 * RECENT_SESSIONS and IDEAS_RECENT, written by code from the stored rows. Pure, tested on the JVM
 * (MemoryBlocksTest).
 *
 * Every item carries a drop rank (0 = never). [ContextBuilder.trimBlocks] drops the highest rank first, so the
 * ranks below give the order the spec asks for when the 9,000-token budget is tight (section 3): the oldest
 * session summaries first, then old idea tags, then the notes with the least behind them (guess before likely
 * before confirmed, fewer times seen first), then people least recently mentioned, then profile guesses, and
 * what he confirmed last.
 */
object MemoryBlocks {

    data class Profile(val key: String, val value: String, val confirmed: Boolean)

    data class Person(val name: String, val relation: String, val notes: String, val updatedAt: Long)

    data class Evidence(val kind: String, val text: String, val day: String)

    data class Note(
        val id: Long,
        val kind: String,
        val confidence: String,
        val timesSeen: Int,
        val firstSeen: String,
        val lastSeen: String,
        val text: String,
        /** Newest last; at most [EVIDENCE_LINES] of them are sent. */
        val evidence: List<Evidence>,
    )

    /** [day] is the day key the conversation started on. */
    data class Session(val day: String, val title: String, val summary: String)

    const val EVIDENCE_LINES = 2
    const val RECENT_SESSIONS = 5
    const val IDEAS = 20
    const val PROFILE_MAX = 30

    private const val SESSIONS_RANK = 9000
    private const val IDEAS_RANK = 8000
    private const val GUESS_RANK = 7000
    private const val LIKELY_RANK = 6000
    private const val PEOPLE_RANK = 5000
    private const val PROFILE_GUESS_RANK = 4000
    private const val CONFIRMED_NOTE_RANK = 3000
    private const val PROFILE_CONFIRMED_RANK = 2000

    fun profile(entries: List<Profile>): Block = Block(
        "PROFILE",
        entries.take(PROFILE_MAX).mapIndexed { i, e ->
            val grade = if (e.confirmed) "confirmed" else "guess"
            BlockItem("${e.key} ($grade): ${e.value}", (if (e.confirmed) PROFILE_CONFIRMED_RANK else PROFILE_GUESS_RANK) + i + 1)
        },
    )

    /** Least recently mentioned is dropped first. */
    fun people(people: List<Person>): Block {
        val newestFirst = people.sortedByDescending { it.updatedAt }
        return Block(
            "PEOPLE",
            newestFirst.mapIndexed { i, p ->
                val who = if (p.relation.isNotBlank()) "${p.name} (${p.relation})" else p.name
                BlockItem(if (p.notes.isNotBlank()) "$who: ${p.notes}" else who, PEOPLE_RANK + i + 1)
            },
        )
    }

    fun notes(notes: List<Note>): Block = Block(
        "NOTES",
        notes.map { n ->
            val head = "note ${n.id}, ${n.kind}, ${n.confidence}, ${KnowText.seen(n.timesSeen, n.firstSeen, n.lastSeen)}: ${n.text}"
            val lines = n.evidence.takeLast(EVIDENCE_LINES).map { e ->
                if (e.kind == "count") "  counted by the app: ${e.text}" else "  their words, ${KnowText.shortDay(e.day)}: \"${e.text}\""
            }
            val base = when (n.confidence) {
                "confirmed" -> CONFIRMED_NOTE_RANK
                "likely" -> LIKELY_RANK
                else -> GUESS_RANK
            }
            BlockItem((listOf(head) + lines).joinToString("\n"), base + (999 - n.timesSeen.coerceIn(1, 999)) + 1)
        },
    )

    /** [sessions] newest first; the oldest of the five is dropped first. */
    fun recentSessions(sessions: List<Session>): Block {
        val kept = sessions.filter { it.summary.isNotBlank() }.take(RECENT_SESSIONS)
        return Block(
            "RECENT_SESSIONS",
            kept.mapIndexed { i, s ->
                val title = if (s.title.isNotBlank()) " \"${s.title}\"" else ""
                BlockItem("${KnowText.shortDay(s.day)}$title: ${s.summary}", SESSIONS_RANK + i + 1)
            }.reversed(), // shown oldest first, like a story
        )
    }

    /** [tags] newest first; shown oldest first; the oldest is dropped first. Repeats are shown once. */
    fun ideas(tags: List<String>): Block {
        val kept = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.take(IDEAS)
        return Block("IDEAS_RECENT", kept.mapIndexed { i, t -> BlockItem(t, IDEAS_RANK + i + 1) }.reversed())
    }
}
