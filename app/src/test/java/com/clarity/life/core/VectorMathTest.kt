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
        val v = floatArrayOf(0.25f, -1.5f, 0.05f)
        val decoded = VectorMath.fromBytes(VectorMath.toBytes(v), 3)!!
        assertArrayEquals(v, decoded, 0.02f)
        assertNull(VectorMath.fromBytes(VectorMath.toBytes(v), 4))
        // Format is 4-byte float scale + dim signed int8 bytes (1 byte per dimension).
        val single = floatArrayOf(1f)
        val bytes = VectorMath.toBytes(single)
        assertEquals(5, bytes.size) // 4 bytes scale + 1 byte int8
        assertEquals(127.toByte(), bytes[4])
        // Legacy float32 format (dim * 4 bytes) is still accepted and decoded.
        val legacy = java.nio.ByteBuffer.allocate(3 * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            for (x in v) putFloat(x)
        }.array()
        assertArrayEquals(v, VectorMath.fromBytes(legacy, 3), 0f)
    }

    @Test
    fun fusionRewardsBeingInBothLists() {
        val keyword = listOf("a", "b", "c")
        val meaning = listOf("c", "d")
        assertEquals(listOf("c", "a", "b", "d"), VectorMath.fuse(keyword, meaning))
        assertEquals(listOf("x"), VectorMath.fuse(listOf("x"), emptyList()))
    }
}
