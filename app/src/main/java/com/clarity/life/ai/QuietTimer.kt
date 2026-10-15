package com.clarity.life.ai

/**
 * The gap timer that only real reply pieces reset (thinking, text, usage, the end, an error object).
 *
 * OkHttp's read timeout alone was not enough: while DeepSeek is busy it sends ": keep-alive" lines for up to
 * 10 minutes before it starts the reply (its rate-limit page), and every such line reset the read timeout, so a
 * reply or Test connection sat on its spinner for that long. Keep-alives, blank lines and pings now count as
 * silence. Pure, so it is tested on the JVM (WireTest).
 */
class QuietTimer(private val gapMs: Long, startMs: Long) {
    private var lastPieceMs = startMs

    /** A real piece of the reply arrived at [nowMs]. */
    fun piece(nowMs: Long) {
        lastPieceMs = nowMs
    }

    /** Only a keep-alive (or a blank line, or a ping) arrived at [nowMs]: true when the gap has run out. */
    fun quietTooLong(nowMs: Long): Boolean = nowMs - lastPieceMs > gapMs

    companion object {
        /** The failure when the provider kept the line open but never sent a piece: it is busy or down. */
        fun busy() = AiFailure("provider_busy", "the provider kept the connection open but sent nothing", retryable = true)
    }
}
