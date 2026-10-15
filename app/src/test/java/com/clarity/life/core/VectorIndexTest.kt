package com.clarity.life.core

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorIndexTest {

    private val dim = 768 // the app's model since Max plan Phase 4 (Embedder.DIM)

    private fun unit(r: Random) = VectorMath.normalize(FloatArray(dim) { (r.nextDouble() * 2 - 1).toFloat() })

    /** A vector that is [cos] alike to [base] (base mixed with fresh noise). */
    private fun near(base: FloatArray, r: Random, mix: Double): FloatArray {
        val noise = unit(r)
        return VectorMath.normalize(FloatArray(dim) { (base[it] * (1 - mix) + noise[it] * mix).toFloat() })
    }

    @Test
    fun approximateScoresStayWithinTheMargin() {
        val r = Random(7)
        val index = VectorIndex(dim)
        val q = unit(r)
        val vectors = List(2000) { i -> if (i % 3 == 0) near(q, r, r.nextDouble()) else unit(r) }
        vectors.forEachIndexed { i, v -> index.add(VectorIndex.Entry("message", i.toLong(), 1, i + 1L), v) }
        val hits = index.search(q, -1.0, vectors.size) { true }
        assertEquals(vectors.size, hits.size)
        var worst = 0.0
        for (h in hits) worst = maxOf(worst, kotlin.math.abs(h.approx - VectorMath.dot(q, vectors[h.entry.docId.toInt()])))
        assertTrue("worst error $worst", worst < VectorIndex.MARGIN / 3)
    }

    @Test
    fun nothingAboveTheThresholdIsMissedWithTheMargin() {
        val r = Random(11)
        val index = VectorIndex(dim)
        val q = unit(r)
        val vectors = List(3000) { near(q, r, r.nextDouble()) }
        vectors.forEachIndexed { i, v -> index.add(VectorIndex.Entry("summary", i.toLong(), i.toLong(), i + 1L), v) }
        val exact = vectors.indices.filter { VectorMath.dot(q, vectors[it]) >= Retrieval.SEMANTIC_MIN }.toSet()
        assertTrue("the test needs matches", exact.size > 50)
        val found = index.search(q, Retrieval.SEMANTIC_MIN - VectorIndex.MARGIN, vectors.size) { true }
            .map { it.entry.docId.toInt() }.toSet()
        assertTrue(found.containsAll(exact))
    }

    @Test
    fun theFilterTheLimitAndTheRowsWork() {
        val r = Random(3)
        val index = VectorIndex(dim)
        val q = unit(r)
        index.add(VectorIndex.Entry("message", 1, 5, 10), q)
        index.add(VectorIndex.Entry("message", 2, 6, 12), q)
        index.add(VectorIndex.Entry("quote", 3, -1, 11), near(q, r, 0.2))
        assertEquals(12, index.maxRowId)
        assertEquals(3, index.size)
        // The open conversation (5) is left out.
        assertEquals(listOf(2L, 3L), index.search(q, 0.0, 10) { it.conversationId != 5L }.map { it.entry.docId })
        assertEquals(1, index.search(q, 0.0, 1) { true }.size)
        index.clear()
        assertEquals(0, index.size)
        assertEquals(0, index.maxRowId)
    }

    @Test
    fun itGrowsPastItsFirstSize() {
        val r = Random(5)
        val index = VectorIndex(dim)
        repeat(500) { index.add(VectorIndex.Entry("event", it.toLong(), -1, it + 1L), unit(r)) }
        assertEquals(500, index.size)
    }

    @Test
    fun roomReservedFirstGivesTheSameScores() {
        val r = Random(8)
        val vectors = List(300) { unit(r) }
        val grown = VectorIndex(dim)
        val reserved = VectorIndex(dim).apply { reserve(300) }
        vectors.forEachIndexed { i, v ->
            grown.add(VectorIndex.Entry("message", i.toLong(), 1, i + 1L), v)
            reserved.add(VectorIndex.Entry("message", i.toLong(), 1, i + 1L), v)
        }
        // Past the reserved room it still grows.
        reserved.add(VectorIndex.Entry("message", 300, 1, 301), vectors[0])
        assertEquals(301, reserved.size)
        val q = vectors[7]
        assertEquals(grown.search(q, -1.0, 300) { true }, reserved.search(q, -1.0, 300) { it.docId < 300 })
    }

    @Test
    fun addQuantizedGivesSameSearchResults() {
        val r = Random(9)
        val vectors = List(50) { unit(r) }
        val indexAdd = VectorIndex(dim)
        val indexQuantized = VectorIndex(dim)
        vectors.forEachIndexed { i, v ->
            indexAdd.add(VectorIndex.Entry("message", i.toLong(), 1, i + 1L), v)
            val bytes = VectorMath.toBytes(v)
            val scale = java.nio.ByteBuffer.wrap(bytes, 0, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).float
            indexQuantized.addQuantized(VectorIndex.Entry("message", i.toLong(), 1, i + 1L), scale, bytes, 4)
        }
        val q = vectors[3]
        val hitsA = indexAdd.search(q, -1.0, 50) { true }
        val hitsB = indexQuantized.search(q, -1.0, 50) { true }
        assertEquals(hitsA.size, hitsB.size)
        for (i in hitsA.indices) {
            assertEquals(hitsA[i].entry.docId, hitsB[i].entry.docId)
            assertEquals(hitsA[i].approx, hitsB[i].approx, 1e-6)
        }
    }
}
