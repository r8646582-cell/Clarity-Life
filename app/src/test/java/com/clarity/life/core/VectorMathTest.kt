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
    fun int8FormIsAQuarterOfTheSizeAndCloseToTheFloats() {
        val rnd = java.util.Random(7)
        repeat(50) {
            val v = VectorMath.normalize(FloatArray(768) { rnd.nextGaussian().toFloat() })
            val w = VectorMath.normalize(FloatArray(768) { rnd.nextGaussian().toFloat() + v[it] * 2 })
            val bytes = VectorMath.toInt8Bytes(v)
            assertEquals(772, bytes.size)
            val back = VectorMath.fromBytes(bytes, 768)!!
            // The cosine a search computes moves far less than the in-memory index's MARGIN.
            assertEquals(VectorMath.dot(v, w), VectorMath.dot(back, w), 0.005)
            for (i in v.indices) assertEquals(v[i], back[i], v.maxOf { x -> kotlin.math.abs(x) } / 127f / 2 + 1e-6f)
        }
    }

    @Test
    fun int8FormKnownAnswerAndBothFormsAreRead() {
        val bytes = VectorMath.toInt8Bytes(floatArrayOf(1.27f, -0.62f, 0f))
        // Scale 0.01 as little-endian float32, then 127, -62, 0.
        assertEquals(0.01f, java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).getFloat(), 1e-9f)
        assertEquals(listOf<Byte>(127, -62, 0), bytes.drop(4))
        assertArrayEquals(floatArrayOf(1.27f, -0.62f, 0f), VectorMath.fromBytes(bytes, 3), 1e-6f)
        assertEquals(true, VectorMath.isFloatForm(VectorMath.toBytes(floatArrayOf(1f, 2f)), 2))
        assertEquals(false, VectorMath.isFloatForm(VectorMath.toInt8Bytes(floatArrayOf(1f, 2f)), 2))
        assertArrayEquals(floatArrayOf(0f, 0f), VectorMath.fromBytes(VectorMath.toInt8Bytes(floatArrayOf(0f, 0f)), 2), 0f)
        assertNull(VectorMath.fromBytes(ByteArray(5), 3))
    }

    @Test
    fun fusionRewardsBeingInBothLists() {
        val keyword = listOf("a", "b", "c")
        val meaning = listOf("c", "d")
        assertEquals(listOf("c", "a", "b", "d"), VectorMath.fuse(keyword, meaning))
        assertEquals(listOf("x"), VectorMath.fuse(listOf("x"), emptyList()))
    }
}
