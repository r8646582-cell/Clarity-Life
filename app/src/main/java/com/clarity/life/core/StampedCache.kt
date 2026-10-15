package com.clarity.life.core

import java.util.concurrent.atomic.AtomicLong

/**
 * One remembered value per key, good until something it was read from changes (improvement report, S3): the
 * caller calls [changed] whenever any of its tables is written, and a value read before that is never handed
 * out after it. A value read while a change lands is not kept. [maxAgeMs] bounds how long any value lives.
 */
class StampedCache<K, V>(private val maxAgeMs: Long, private val clock: () -> Long = System::currentTimeMillis) {
    private val generation = AtomicLong(0)

    private data class Kept<K, V>(val key: K, val generation: Long, val at: Long, val value: V)

    @Volatile private var kept: Kept<K, V>? = null

    fun changed() {
        generation.incrementAndGet()
        kept = null
    }

    suspend fun get(key: K, read: suspend () -> V): V {
        val gen = generation.get()
        val now = clock()
        kept?.let { if (it.key == key && it.generation == gen && now - it.at < maxAgeMs) return it.value }
        val value = read()
        // Kept only if nothing changed while it was read; otherwise the next call reads again.
        if (generation.get() == gen) kept = Kept(key, gen, now, value)
        return value
    }
}
