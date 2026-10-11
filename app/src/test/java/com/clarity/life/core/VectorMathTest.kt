package com.clarity.life.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VectorMathTest {

    @Test
    fun normalizeAndDot() {
        val v = VectorMath.normalize(floatArrayOf(3f, 4f))
        assertArrayEquals(floatArrayOf(0.6f, 0.8f), v, 1e-6f)
        assertEquals(1.0, VectorMath.dot(v, v), 1e-6)
        assertArrayEquals(floatArrayOf(0f, 0f), VectorMath.normalize(floatArrayOf(0f, 0f)), 0f)
    }

    @Test
    fun bytesRoundTripAndRejectTheWrongSize() {
        val v = floatArrayOf(0.25f, -1.5f, 3.0e-7f)
        assertArrayEquals(v, VectorMath.fromBytes(VectorMath.toBytes(v), 3), 0f)
        assertNull(VectorMath.fromBytes(VectorMath.toBytes(v), 4))
        // Little-endian, so the stored form is the same on every phone.
        assertEquals(0x00, VectorMath.toBytes(floatArrayOf(1f))[0].toInt())
        assertEquals(0x3F, VectorMath.toBytes(floatArrayOf(1f))[3].toInt())
    }

    @Test
    fun fusionRewardsBeingInBothLists() {
        val keyword = listOf("a", "b", "c")
        val meaning = listOf("c", "d")
        assertEquals(listOf("c", "a", "b", "d"), VectorMath.fuse(keyword, meaning))
        assertEquals(listOf("x"), VectorMath.fuse(listOf("x"), emptyList()))
    }
}
