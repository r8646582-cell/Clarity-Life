package com.clarity.life.core

/**
 * MEMORY_UPDATES (contracts/context.md, Part 3; improvement report C3): what the shared memory gained since this
 * conversation's frozen part was built, sent with each message instead of building that part again at full
 * price. Before, a reflection of another conversation threw every open conversation's frozen part away.
 *
 * Only additions and changes made by a reflection come this way: a new or changed note, a person or a profile
 * entry written after the frozen part was built, and a conversation summary that was not there yet. Anything he
 * edits, confirms or deletes still builds every frozen part again (rule 5), so nothing he removed is sent in its
 * old words. Lines are written by [MemoryBlocks], exactly as the frozen part writes them. Pure, tested on the JVM.
 */
object MemoryUpdates {

    /** Kept with the frozen part: when it was built, and each recent session's summary as it was then. */
    data class Stamp(val builtAt: Long, val sessions: Map<Long, Int>)

    data class NoteNow(val note: MemoryBlocks.Note, val createdAt: Long)

    data class PersonNow(val person: MemoryBlocks.Person)

    data class ProfileNow(val entry: MemoryBlocks.Profile, val updatedAt: Long)

    data class SessionNow(val conversationId: Long, val session: MemoryBlocks.Session)

    /** At most this many lines; the newest changes are the ones kept. Under a few hundred tokens. */
    const val MAX_ITEMS = 20

    /** What [Stamp.sessions] holds for a session: its title and summary, so a rewritten summary counts as new. */
    fun sessionKey(s: MemoryBlocks.Session): Int = (s.title + "\u0000" + s.summary).hashCode()

    /** The stamp for a frozen part built at [builtAt] with these recent sessions in front of it. */
    fun stamp(builtAt: Long, sessions: List<SessionNow>): Stamp =
        Stamp(builtAt, sessions.filter { it.session.summary.isNotBlank() }.associate { it.conversationId to sessionKey(it.session) })

    /** Whether [n] goes in the block: its line in the frozen part changed, or it is newer than the frozen part. */
    fun noteNeeded(prefix: String, sent: Set<Long>, stamp: Stamp, n: NoteNow): Boolean {
        val head = MemoryBlocks.notes(listOf(n.note.copy(evidence = emptyList()))).items.single().text
        return if (n.note.id in sent) !prefix.contains(head) else n.createdAt >= stamp.builtAt
    }

    /**
     * The block, empty when nothing changed. [prefix] is the stored system message. [notes] are the active notes
     * with their evidence; a note the frozen part sends ([MemoryBlocks.idsIn]) counts when its line there is no
     * longer what it would be now (its grade or times seen went up), and one it does not send counts only when it
     * was made after the stamp (an older one was left out for room, and RELEVANT_PAST can still bring it).
     */
    fun block(
        prefix: String,
        stamp: Stamp,
        notes: List<NoteNow>,
        people: List<PersonNow>,
        profile: List<ProfileNow>,
        sessions: List<SessionNow>,
    ): Block {
        val sent = MemoryBlocks.idsIn(prefix)
        val lines = ArrayList<String>()
        for (s in sessions) {
            if (s.session.summary.isBlank()) continue
            if (stamp.sessions[s.conversationId] == sessionKey(s.session)) continue
            lines += "Conversation summary, new: " + MemoryBlocks.recentSessions(listOf(s.session)).items.single().text
        }
        for (n in notes) {
            if (!noteNeeded(prefix, sent, stamp, n)) continue
            val text = MemoryBlocks.notes(listOf(n.note)).items.single().text
            lines += (if (n.note.id in sent) "Note, changed: " else "Note, new: ") + text
        }
        for (p in people) {
            if (p.person.updatedAt >= stamp.builtAt) lines += "Person, new or changed: " + MemoryBlocks.people(listOf(p.person)).items.single().text
        }
        for (e in profile) {
            if (e.updatedAt >= stamp.builtAt) lines += "Profile, new or changed: " + MemoryBlocks.profile(listOf(e.entry)).items.single().text
        }
        if (lines.isEmpty()) return Block(NAME, emptyList())
        val kept = lines.takeLast(MAX_ITEMS)
        return Block(NAME, (listOf(HEAD) + kept).map { BlockItem(it, 0) })
    }

    const val NAME = "MEMORY_UPDATES"
    const val HEAD = "Added to what you know since this conversation began (from their other conversations); where a line here " +
        "and the frozen part above differ, this line is the newer one:"
}
