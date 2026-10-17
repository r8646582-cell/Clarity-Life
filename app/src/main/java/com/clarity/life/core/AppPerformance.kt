package com.clarity.life.core

import android.os.SystemClock

/**
 * Lightweight in-memory tracker for phone performance measurements (spec S6: startup time, meaning-model latency, memory).
 * Nothing personal is stored; numbers are shown plainly in Settings > Health.
 */
object AppPerformance {
    private var processStartUptime: Long = SystemClock.uptimeMillis()
    @Volatile
    private var activityStartUptime: Long? = null

    @Volatile
    var startupMs: Long? = null
        private set

    fun markProcessStart() {
        processStartUptime = SystemClock.uptimeMillis()
    }

    fun markActivityCreate() {
        if (activityStartUptime == null) {
            activityStartUptime = SystemClock.uptimeMillis()
        }
    }

    fun markFirstScreen() {
        if (startupMs == null) {
            val start = activityStartUptime ?: processStartUptime
            startupMs = (SystemClock.uptimeMillis() - start).coerceAtLeast(0)
        }
    }
}
