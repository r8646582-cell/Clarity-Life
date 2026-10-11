package com.clarity.life.core

/**
 * What Talk shows of a reply while it is still streaming. Pure, so it is tested on the JVM.
 *
 * A reply ends with its card lines (`[[suggest: {...}]]`, prompts/cards.md). Those are read by
 * [CardParser] once the reply is finished; while it streams they would flash up as raw brackets and then
 * vanish. So everything from the first line that starts a card is held back, and so is a last line that
 * could still turn into one ("[", "[[sug").
 */
object LiveText {

    private const val CARD_START = "[[suggest:"

    fun visible(raw: String): String {
        var start = 0
        while (true) {
            val newline = raw.indexOf('\n', start)
            val end = if (newline < 0) raw.length else newline
            val line = raw.substring(start, end).trimStart()
            val last = newline < 0
            if (line.startsWith("[[") || (last && line.isNotEmpty() && CARD_START.startsWith(line))) {
                return raw.substring(0, start).trimEnd()
            }
            if (last) return raw
            start = end + 1
        }
    }

    /**
     * How many more characters to show on this frame, so text that arrives in bursts still appears at an
     * even pace: a small backlog shows a character or two a frame, a big one catches up within a few frames.
     */
    fun step(backlog: Int): Int = when {
        backlog <= 0 -> 0
        else -> maxOf(1, (backlog + 5) / 6)
    }
}
