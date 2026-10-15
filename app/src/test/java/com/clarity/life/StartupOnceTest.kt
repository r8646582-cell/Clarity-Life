package com.clarity.life

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StartupOnceTest {

    @Test
    fun runsOnceAndFinishesWhenTheScreenGoesAway() = runBlocking {
        val once = StartupOnce(CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val runs = AtomicInteger(0)
        val finished = AtomicInteger(0)

        // The first screen starts the sweep, then is destroyed (a turn of the phone) while it runs.
        val screen = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var job: kotlinx.coroutines.Job? = null
        screen.launch {
            job = once.launch {
                runs.incrementAndGet()
                delay(200)
                finished.incrementAndGet()
            }
        }.join()
        screen.cancel()

        // The new screen asks again: nothing new starts.
        assertNull(once.launch { runs.incrementAndGet() })

        job!!.join()
        assertEquals(1, runs.get())
        assertEquals(1, finished.get())
    }
}
