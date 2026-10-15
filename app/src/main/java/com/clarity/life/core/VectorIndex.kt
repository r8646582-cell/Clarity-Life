package com.clarity.life.core

import kotlin.math.abs

/**
 * The meaning-search vectors kept in memory, so a message does not read every vector from the database
 * (spec section 7). Each unit vector is stored as [dim] signed bytes and one scale, a quarter of its float
 * size: about 46 MB for 60,000 vectors of 768 (the model since Max plan Phase 4). Scores from here are close to the exact cosine (well within
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

    /**
     * Room for [count] vectors in all, made once (Max plan Phase 5): growing by doubling copied tens of megabytes
     * several times at ten years, and briefly held the old and the new copy together.
     */
    fun reserve(count: Int) {
        if (count <= entries.size) return
        entries = entries.copyOf(count)
        data = data.copyOf(count * dim)
        scales = scales.copyOf(count)
    }

    fun add(entry: Entry, vector: FloatArray) {
        require(vector.size == dim) { "vector of the wrong size" }
        if (size == entries.size) grow()
        var max = 0f
        for (x in vector) {
            val a = abs(x)
            if (a > max) max = a
        }
        val scale = if (max == 0f) 1f else max / 127f
        val base = size * dim
        for (i in 0 until dim) {
            val q = Math.round(vector[i] / scale)
            data[base + i] = (if (q > 127) 127 else if (q < -127) -127 else q).toByte()
        }
        scales[size] = scale
        entries[size] = entry
        size++
        if (entry.rowId > maxRowId) maxRowId = entry.rowId
    }

    /** Adds an already quantized int8 vector and its float scale directly without re-quantization. */
    fun addQuantized(entry: Entry, scale: Float, quantized: ByteArray, offset: Int = 0) {
        require(quantized.size - offset >= dim) { "quantized vector of the wrong size" }
        if (size == entries.size) grow()
        val base = size * dim
        System.arraycopy(quantized, offset, data, base, dim)
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
        val q = IntArray(dim) { Math.round(query[it] / qscale).coerceIn(-127, 127) }
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
         * cosine of two 768-dimension unit vectors is far smaller than this.
         */
        const val MARGIN = 0.03
    }
}
