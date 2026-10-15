package com.clarity.life.core

/**
 * Named, content-free timings of the steps inside a request (Max plan Phase 5): the ten-year timing harness
 * (LifetimeTimingTest) sets [sink] and prints how long each step took. In the app [sink] is null and a span
 * costs one read of it. A span carries a fixed step name and a duration; never any text he wrote.
 */
object Spans {

    /** Receives a step name and its duration in nanoseconds; null (off) except in the timing harness. */
    @Volatile
    var sink: ((String, Long) -> Unit)? = null

    inline fun <T> span(name: String, block: () -> T): T {
        val s = sink ?: return block()
        val t0 = System.nanoTime()
        try {
            return block()
        } finally {
            s(name, System.nanoTime() - t0)
        }
    }
}
