package com.clarity.life.core

import kotlin.math.ceil

/**
 * The context packet: what the app sends with every chat request.
 *
 * A 1:1 port of tools/context.py (the why is in contracts/context.md); ContextBuilderTest runs every case
 * in contracts/context_vectors.json.
 *
 * Layout, longest stable text first so the provider's prefix cache is hit on almost every request:
 *   system   = Part 1 (persona, examples, cards, chat-type file) + Part 2 (frozen context blocks)
 *   messages = the history window
 *   last one = Part 3 (volatile blocks) + "## MESSAGE" + his new text
 *
 * Pure functions only: no Android, no clock, no database.
 */
class ContextError(message: String) : Exception(message)

/** One line of a context block. [drop] is its trim rank: 0 means never drop, higher goes first. */
data class BlockItem(val text: String, val drop: Int)

data class Block(val name: String, val items: List<BlockItem>)

data class Prefix(val system: String, val dropped: Int, val overBudget: Boolean)

data class UserTurn(val content: String, val dropped: Int, val overBudget: Boolean)

data class HistoryMessage(val role: String, val content: String)

data class TrimResult(val blocks: List<Block>, val dropped: Int, val overBudget: Boolean)

object ContextBuilder {
    val PART2_ORDER = listOf(
        "FLAGS", "PROFILE", "PEOPLE", "NOTES", "FOCUS", "CHAPTERS", "RECENT_SESSIONS", "IDEAS_RECENT",
        "LETTER", "REVIEW",
    )
    val PART3_ORDER = listOf(
        "NOW", "PLAN_STATE", "OPEN_PROMISES", "CHECKIN_FACTS", "CARD_RESULTS", "EARLIER", "LAST_CHAT",
        "RELEVANT_PAST",
    )
    val NEVER_TRIM = setOf(
        "FLAGS", "FOCUS", "LETTER", "REVIEW", "NOW", "PLAN_STATE", "OPEN_PROMISES", "CHECKIN_FACTS",
        "CARD_RESULTS",
    )
    const val MESSAGE_HEADER = "## MESSAGE"

    const val PART2_BUDGET = 9000
    const val PART3_BUDGET = 4000
    const val HISTORY_BUDGET = 24000

    /** The newest messages are always sent, even over budget. */
    const val HISTORY_MIN_KEEP = 6

    /** When the window must move, it moves until the history is at most this share of the budget. */
    const val HISTORY_REFILL = 0.75

    /**
     * Estimated tokens (tools/context.py estimate_tokens): English letters, spaces and signs at 3 characters a
     * token, every digit 1, every other character (an emoji, an accented letter) 2, counted by code point as
     * Python counts them. Measured against DeepSeek's tokenizer: plain English about 1.4 times its real count, and
     * numbers, dates and emoji no longer under-counted.
     */
    fun estimateTokens(text: String): Int {
        var digits = 0
        var other = 0
        var all = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            all++
            if (cp in '0'.code..'9'.code) digits++ else if (cp > 127) other++
            i += Character.charCount(cp)
        }
        return ceil((all - digits - other) / 3.0 + digits + 2 * other).toInt()
    }

    /** Make text from him or from the model safe inside a block: it can never open a card or a block. */
    fun escape(text: String): String {
        var t = text
        while (t.contains("[[") || t.contains("]]")) {
            t = t.replace("[[", "[ [").replace("]]", "] ]")
        }
        return t.split("\n").joinToString("\n") { line ->
            val stripped = line.trimStart()
            if (stripped.startsWith("## ") || stripped == "##") "  $line" else line
        }
    }

    fun renderBlock(name: String, items: List<BlockItem>): String =
        "## $name\n" + items.joinToString("\n") { escape(it.text) }

    private fun render(blocks: List<Block>, order: List<String>): String {
        val present = LinkedHashMap<String, Block>()
        for (b in blocks) if (b.items.isNotEmpty()) present[b.name] = b
        for (name in present.keys) if (name !in order) throw ContextError("unknown block $name")
        return order.filter { it in present }.joinToString("\n\n") { renderBlock(it, present.getValue(it).items) }
    }

    /**
     * Drop items until the rendered blocks fit the budget. Ties drop the item that comes first
     * (block order, then position); blocks in [NEVER_TRIM] are never touched.
     */
    fun trimBlocks(blocks: List<Block>, order: List<String>, budget: Int): TrimResult {
        val names = blocks.map { it.name }
        val items = blocks.map { it.items.toMutableList() }
        fun snapshot() = names.indices.map { Block(names[it], items[it].toList()) }
        var dropped = 0
        while (estimateTokens(render(snapshot(), order)) > budget) {
            var bestBlock = -1
            var bestIndex = -1
            var bestRank = 0
            for (b in names.indices.sortedBy { order.indexOf(names[it]) }) {
                if (names[b] in NEVER_TRIM) continue
                for ((idx, item) in items[b].withIndex()) {
                    if (item.drop > 0 && item.drop > bestRank) {
                        bestBlock = b
                        bestIndex = idx
                        bestRank = item.drop
                    }
                }
            }
            if (bestBlock < 0) return TrimResult(snapshot(), dropped, true)
            items[bestBlock].removeAt(bestIndex)
            dropped++
        }
        return TrimResult(snapshot(), dropped, false)
    }

    /** The system message: Part 1 then Part 2. [mode] is null for free talk. */
    fun buildPrefix(
        persona: String,
        examples: String,
        cards: String,
        mode: String?,
        part2Blocks: List<Block>,
    ): Prefix {
        val part1 = listOfNotNull(persona, examples, cards, mode)
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")
        val trimmed = trimBlocks(part2Blocks, PART2_ORDER, PART2_BUDGET)
        val part2 = render(trimmed.blocks, PART2_ORDER)
        return Prefix(
            system = part1 + if (part2.isNotEmpty()) "\n\n$part2" else "",
            dropped = trimmed.dropped,
            overBudget = trimmed.overBudget,
        )
    }

    /**
     * Index of the first history message to send. It only moves forward, and only when the window is over
     * budget; then it moves until the window is at most [refill] x budget, never past the newest [minKeep].
     */
    fun nextHistoryStart(
        tokenCounts: List<Int>,
        start: Int,
        budget: Int = HISTORY_BUDGET,
        minKeep: Int = HISTORY_MIN_KEEP,
        refill: Double = HISTORY_REFILL,
    ): Int {
        val n = tokenCounts.size
        var s = start.coerceIn(0, n)
        fun tail(from: Int) = tokenCounts.drop(from).sum()
        if (tail(s) <= budget) return s
        val limit = maxOf(0, n - minKeep)
        while (s < limit && tail(s) > refill * budget) s++
        return s
    }

    fun buildUserTurn(part3Blocks: List<Block>, text: String): UserTurn {
        val trimmed = trimBlocks(part3Blocks, PART3_ORDER, PART3_BUDGET)
        val blocks = render(trimmed.blocks, PART3_ORDER)
        val body = MESSAGE_HEADER + "\n" + escape(text)
        return UserTurn(
            content = (if (blocks.isNotEmpty()) "$blocks\n\n" else "") + body,
            dropped = trimmed.dropped,
            overBudget = trimmed.overBudget,
        )
    }

    /**
     * A stored message as it is sent again later. His messages carry the stamp the app wrote when he sent it
     * ([Fri 9 Oct, 21:40]). Coach messages go as they were shown, escaped, and then the cards that reply offered
     * are put back as card lines, from the stored, already validated card JSON ([cards], in order). Without them
     * the coach reads its own past replies as "tap it below" with no card line, copies that, and stops sending
     * cards. A stored card that holds a bracket pair or a line break is left out, so a card line is never mangled.
     */
    fun historyMessage(role: String, text: String, stamp: String? = null, cards: List<String> = emptyList()): HistoryMessage =
        when (role) {
            "user" -> HistoryMessage("user", if (stamp != null) "[$stamp] ${escape(text)}" else escape(text))
            "coach" -> {
                val lines = mutableListOf<String>()
                if (text.isNotBlank()) lines.add(escape(text))
                for (payload in cards) {
                    if (payload.contains("[[") || payload.contains("]]") || payload.contains('\n') || payload.contains('\r')) continue
                    lines.add("[[suggest: $payload]]")
                }
                HistoryMessage("assistant", lines.joinToString("\n"))
            }
            else -> throw ContextError("bad role")
        }
}
