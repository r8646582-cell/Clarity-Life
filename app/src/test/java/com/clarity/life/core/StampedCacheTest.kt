package com.clarity.life.core

import org.junit.Assert.assertEquals
import kotlin.coroutines.startCoroutine
import org.junit.Test

class StampedCacheTest {

    /** Runs a block that never really suspends (no coroutine library in core's tests). */
    private fun now(block: suspend () -> Unit) {
        var failure: Throwable? = null
        block.startCoroutine(kotlin.coroutines.Continuation(kotlin.coroutines.EmptyCoroutineContext) { r -> failure = r.exceptionOrNull() })
        failure?.let { throw it }
    }

    @Test
    fun readsOnceUntilSomethingChanges() = now {
        var reads = 0
        val cache = StampedCache<String, Int>(60_000)
        assertEquals(1, cache.get("2026-10-15") { ++reads })
        assertEquals(1, cache.get("2026-10-15") { ++reads })
        cache.changed()
        assertEquals(2, cache.get("2026-10-15") { ++reads })
        assertEquals(2, reads)
    }

    @Test
    fun anotherDayReadsAgain() = now {
        var reads = 0
        val cache = StampedCache<String, Int>(60_000)
        cache.get("2026-10-15") { ++reads }
        assertEquals(2, cache.get("2026-10-16") { ++reads })
    }

    @Test
    fun aValueReadWhileAChangeLandsIsNotKept() = now {
        var reads = 0
        val cache = StampedCache<String, Int>(60_000)
        cache.get("d") { ++reads; cache.changed(); reads }
        assertEquals(2, cache.get("d") { ++reads })
    }

    @Test
    fun anOldValueIsReadAgain() = now {
        var clock = 0L
        var reads = 0
        val cache = StampedCache<String, Int>(1_000) { clock }
        cache.get("d") { ++reads }
        clock = 999
        assertEquals(1, cache.get("d") { ++reads })
        clock = 1_000
        assertEquals(2, cache.get("d") { ++reads })
    }
}
