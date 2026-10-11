package com.clarity.life.core

/**
 * The few marks a coach reply may carry (bold, a heading line, a list line), turned into plain text plus the
 * ranges to draw bold. Anything it does not recognise is left exactly as written. Pure, so it is tested on
 * the JVM; the Talk screen only applies the ranges.
 */
object RichText {

    /** [text] is what is shown; [bold] are the character ranges of it drawn bold. */
    data class Styled(val text: String, val bold: List<IntRange>)

    private val HEADING = Regex("^#{1,6}\\s+(.*)$")
    private val BULLET = Regex("^(\\s*)[-*•]\\s+(.*)$")

    fun parse(raw: String): Styled {
        val out = StringBuilder()
        val bold = mutableListOf<IntRange>()
        for ((n, line) in raw.split("\n").withIndex()) {
            if (n > 0) out.append('\n')
            val heading = HEADING.find(line)
            val bullet = if (heading == null) BULLET.find(line) else null
            val body = when {
                heading != null -> heading.groupValues[1]
                bullet != null -> {
                    out.append(bullet.groupValues[1]).append("•  ")
                    bullet.groupValues[2]
                }
                else -> line
            }
            val start = out.length
            inline(body, out, bold)
            if (heading != null && out.length > start) bold.add(start until out.length)
        }
        return Styled(out.toString(), bold)
    }

    /** Copies [s] to [out] without its matched pairs of two stars, remembering what was between them. */
    private fun inline(s: String, out: StringBuilder, bold: MutableList<IntRange>) {
        var i = 0
        while (i < s.length) {
            val open = s.indexOf("**", i)
            val close = if (open >= 0) s.indexOf("**", open + 2) else -1
            if (open < 0 || close < 0 || close == open + 2) {
                // No pair left (a reply still being written may end in an open mark): show it as it is.
                out.append(s, i, s.length)
                return
            }
            out.append(s, i, open)
            val start = out.length
            out.append(s, open + 2, close)
            bold.add(start until out.length)
            i = close + 2
        }
    }
}
