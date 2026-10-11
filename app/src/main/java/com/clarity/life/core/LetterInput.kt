package com.clarity.life.core

/**
 * What the weekly letter is written from (spec section 8, prompts/letter_weekly.md), decided in code.
 *
 * - [worthWriting]: a letter is skipped, with no model call, when the week had no reflected conversation with at
 *   least [MIN_HIS_MESSAGES] of his messages and fewer than [MIN_CHECKIN_DAYS] answered check-in days.
 * - [transcripts]: the week's conversations, oldest first. Full transcripts while they fit in [budgetChars],
 *   the ones with most of his words first; the rest as their summaries. His words are escaped like any context.
 *
 * Pure Kotlin.
 */
object LetterInput {
    const val MIN_HIS_MESSAGES = 4
    const val MIN_CHECKIN_DAYS = 3
    const val TRANSCRIPT_BUDGET_CHARS = 60_000

    data class Conversation(
        val id: Long,
        val day: String,
        val title: String,
        val summary: String,
        val reflected: Boolean,
        /** role ("user" or "coach") and text, in order, only the messages of this week. */
        val messages: List<Pair<String, String>>,
    ) {
        val hisCount: Int get() = messages.count { it.first == "user" }
    }

    fun worthWriting(conversations: List<Conversation>, answeredCheckinDays: Int): Boolean =
        conversations.any { it.reflected && it.hisCount >= MIN_HIS_MESSAGES } || answeredCheckinDays >= MIN_CHECKIN_DAYS

    fun transcripts(conversations: List<Conversation>, budgetChars: Int = TRANSCRIPT_BUDGET_CHARS): String {
        val full = mutableSetOf<Long>()
        var used = 0
        for (c in conversations.sortedWith(compareByDescending<Conversation> { it.hisCount }.thenBy { it.id })) {
            val size = fullText(c).length
            if (used + size <= budgetChars) {
                full += c.id
                used += size
            }
        }
        val parts = conversations.mapNotNull { c ->
            val head = "${c.day}, \"${c.title.ifBlank { "untitled" }}\""
            when {
                c.id in full -> "$head:\n${fullText(c)}"
                c.summary.isNotBlank() -> "$head (summary): ${ContextBuilder.escape(c.summary)}"
                else -> null
            }
        }
        return parts.joinToString("\n\n").ifBlank { "(no conversations this week)" }
    }

    private fun fullText(c: Conversation): String = c.messages.joinToString("\n") { (role, text) ->
        (if (role == "user") "User: " else "Coach: ") + ContextBuilder.escape(text)
    }
}
