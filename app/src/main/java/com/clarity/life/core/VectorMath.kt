package com.clarity.life.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
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

    /** Stored as little-endian float32 in embedding_row.vector. */
    fun toBytes(v: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (x in v) buf.putFloat(x)
        return buf.array()
    }

    fun fromBytes(bytes: ByteArray, dim: Int): FloatArray? {
        if (bytes.size != dim * 4) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(dim) { buf.getFloat() }
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
