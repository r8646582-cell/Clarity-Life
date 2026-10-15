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

    /**
     * [since]: the day key this value was first said (schema 6), null when not known. [before] and [beforeUntil]:
     * the value it replaced and the day it was replaced (profile_past, the newest one), so the coach never takes
     * an old fact for a current one, nor misses that something changed (Max plan Phase 3).
     */
    data class Profile(
        val key: String,
        val value: String,
        val confirmed: Boolean,
        val since: String? = null,
        val before: String? = null,
        val beforeUntil: String? = null,
    )

    data class Person(val name: String, val relation: String, val notes: String, val updatedAt: Long)

    /** [ago]: "today", "2 days ago", from [TimeFacts.ago]; null leaves the line as the day alone. */
    data class Evidence(val kind: String, val text: String, val day: String, val ago: String? = null)

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

    /**
     * [day] is the day key of the conversation's last message. [span] is when it took place, from its first to
     * its last message ([TimeFacts.span]); without it the line names the day alone.
     */
    data class Session(
        val day: String,
        val title: String,
        val summary: String,
        val ago: String? = null,
        val span: String? = null,
    )

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
            val since = e.since?.let { ", since ${SearchText.day(it)}" }.orEmpty()
            val before = if (e.before != null && e.beforeUntil != null) {
                "; before that, until ${SearchText.day(e.beforeUntil)}: ${e.before}"
            } else {
                ""
            }
            BlockItem("${e.key} ($grade$since): ${e.value}$before", (if (e.confirmed) PROFILE_CONFIRMED_RANK else PROFILE_GUESS_RANK) + i + 1)
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

    /**
     * NOTES. [relevant] are the notes that share a word with what the conversation is about (its first message,
     * or the message that rebuilt the prefix; Max plan Phase 2): under a tight budget they are the last of their
     * grade to go. Notes that do not fit can still reach the coach through RELEVANT_PAST ([idsIn]).
     */
    fun formatNote(n: Note): String {
        val head = "note ${n.id}, ${n.kind}, ${n.confidence}, ${KnowText.seen(n.timesSeen, n.firstSeen, n.lastSeen)}: ${n.text}"
        val lines = n.evidence.takeLast(EVIDENCE_LINES).map { e ->
            if (e.kind == "count") "  counted by the app: ${e.text}" else "  their words, ${KnowText.shortDay(e.day)}${e.ago?.let { " ($it)" }.orEmpty()}: \"${e.text}\""
        }
        return (listOf(head) + lines).joinToString("\n")
    }

    /**
     * NOTES. [relevant] are the notes that share a word with what the conversation is about (its first message,
     * or the message that rebuilt the prefix; Max plan Phase 2): under a tight budget they are the last of their
     * grade to go. Notes that do not fit can still reach the coach through RELEVANT_PAST ([idsIn]).
     */
    fun notes(notes: List<Note>, relevant: Set<Long> = emptySet()): Block = Block(
        "NOTES",
        notes.map { n ->
            val base = when (n.confidence) {
                "confirmed" -> CONFIRMED_NOTE_RANK
                "likely" -> LIKELY_RANK
                else -> GUESS_RANK
            }
            // Within a grade: relevant notes go last, then the ones seen most often.
            val step = if (n.id in relevant) 1 else RELEVANT_STEP
            BlockItem(formatNote(n), base + step + (SEEN_CAP - n.timesSeen.coerceIn(1, SEEN_CAP)))
        },
    )

    /**
     * NEW_NOTES (contracts/context.md, Part 3): active notes added since this conversation's prefix was
     * frozen, omitted when empty. Trim: oldest first.
     */
    fun newNotes(notes: List<Note>): Block {
        val sorted = notes.sortedBy { it.id }
        return Block(
            "NEW_NOTES",
            sorted.mapIndexed { i, n ->
                BlockItem(formatNote(n), sorted.size - i)
            },
        )
    }

    private const val RELEVANT_STEP = 500
    private const val SEEN_CAP = 499

    /** The notes that share a searched word with [text] (one is enough: a note is short). */
    fun relevantNotes(notes: List<Note>, text: String): Set<Long> {
        val terms = Retrieval.terms(text)
        if (terms.isEmpty()) return emptySet()
        // The same as Retrieval.score(note, terms) > 0, with the words' stems made once, not once per note.
        val wanted = terms.map { Retrieval.stem(it) }.toSet()
        return notes.filter { n -> Retrieval.stems(n.text).any { it in wanted } }.map { it.id }.toSet()
    }

    private val NOTE_LINE = Regex("^note (\\d+), ")

    /** The ids of the notes a stored system message sends in its NOTES block. */
    fun idsIn(system: String): Set<Long> {
        val out = HashSet<Long>()
        var inNotes = false
        for (line in system.lineSequence()) {
            if (line.startsWith("## ")) {
                inNotes = line.trim() == "## NOTES"
                continue
            }
            if (inNotes) NOTE_LINE.find(line)?.let { out += it.groupValues[1].toLong() }
        }
        return out
    }

    /** [sessions] newest first (by their last message); the oldest of the five is dropped first. */
    fun recentSessions(sessions: List<Session>): Block {
        val kept = sessions.filter { it.summary.isNotBlank() }.take(RECENT_SESSIONS)
        return Block(
            "RECENT_SESSIONS",
            kept.mapIndexed { i, s ->
                val title = if (s.title.isNotBlank()) " \"${s.title}\"" else ""
                BlockItem("${s.span ?: KnowText.shortDay(s.day)}${s.ago?.let { " ($it)" }.orEmpty()}$title: ${s.summary}", SESSIONS_RANK + i + 1)
            }.reversed(), // shown oldest first, like a story
        )
    }

    /** [tags] newest first; shown oldest first; the oldest is dropped first. Repeats are shown once. */
    fun ideas(tags: List<String>): Block {
        val kept = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.take(IDEAS)
        return Block("IDEAS_RECENT", kept.mapIndexed { i, t -> BlockItem(t, IDEAS_RANK + i + 1) }.reversed())
    }
}
