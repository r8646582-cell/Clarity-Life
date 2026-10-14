package com.clarity.life.memory

import androidx.room.InvalidationTracker
import com.clarity.life.core.Spans
import com.clarity.life.core.VectorIndex
import com.clarity.life.core.VectorMath
import com.clarity.life.data.ClarityDatabase
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Meaning-search vectors held in memory ([VectorIndex]), so a message no longer reads up to 60,000 vectors
 * from the database. It is loaded once, and after that only the rows added since are read. When rows were
 * removed (Forget, Re-index, a restore, a summary written again) the count no longer matches and it is
 * loaded again from scratch. Derived data, like the vectors themselves: nothing is lost if it is dropped.
 *
 * Candidates found here are scored again exactly from their stored floats ([exact]), so results are the
 * same as reading every vector.
 */
@Singleton
class VectorCache @Inject constructor(private val db: ClarityDatabase) {

    private val index = VectorIndex(Embedder.DIM)
    private val lock = Mutex()
    private val changed = AtomicBoolean(true)

    /** Every row read so far, of any model, to compare with the table's count. */
    private var seen = 0

    /** The last row read, of any model (the index only keeps the current model's rows). */
    private var lastRowSeen = 0L

    init {
        db.invalidationTracker.addObserver(
            object : InvalidationTracker.Observer(arrayOf("embedding_row")) {
                override fun onInvalidated(tables: Set<String>) = changed.set(true)
            },
        )
    }

    /**
     * The candidates for [query] at or above [floor], best first, at most [limit], with [accept] deciding
     * which entries count (the open conversation is left out by the caller).
     */
    suspend fun search(
        query: FloatArray,
        floor: Double,
        limit: Int,
        accept: (VectorIndex.Entry) -> Boolean,
    ): List<VectorIndex.Hit> = lock.withLock {
        Spans.span("meaning: vectors brought up to date") { refresh() }
        index.search(query, floor - VectorIndex.MARGIN, limit, accept)
    }

    /** The exact cosine from the stored floats, or null when the vector has gone since. */
    suspend fun exact(query: FloatArray, entry: VectorIndex.Entry): Double? {
        val bytes = db.embeddings().vectorOf(entry.kind, entry.docId, Embedder.MODEL, Embedder.DIM) ?: return null
        val v = VectorMath.fromBytes(bytes, Embedder.DIM) ?: return null
        return VectorMath.dot(query, v)
    }

    private suspend fun refresh() {
        // Cleared before reading, so a change made while this reads is picked up next time.
        if (!changed.getAndSet(false)) return
        val e = db.embeddings()
        readNew()
        if (seen != e.countAll()) {
            // Something was removed (or replaced): start again from nothing.
            index.clear()
            seen = 0
            lastRowSeen = 0L
            readNew()
        }
    }

    private suspend fun readNew() {
        val e = db.embeddings()
        var after = lastRowSeen
        while (true) {
            val rows = e.rowsAfter(after, BATCH)
            if (rows.isEmpty()) break
            for (r in rows) {
                seen++
                after = r.rowId
                if (r.model != Embedder.MODEL || r.dim != Embedder.DIM) continue
                val v = VectorMath.fromBytes(r.vector, Embedder.DIM) ?: continue
                index.add(VectorIndex.Entry(r.kind, r.docId, r.conversationId, r.rowId), v)
            }
            if (rows.size < BATCH) break
        }
        lastRowSeen = after
    }

    companion object {
        /** Rows per read: a few hundred kilobytes of vectors at a time. */
        private const val BATCH = 500
    }
}
