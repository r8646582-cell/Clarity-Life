package com.clarity.life

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Work that runs once per process, in a scope that outlives any one screen. The start-up sweep used to run in
 * the screen's own scope: turning the phone (or a dark/light switch) in the first moments rebuilt the screen,
 * cancelled the sweep half way, and the once-per-process flag kept it from running again (bug scan 14 Oct
 * 2026, full scan, finding 2). Tested on the JVM (StartupOnceTest).
 */
class StartupOnce(private val scope: CoroutineScope) {
    private val started = AtomicBoolean(false)

    /** Starts [work] the first time only; later calls return null and do nothing. */
    fun launch(work: suspend () -> Unit): Job? =
        if (started.compareAndSet(false, true)) scope.launch { work() } else null
}
