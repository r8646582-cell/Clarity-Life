package com.clarity.life.core

import java.text.Normalizer
import java.util.Locale

/**
 * QuoteCheck and NumberCheck: the two gates on AI-written text (letters, reviews, chapters, note evidence).
 *
 * A 1:1 port of tools/textcheck.py; TextCheckTest runs every case in contracts/textcheck_vectors.json.
 *
 * QuoteCheck  a quote shown as his words must appear inside one of his own messages (ignoring case,
 *             spacing, Unicode width forms and curly versus straight quote marks), starting and ending on
 *             word boundaries, so "tired" is never found inside "retired".
 * NumberCheck every number in AI text, in digits or in words, must also appear in the inputs the model was
 *             given. The model may not calculate or invent a figure.
 *
 * Both fail closed: the caller drops or regenerates the text and never repairs it by guessing. Anything the
 * checker cannot read with certainty (an unbalanced quotation mark, a quotation too long to be a quote) fails.
 */
object TextCheck {

    /** Quotes stored as his words must be at least this long; shorter is too generic to be evidence. */
    const val MIN_CHARS = 6

    /** A "quotation" longer than this is not a quote, it is a paraphrase in quotation marks. */
    const val MAX_QUOTE = 400

    private const val TRIM = " \t\n\"'.,;:!?…"
    private val WHITESPACE = Regex("\\s+")

    fun norm(s: String): String {
        // Plain ASCII is already in NFKC form; the normalizer is a native call on Android, and search runs this
        // hundreds of times a message (Max plan Phase 5).
        var out = (if (s.all { it.code < 128 }) s else Normalizer.normalize(s, Normalizer.Form.NFKC)).lowercase(Locale.ROOT)
        out = out.replace('’', '\'').replace('‘', '\'').replace('“', '"').replace('”', '"')
        return WHITESPACE.replace(out, " ").trim()
    }

    private fun trimEnds(s: String): String = s.trim { it in TRIM }

    private fun Char.isAlnum() = Character.isLetterOrDigit(this)

    private fun foundOnWordBoundaries(q: String, hay: String): Pair<Int, Int>? {
        if (q.isEmpty()) return null
        var start = hay.indexOf(q)
        while (start != -1) {
            val end = start + q.length
            val leftOk = !q[0].isAlnum() || start == 0 || !hay[start - 1].isAlnum()
            val rightOk = !q[q.length - 1].isAlnum() || end == hay.length || !hay[end].isAlnum()
            if (leftOk && rightOk) return start to end
            start = hay.indexOf(q, start + 1)
        }
        return null
    }

    private fun wordDist(w1: String, w2: String): Int {
        if (w1 == w2) return 0
        if (Math.abs(w1.length - w2.length) > 1) return 2
        if (w1.startsWith(w2) || w2.startsWith(w1) || w1.endsWith(w2) || w2.endsWith(w1)) return 2
        if (w1.length == w2.length) {
            var diffs = 0
            for (i in w1.indices) {
                if (w1[i] != w2[i]) {
                    diffs++
                    if (diffs > 1) return 2
                }
            }
            return diffs
        }
        val (shorter, longer) = if (w1.length < w2.length) w1 to w2 else w2 to w1
        for (i in longer.indices) {
            val cand = longer.removeRange(i, i + 1)
            if (cand == shorter) return 1
        }
        return 2
    }

    private val WORD_REGEX = Regex("[\\w']+")

    private fun findInMessage(qNorm: String, m: String): String? {
        // 1. Exact substring on word boundaries
        val bounds = foundOnWordBoundaries(qNorm, norm(m))
        if (bounds != null) return m.substring(bounds.first, bounds.second)

        // 2. Tolerant word-by-word match: allows punctuation variations and 1-letter typos
        val qWords = WORD_REGEX.findAll(qNorm).map { it.value }.toList()
        if (qWords.isEmpty()) return null
        val hayMatches = WORD_REGEX.findAll(m).toList()
        if (hayMatches.size < qWords.size) return null

        val k = qWords.size
        for (i in 0..hayMatches.size - k) {
            val window = hayMatches.subList(i, i + k)
            var typos = 0
            var match = true
            for (j in 0 until k) {
                val d = wordDist(qWords[j], window[j].value.lowercase(Locale.ROOT))
                if (d > 1) {
                    match = false
                    break
                }
                typos += d
                if (typos > 1) {
                    match = false
                    break
                }
            }
            if (match) {
                val startIdx = window.first().range.first
                val endIdx = window.last().range.last + 1
                return m.substring(startIdx, endIdx)
            }
        }
        return null
    }

    fun findHisQuote(quote: String, userMessages: List<String>, minChars: Int = 1): String? {
        val qNorm = trimEnds(norm(quote))
        if (qNorm.length < minChars) return null

        for (m in userMessages) {
            val res = findInMessage(qNorm, m)
            if (res != null) return res
        }

        for (i in 0 until userMessages.size - 1) {
            for (j in i + 1 until minOf(i + 3, userMessages.size)) {
                val joined = userMessages.subList(i, j + 1).joinToString(" ")
                val res = findInMessage(qNorm, joined)
                if (res != null) return res
            }
        }
        return null
    }

    fun quoteIsHis(quote: String, userMessages: List<String>, minChars: Int = MIN_CHARS): Boolean {
        val q = trimEnds(norm(quote))
        if (q.length < minChars) return false
        return findHisQuote(quote, userMessages, 1) != null
    }

    fun verifiedQuotes(quotes: List<String>, userMessages: List<String>): List<String> =
        quotes.mapNotNull { q ->
            if (quoteIsHis(q, userMessages)) (findHisQuote(q, userMessages) ?: q) else null
        }

    /**
     * Every phrase the text presents as a quotation, or null when the quotation marks cannot be read with
     * certainty. Straight double quotes pair in order. Curly double quotes must open with " and close with ".
     * A left single curly quote always opens a quotation (an apostrophe is the right one); it closes at the
     * next right single quote that is not followed by a letter or digit. Straight single quotes are
     * apostrophes and are never quotations.
     */
    fun quotedPhrases(text: String): List<String>? {
        val phrases = mutableListOf<String>()
        if (text.count { it == '"' } % 2 != 0) return null
        Regex("\"([^\"]*)\"").findAll(text).forEach { phrases.add(it.groupValues[1]) }
        var depth = 0
        val buf = StringBuilder()
        for (ch in text) {
            when {
                ch == '“' -> {
                    if (depth != 0) return null
                    depth = 1
                    buf.setLength(0)
                }
                ch == '”' -> {
                    if (depth == 0) return null
                    phrases.add(buf.toString())
                    depth = 0
                }
                depth != 0 -> buf.append(ch)
            }
        }
        if (depth != 0) return null
        var i = text.indexOf('‘')
        while (i != -1) {
            var j = i + 1
            while (true) {
                j = text.indexOf('’', j)
                if (j == -1) return null
                if (j + 1 >= text.length || !text[j + 1].isAlnum()) break
                j++
            }
            phrases.add(text.substring(i + 1, j))
            i = text.indexOf('‘', j + 1)
        }
        return phrases
    }

    data class CheckResult(val ok: Boolean, val bad: List<String>)

    /** Check every quoted phrase in AI text. Fragments separated by an ellipsis are each checked. */
    fun verifyQuotesInText(text: String, userMessages: List<String>): CheckResult {
        val phrases = quotedPhrases(text) ?: return CheckResult(false, listOf("unbalanced quotation mark"))
        val bad = mutableListOf<String>()
        for (phrase in phrases) {
            if (phrase.length > MAX_QUOTE) {
                bad.add(phrase.take(40) + "...")
                continue
            }
            for (frag in phrase.split(Regex("\\.\\.\\.|…"))) {
                val f = trimEnds(frag)
                if (f.isEmpty()) continue
                if (!quoteIsHis(f, userMessages, minChars = 1)) {
                    bad.add(phrase)
                    break
                }
            }
        }
        return CheckResult(bad.isEmpty(), bad)
    }

    // ---------- NumberCheck ----------

    private val NUM = Regex("""\d+(?:[.,]\d+)*""")
    private val SMALL = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13,
        "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
    )
    private val TENS = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70,
        "eighty" to 80, "ninety" to 90,
    )
    private val ORD = mapOf(
        "third" to 3, "fourth" to 4, "fifth" to 5, "sixth" to 6, "seventh" to 7, "eighth" to 8, "ninth" to 9,
        "tenth" to 10, "eleventh" to 11, "twelfth" to 12,
    )
    private val MULT = mapOf("twice" to 2, "thrice" to 3)

    // "one", "once", "first", "second", "half" and "a couple" are too common as ordinary words to treat as
    // figures, unless they are part of a larger number ("twenty-one").

    private fun canon(token: String): String {
        val t = token.replace(",", "")
        val dot = t.indexOf('.')
        var whole = if (dot >= 0) t.substring(0, dot) else t
        var frac = if (dot >= 0) t.substring(dot + 1) else ""
        whole = whole.trimStart('0').ifEmpty { "0" }
        frac = frac.trimEnd('0')
        return whole + if (frac.isNotEmpty()) ".$frac" else ""
    }

    private fun wordNumbers(text: String): Set<String> {
        val toks = Regex("[a-z]+").findAll(norm(text)).map { it.value }.toList()
        val out = HashSet<String>()
        var i = 0
        while (i < toks.size) {
            var w = toks[i]
            if (w in MULT) { out.add(MULT.getValue(w).toString()); i++; continue }
            if (w in ORD) { out.add(ORD.getValue(w).toString()); i++; continue }
            if (w !in SMALL && w !in TENS && w !in setOf("hundred", "thousand", "dozen")) { i++; continue }
            var total = 0
            var cur = 0
            val words = mutableListOf<String>()
            var last: String? = null
            while (i < toks.size) {
                w = toks[i]
                when {
                    w in SMALL -> {
                        if ((last == "small" || last == "tens") && !(last == "tens" && SMALL.getValue(w) < 10)) break
                        cur += SMALL.getValue(w)
                        last = "small"
                    }
                    w in TENS -> {
                        if (last == "small" || last == "tens") break
                        cur += TENS.getValue(w)
                        last = "tens"
                    }
                    w == "hundred" -> { cur = maxOf(cur, 1) * 100; last = "mult" }
                    w == "dozen" -> { cur = maxOf(cur, 1) * 12; last = "mult" }
                    w == "thousand" -> { total += maxOf(cur, 1) * 1000; cur = 0; last = "mult" }
                    w == "and" && last == "mult" && i + 1 < toks.size &&
                        (toks[i + 1] in SMALL || toks[i + 1] in TENS) -> {
                        i++
                        continue
                    }
                    else -> break
                }
                words.add(w)
                i++
            }
            if (words == listOf("one")) continue
            out.add((total + cur).toString())
        }
        return out
    }

    fun numbersIn(text: String): Set<String> =
        NUM.findAll(text).map { canon(it.value) }.toSet() + wordNumbers(text)

    /** [inputs] are the strings the model was given. Numbers written in words count, on both sides. */
    fun numberCheck(text: String, inputs: List<String>): CheckResult {
        val allowed = HashSet<String>()
        inputs.forEach { allowed += numbersIn(it) }
        val bad = (numbersIn(text) - allowed).sorted()
        return CheckResult(bad.isEmpty(), bad)
    }
}
