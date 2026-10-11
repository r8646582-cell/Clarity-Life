package com.clarity.life.core

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The meaning-search vectors kept in memory, so a message does not read every vector from the database
 * (spec section 7). Each unit vector is stored as [dim] signed bytes and one scale, a quarter of its float
 * size: about 23 MB for 60,000 vectors of 384. Scores from here are close to the exact cosine (well within
 * [MARGIN]); the caller takes the best ones and scores them again exactly from the stored floats, so what
 * meaning search finds does not change. Pure, tested on the JVM.
 */
class VectorIndex(private val dim: Int) {

    /** One stored vector: what it is, which conversation it belongs to (-1 for none), and its row. */
    data class Entry(val kind: String, val docId: Long, val conversationId: Long, val rowId: Long)

    data class Hit(val entry: Entry, val approx: Double)

    private var entries = arrayOfNulls<Entry>(64)
    private var data = ByteArray(64 * dim)
    private var scales = FloatArray(64)
    var size = 0
        private set

    /** The highest database row id added so far; new rows are those above it. */
    var maxRowId = 0L
        private set

    fun clear() {
        entries = arrayOfNulls(64)
        data = ByteArray(64 * dim)
        scales = FloatArray(64)
        size = 0
        maxRowId = 0L
    }

    fun add(entry: Entry, vector: FloatArray) {
        require(vector.size == dim) { "vector of the wrong size" }
        if (size == entries.size) grow()
        var max = 0f
        for (x in vector) max = maxOf(max, abs(x))
        val scale = if (max == 0f) 1f else max / 127f
        val base = size * dim
        for (i in 0 until dim) data[base + i] = (vector[i] / scale).roundToInt().coerceIn(-127, 127).toByte()
        scales[size] = scale
        entries[size] = entry
        size++
        if (entry.rowId > maxRowId) maxRowId = entry.rowId
    }

    /**
     * Every vector [accept] lets through whose approximate score is at least [floor], best first, at most
     * [limit]. The query is quantized the same way, so one pass is integer arithmetic.
     */
    fun search(query: FloatArray, floor: Double, limit: Int, accept: (Entry) -> Boolean): List<Hit> {
        require(query.size == dim) { "query of the wrong size" }
        var qmax = 0f
        for (x in query) qmax = maxOf(qmax, abs(x))
        val qscale = if (qmax == 0f) 1f else qmax / 127f
        val q = IntArray(dim) { (query[it] / qscale).roundToInt().coerceIn(-127, 127) }
        val hits = ArrayList<Hit>()
        for (n in 0 until size) {
            val e = entries[n]!!
            if (!accept(e)) continue
            val base = n * dim
            var s = 0
            for (i in 0 until dim) s += q[i] * data[base + i]
            val approx = s.toDouble() * qscale * scales[n]
            if (approx >= floor) hits.add(Hit(e, approx))
        }
        hits.sortByDescending { it.approx }
        return if (hits.size > limit) hits.subList(0, limit).toList() else hits
    }

    private fun grow() {
        val cap = entries.size * 2
        entries = entries.copyOf(cap)
        data = data.copyOf(cap * dim)
        scales = scales.copyOf(cap)
    }

    companion object {
        /**
         * How far below the real threshold a candidate is still kept for exact scoring. The int8 error on a
         * cosine of two 384-dimension unit vectors is far smaller than this.
         */
        const val MARGIN = 0.03
    }
}
