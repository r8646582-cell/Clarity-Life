package com.clarity.life.memory

import javax.inject.Inject
import javax.inject.Singleton

/**
 * The on-phone model's vectors for the short texts the note checks compare (Max plan Phase 3): a new note
 * against the notes he deleted and the active notes, a merged or rewritten note against the deleted ones.
 * [com.clarity.life.core.JobFields.closeToDeleted] and [com.clarity.life.core.JobFields.duplicateOfActive]
 * use them as well as shared words, so a reworded copy of a note he deleted is never re-created.
 *
 * Kept in memory only, never stored: a deleted note's vector in the database could be found by search. The
 * job compares first (outside any transaction), which fills the cache; the repository's re-check inside the
 * transaction then only embeds what changed while the job ran. When meaning search is off, [of] gives null
 * and the checks fall back to words, as before.
 */
@Singleton
class NoteVectors @Inject constructor(private val embedder: Embedder) {

    private val cache = object : LinkedHashMap<String, FloatArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?) = size > MAX
    }

    /** A unit vector for [text], or null while meaning search is off. Blocking: call it off the main thread. */
    fun of(text: String): FloatArray? {
        synchronized(cache) { cache[text]?.let { return it.copyOf() } }
        val v = embedder.embed(text) ?: return null
        synchronized(cache) { cache[text] = v }
        return v.copyOf()
    }

    companion object {
        /** About 3 MB of vectors: every note of a lifetime's active memory, the deleted ones and a month's new. */
        private const val MAX = 2_000
    }
}
