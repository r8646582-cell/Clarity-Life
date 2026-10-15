package com.clarity.life

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Whether the first real screen (Talk's home) has been drawn in this process. The start-up warm-up (the
 * 147 MB meaning model and every vector) waits for it, so it no longer competes with the home screen for the
 * phone's processor and memory in the first seconds after opening the app.
 */
object FirstScreen : FirstScreenGate()

/** The gate itself, apart from the one the app uses, so it is tested on its own (FirstScreenTest). */
open class FirstScreenGate {
    private val shown = CompletableDeferred<Unit>()

    /** Talk's home is on show. Later calls do nothing. */
    fun mark() {
        com.clarity.life.core.AppPerformance.markFirstScreen()
        shown.complete(Unit)
    }

    /**
     * Waits until the home is on show, then [settleMs] more for its first frames, or at most [maxWaitMs] in all
     * (the app may open on the lock screen or straight into a conversation, where the home never shows).
     */
    suspend fun awaitShown(maxWaitMs: Long = MAX_WAIT_MS, settleMs: Long = SETTLE_MS) {
        withTimeoutOrNull(maxWaitMs) {
            shown.await()
            kotlinx.coroutines.delay(settleMs)
        }
    }

    private companion object {
        const val MAX_WAIT_MS = 10_000L
        const val SETTLE_MS = 1_500L
    }
}
