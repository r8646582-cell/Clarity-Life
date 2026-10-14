package com.clarity.life.core

import java.time.Duration
import java.time.Instant

/**
 * LAST_CHAT (contracts/context.md, Part 3): the words of the conversation he was just in, so a new one never
 * starts blind. Typing on Talk home always starts a new conversation, and Android often closes the app while
 * he sleeps or naps; the summary of the one before is written by a background job that takes a while, so
 * without this block the coach could greet him knowing nothing of a plan made minutes earlier.
 *
 * Pure, tested on the JVM (LastChatTest). Every time and "ago" is counted here (rule 2).
 */
object LastChat {

    /** Shown while the other conversation's last message is younger than this. */
    const val HOURS = 6L
    const val MESSAGES = 8
    const val CHARS = 500

    data class Msg(val role: String, val text: String, val at: Long)

    /** True when a conversation whose last message was at [lastAt] still belongs in [now]'s request. */
    fun fresh(lastAt: Long, now: Long): Boolean = now >= lastAt && now - lastAt < Duration.ofHours(HOURS).toMillis()

    /**
     * [messages] oldest first: his and the coach's (finished or kept replies only). The header is never
     * dropped; the oldest message is the first to go when the budget is tight, after every search result.
     */
    fun block(
        title: String,
        firstAt: Long,
        lastAt: Long,
        messages: List<Msg>,
        now: Long,
        tz: String,
        boundaryHour: Int,
    ): Block {
        val kept = messages.filter { it.text.isNotBlank() }.takeLast(MESSAGES)
        if (kept.isEmpty()) return Block("LAST_CHAT", emptyList())
        val named = if (title.isNotBlank()) "Your other conversation \"$title\"" else "Your other conversation"
        val day = Planner.dayKey(Instant.ofEpochMilli(lastAt).toString(), tz, boundaryHour)
        val today = Planner.dayKey(Instant.ofEpochMilli(now).toString(), tz, boundaryHour)
        val span = TimeFacts.span(Instant.ofEpochMilli(firstAt), Instant.ofEpochMilli(lastAt), tz)
        val ago = TimeFacts.spell(Duration.ofMillis(now - lastAt))
        val head = "$named, $span (${TimeFacts.ago(day, today)}); its last message was $ago ago. Its last messages:"
        val nowDate = TimeFacts.local(Instant.ofEpochMilli(now), tz).toLocalDate()
        val lines = kept.mapIndexed { i, m ->
            val text = m.text.replace(Regex("\\s+"), " ").trim()
            val clipped = if (text.length > CHARS) text.take(CHARS).trimEnd() + "..." else text
            val line = if (m.role == "user") {
                val t = TimeFacts.local(Instant.ofEpochMilli(m.at), tz)
                // Within a few hours, the clock time says it; across midnight the day comes too.
                val stamp = if (t.toLocalDate() == nowDate) TimeFacts.clock(t) else TimeFacts.cardTime(Instant.ofEpochMilli(m.at), tz)
                "[$stamp] They: $clipped"
            } else {
                "Coach: $clipped"
            }
            BlockItem(line, kept.size - i)
        }
        return Block("LAST_CHAT", listOf(BlockItem(head, 0)) + lines)
    }
}
