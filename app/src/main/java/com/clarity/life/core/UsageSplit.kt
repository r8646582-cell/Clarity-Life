package com.clarity.life.core

/**
 * Health's cost by job (improvement report, C1): this month's calls and tokens for each kind of call, so a
 * cost decision rests on numbers instead of a guess. Pure, so UsageSplitTest checks the wording.
 */
object UsageSplit {

    data class Kind(val kind: String, val calls: Long, val tokensIn: Long, val tokensOut: Long, val tokensCached: Long)

    /** In the order given (most input first), one line each: "12 calls, 140,000 in (91% cached), 3,200 out". */
    fun lines(kinds: List<Kind>): List<Pair<String, String>> {
        val totalIn = kinds.sumOf { it.tokensIn }
        return kinds.filter { it.calls > 0 }.map { k ->
            val cached = if (k.tokensIn > 0) " (${k.tokensCached * 100 / k.tokensIn}% cached)" else ""
            val share = if (totalIn > 0 && kinds.size > 1) ", ${k.tokensIn * 100 / totalIn}% of all input" else ""
            "  " + (NAMES[k.kind] ?: k.kind) to
                "${k.calls} ${if (k.calls == 1L) "call" else "calls"}, ${"%,d".format(k.tokensIn)} in$cached, " +
                "${"%,d".format(k.tokensOut)} out$share"
        }
    }

    val NAMES = mapOf(
        "chat" to "Chat replies",
        "card_step" to "Card follow-up",
        "reflection" to "Memory (reflection)",
        "review" to "Reviews",
        "letter" to "Letters",
        "chapter" to "Chapters",
        "story" to "Your story",
        "gardening" to "Gardening",
        "bench" to "Test bench",
    )
}
