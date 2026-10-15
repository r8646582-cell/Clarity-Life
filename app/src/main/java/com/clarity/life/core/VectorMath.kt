package com.clarity.life.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The arithmetic of meaning search: unit vectors, cosine similarity, the stored byte form of a vector, and
 * reciprocal rank fusion of the keyword and meaning result lists (spec section 7). Pure, tested on the JVM.
 * Vectors only find and rank; they never write text and never change a note's confidence.
 */
object VectorMath {

    /** Reciprocal rank fusion constant: the usual 60. */
    const val RRF_K = 60

    fun normalize(v: FloatArray): FloatArray {
        var sum = 0.0
        for (x in v) sum += x.toDouble() * x
        val norm = sqrt(sum)
        if (norm == 0.0 || norm.isNaN()) return v.copyOf()
        return FloatArray(v.size) { (v[it] / norm).toFloat() }
    }

    /** Cosine of two unit vectors is their dot product. */
    fun dot(a: FloatArray, b: FloatArray): Double {
        require(a.size == b.size) { "vectors of different sizes" }
        var s = 0.0
        for (i in a.indices) s += a[i].toDouble() * b[i]
        return s
    }

    /**
     * The stored form since the improvement report (S1): one little-endian float32 scale, then one signed byte per
     * number (the number divided by the scale, rounded, within -127..127). [dim] + 4 bytes instead of [dim] * 4:
     * about a quarter of the space. The in-memory index already worked this way ([VectorIndex]).
     */
    fun toInt8Bytes(v: FloatArray): ByteArray {
        var max = 0f
        for (x in v) max = maxOf(max, abs(x))
        val scale = if (max == 0f || max.isNaN()) 1f else max / 127f
        val buf = ByteBuffer.allocate(4 + v.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putFloat(scale)
        for (x in v) {
            val q = Math.round(x / scale)
            buf.put((if (q > 127) 127 else if (q < -127) -127 else q).toByte())
        }
        return buf.array()
    }

    /** Whether [bytes] is the older float32 form of a [dim]-number vector (made again as int8 by the indexer). */
    fun isFloatForm(bytes: ByteArray, dim: Int): Boolean = bytes.size == dim * 4 && dim > 1

    /** The older stored form: little-endian float32. Still read; written only by tests. */
    fun toBytes(v: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (x in v) buf.putFloat(x)
        return buf.array()
    }

    /** Either stored form (int8 with its scale, or the older float32), as floats; null for any other size. */
    fun fromBytes(bytes: ByteArray, dim: Int): FloatArray? {
        if (bytes.size == dim + 4 && dim > 1) {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val scale = buf.getFloat()
            return FloatArray(dim) { bytes[4 + it] * scale }
        }
        if (bytes.size != dim * 4) return null
        // One bulk copy, not one call per number (Max plan Phase 5: ten years of vectors are 33 million numbers).
        val out = FloatArray(dim)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
        return out
    }

    /**
     * Reciprocal rank fusion: each list is best first; a key's score is the sum of 1 / (k + rank) over the
     * lists it is in. Ties keep the order of first appearance (the keyword list comes first).
     */
    fun <K> fuse(vararg lists: List<K>): List<K> {
        val score = LinkedHashMap<K, Double>()
        for (list in lists) {
            list.forEachIndexed { i, key -> score[key] = (score[key] ?: 0.0) + 1.0 / (RRF_K + i + 1) }
        }
        return score.entries.sortedByDescending { it.value }.map { it.key }
    }
}
