package com.clarity.life.core

/**
 * How long the slow steps take on his own phone (improvement report, S6), shown in Health: building a
 * request, the meaning model's work per message, and the start-up to Talk's home. Only durations under a
 * fixed name are kept, in memory, for the last [KEEP] times each; never any text. A restart starts them over.
 */
object PhoneTimings {
    const val PACKET = "Request built"
    const val EMBED = "Meaning model, one text"
    const val START = "Start-up to home"

    const val KEEP = 50

    private val kept = HashMap<String, ArrayDeque<Long>>()

    fun record(name: String, ms: Long) = synchronized(kept) {
        val q = kept.getOrPut(name) { ArrayDeque() }
        q.addLast(ms.coerceAtLeast(0))
        while (q.size > KEEP) q.removeFirst()
    }

    inline fun <T> timed(name: String, block: () -> T): T {
        val t0 = System.nanoTime()
        try {
            return block()
        } finally {
            record(name, (System.nanoTime() - t0) / 1_000_000)
        }
    }

    fun snapshot(): Map<String, List<Long>> = synchronized(kept) { kept.mapValues { it.value.toList() } }

    fun clear() = synchronized(kept) { kept.clear() }

    /** One Health line per measure that has run: "typical 120 ms, slowest 410 ms (last 12)". */
    fun lines(snapshot: Map<String, List<Long>> = snapshot()): List<Pair<String, String>> =
        listOf(START, PACKET, EMBED).mapNotNull { name ->
            val ms = snapshot[name]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            name to if (ms.size == 1) {
                "${ms[0]} ms (once since the app started)"
            } else {
                "typical ${median(ms)} ms, slowest ${ms.max()} ms (last ${ms.size})"
            }
        }

    /** The middle value; of two middle values, the lower one. */
    fun median(ms: List<Long>): Long = ms.sorted()[(ms.size - 1) / 2]
}
