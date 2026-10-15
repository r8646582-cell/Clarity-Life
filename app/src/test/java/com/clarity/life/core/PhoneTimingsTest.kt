package com.clarity.life.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneTimingsTest {

    @Test
    fun linesShowTheTypicalAndSlowestTimeInAFixedOrder() {
        val lines = PhoneTimings.lines(
            mapOf(
                PhoneTimings.EMBED to listOf(40L, 90L, 60L, 50L),
                PhoneTimings.START to listOf(1800L),
                PhoneTimings.PACKET to emptyList(),
            ),
        )
        assertEquals(
            listOf(
                PhoneTimings.START to "1800 ms (once since the app started)",
                PhoneTimings.EMBED to "typical 50 ms, slowest 90 ms (last 4)",
            ),
            lines,
        )
    }

    @Test
    fun onlyTheLastFiftyAreKept() {
        PhoneTimings.clear()
        repeat(60) { PhoneTimings.record(PhoneTimings.PACKET, it.toLong()) }
        val kept = PhoneTimings.snapshot().getValue(PhoneTimings.PACKET)
        assertEquals(PhoneTimings.KEEP, kept.size)
        assertEquals(10L, kept.first())
        PhoneTimings.clear()
    }

    @Test
    fun timedRecordsEvenWhenTheStepThrows() {
        PhoneTimings.clear()
        try {
            PhoneTimings.timed(PhoneTimings.EMBED) { throw IllegalStateException("x") }
        } catch (e: IllegalStateException) {
            // expected
        }
        assertTrue(PhoneTimings.snapshot().getValue(PhoneTimings.EMBED).size == 1)
        PhoneTimings.clear()
    }
}
