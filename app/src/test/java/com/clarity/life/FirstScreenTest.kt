package com.clarity.life

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/** The start-up warm-up waits for the home, but never forever (the app may open on the lock screen). */
class FirstScreenTest {

    @Test
    fun theWarmUpWaitsForTheHomeButNeverForever() = runBlocking {
        val gate = FirstScreenGate()
        // Not shown yet: the wait ends at its limit.
        var t = System.nanoTime()
        gate.awaitShown(maxWaitMs = 200, settleMs = 0)
        var waited = (System.nanoTime() - t) / 1_000_000
        assertTrue("waited $waited ms", waited in 150..2_000)

        // Shown: the wait is only the settle time.
        gate.mark()
        gate.mark() // a second home (a turn of the phone) changes nothing
        t = System.nanoTime()
        gate.awaitShown(maxWaitMs = 10_000, settleMs = 50)
        waited = (System.nanoTime() - t) / 1_000_000
        assertTrue("waited $waited ms", waited < 2_000)
    }
}
