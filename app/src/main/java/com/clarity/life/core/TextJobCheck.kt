package com.clarity.life.core

import com.clarity.life.core.JobFields.pyLen
import com.clarity.life.core.JobFields.strip
import com.clarity.life.core.JobFields.words

/**
 * Checks AI-written text before it is published: the weekly review note, the letter, the chapter, the
 * morning brief, the monthly review and the monthly story. A 1:1 port of `check_text_job`, `apply_monthly_review` and
 * `apply_story` in tools/appliers.py.
 *
 * Every quotation must be his own words (QuoteCheck), every number must be in the inputs the model was given
 * (NumberCheck), and the text must be plain prose (no brackets, no dashes used as punctuation, no markdown).
 * A failed check means the text is not published; it is never repaired by guessing.
 */
object TextJobCheck {

    data class TextResult(val ok: Boolean, val problems: List<String>, val title: String, val body: String)

    data class Revisit(val ref: String?, val question: String)

    data class MonthlyResult(
        val ok: Boolean,
        val problems: List<String>,
        val headline: String = "",
        val narrative: String = "",
        val revisit: List<Revisit> = emptyList(),
    )

    private val LIST_OR_HEADING = Regex("^\\s*([-*•]\\s|\\d+[.\\)]\\s|#+\\s)", RegexOption.MULTILINE)
    private val CHAPTER_BULLET = Regex("^\\s*-\\s", RegexOption.MULTILINE)

    private fun plainTextProblems(text: String): List<String> {
        val probs = mutableListOf<String>()
        if ("[[" in text || "]]" in text) probs.add("brackets")
        if ('—' in text || '–' in text) probs.add("em dash")
        if (LIST_OR_HEADING.containsMatchIn(text) || "**" in text) probs.add("markdown list, heading or bold")
        return probs
    }

    /** The morning brief: a few lines, never a letter (BRIEF_WORDS in tools/appliers.py). */
    val BRIEF_WORDS = 15 to 90

    /** [kind] is review_weekly, letter, chapter or brief. [inputs] = every string the model was given. */
    fun check(kind: String, rawText: String, inputs: List<String>, hisMessages: List<String>): TextResult {
        val (lo, hi) = when (kind) {
            "review_weekly" -> 30 to 230
            "letter", "chapter" -> 120 to 700
            "brief" -> BRIEF_WORDS
            else -> throw IllegalArgumentException("unknown text job $kind")
        }
        val text = strip(rawText)
        var title = ""
        var body = text
        val probs = mutableListOf<String>()
        if (kind == "letter" || kind == "chapter") {
            val nl = text.indexOf('\n')
            title = strip(if (nl < 0) text else text.substring(0, nl))
            body = if (nl < 0) "" else strip(text.substring(nl + 1))
            if (title.isEmpty() || pyLen(title) > 100) probs.add("title missing or over 100 characters")
        }
        val n = words(body).size
        if (n !in lo..hi) probs.add("length $n words is outside $lo to $hi")
        probs += plainTextProblems(if (kind != "chapter") body else CHAPTER_BULLET.replace(body, ""))
        val q = TextCheck.verifyQuotesInText(text, hisMessages)
        if (!q.ok) probs.add("quotes not his: " + q.bad.joinToString("; "))
        val nc = TextCheck.numberCheck(text, inputs)
        if (!nc.ok) probs.add("numbers not in the inputs: " + nc.bad.joinToString(", "))
        return TextResult(probs.isEmpty(), probs, title, body)
    }

    fun monthly(raw: String, knownIds: List<String>, inputs: List<String>, hisMessages: List<String>): MonthlyResult {
        val (parsed, error) = JobFields.parse(raw)
        if (parsed == null) return MonthlyResult(false, listOf(error ?: "not strict JSON"))
        val obj = parsed as? Jv.Obj ?: return MonthlyResult(false, listOf("not an object"))
        val probs = mutableListOf<String>()
        val head = obj.map["headline"]?.asStr
        val narr = obj.map["narrative"]?.asStr
        // A missing revisit means none; an explicit null is not a list.
        var rev: List<Jv>? = if (obj.map.containsKey("revisit")) obj.map["revisit"]?.asList else emptyList()
        if (head == null || strip(head).isEmpty() || words(head).size > 16) probs.add("headline missing or too long")
        if (narr == null || words(narr).size !in 100..350) probs.add("narrative missing or outside 100 to 350 words")
        if (rev == null || rev.size > 3) {
            probs.add("revisit must be a list of at most 3")
            rev = emptyList()
        }
        val out = mutableListOf<Revisit>()
        for (r in rev) {
            val ro = r as? Jv.Obj
            // Python reads a non-object item's ref as the string "bad".
            val ref: Jv = if (ro == null) Jv.Str("bad") else ro.map["ref"] ?: Jv.Null
            val qn = ro?.map?.get("question")?.asStr
            if (ref != Jv.Null && ref.asStr.let { it == null || it !in knownIds }) {
                probs.add("revisit: unknown ref")
            } else if (qn == null || strip(qn).isEmpty() || pyLen(qn) > 200 || "[[" in qn) {
                probs.add("revisit: bad question")
            } else {
                out.add(Revisit(ref.asStr, strip(qn)))
            }
        }
        if (probs.isNotEmpty() || head == null || narr == null) return MonthlyResult(false, probs)
        val allText = (listOf(head, narr) + out.map { it.question }).joinToString("\n")
        probs += plainTextProblems(narr)
        val q = TextCheck.verifyQuotesInText(allText, hisMessages)
        if (!q.ok) probs.add("quotes not his: " + q.bad.joinToString("; "))
        val nc = TextCheck.numberCheck(allText, inputs + knownIds)
        if (!nc.ok) probs.add("numbers not in the inputs: " + nc.bad.joinToString(", "))
        return MonthlyResult(probs.isEmpty(), probs, strip(head), strip(narr), out)
    }

    /** One thing he overcame, as the story job wrote it and the check let through. */
    data class Overcame(val title: String, val thenQuote: Long?, val did: String, val now: String)

    data class StoryResult(
        val ok: Boolean,
        val problems: List<String>,
        val short: String = "",
        val thenQuote: Long? = null,
        val nowQuote: Long? = null,
        val overcame: List<Overcame> = emptyList(),
        val dropped: List<String> = emptyList(),
    )

    const val STORY_MIN_WORDS = 40
    const val STORY_MAX_WORDS = 120
    const val STORY_OVERCAME = 3
    const val STORY_TITLE_CHARS = 60
    const val STORY_LINE_CHARS = 200

    private class Bad(message: String) : Exception(message)

    /** A quote reference: null, or the id of a quote the model was given (never a bool, never a guess). */
    private fun quoteId(v: Jv?, days: Map<Long, String>): Long? {
        if (v == null || v == Jv.Null) return null
        val n = v as? Jv.Num
        if (n == null || !n.isInteger || n.value.toLong() !in days) throw Bad("unknown quote id")
        return n.value.toLong()
    }

    private fun storyLine(v: Jv?, maxLen: Int): String {
        val s = v?.asStr
        if (s == null || strip(s).isEmpty() || pyLen(s) > maxLen || '\n' in strip(s) || "[[" in s) {
            throw Bad("missing, too long or not one line")
        }
        return strip(s)
    }

    /**
     * The monthly story job (spec 2.5 and 8). [quotes] = quote id to its day, the quotes the model was given;
     * [inputs] = every string it was given. The short version must pass or nothing is published; the quote
     * pair and each overcame item are checked on their own, and a bad one is dropped and named in dropped.
     */
    fun story(raw: String, quotes: Map<Long, String>, inputs: List<String>, hisMessages: List<String>): StoryResult {
        val (parsed, error) = JobFields.parse(raw)
        if (parsed == null) return StoryResult(false, listOf(error ?: "not strict JSON"))
        val obj = parsed as? Jv.Obj ?: return StoryResult(false, listOf("not an object"))
        val probs = mutableListOf<String>()
        val rawShort = obj.map["short"]?.asStr
        var short = ""
        if (rawShort == null || words(rawShort).size !in STORY_MIN_WORDS..STORY_MAX_WORDS) {
            probs.add("short missing or outside $STORY_MIN_WORDS to $STORY_MAX_WORDS words")
        } else {
            short = strip(rawShort)
            probs += plainTextProblems(short)
            val q = TextCheck.verifyQuotesInText(short, hisMessages)
            if (!q.ok) probs.add("quotes not his: " + q.bad.joinToString("; "))
            val nc = TextCheck.numberCheck(short, inputs)
            if (!nc.ok) probs.add("numbers not in the inputs: " + nc.bad.joinToString(", "))
        }
        if (probs.isNotEmpty()) return StoryResult(false, probs)
        val dropped = mutableListOf<String>()
        var thenQ: Long? = null
        var nowQ: Long? = null
        try {
            thenQ = quoteId(obj.map["then_quote"], quotes)
            nowQ = quoteId(obj.map["now_quote"], quotes)
            if ((thenQ == null) != (nowQ == null)) throw Bad("then and now come together")
            if (thenQ != null && quotes.getValue(thenQ) >= quotes.getValue(nowQ!!)) {
                throw Bad("the then quote must be from an earlier day than the now quote")
            }
        } catch (e: Bad) {
            dropped.add("quotes: ${e.message}")
            thenQ = null
            nowQ = null
        }
        var items: List<Jv> = if (obj.map.containsKey("overcame")) {
            obj.map["overcame"]?.asList ?: run {
                dropped.add("overcame: not a list")
                emptyList()
            }
        } else {
            emptyList()
        }
        if (items.size > STORY_OVERCAME) {
            dropped.add("overcame: more than $STORY_OVERCAME, the rest dropped")
            items = items.take(STORY_OVERCAME)
        }
        val out = mutableListOf<Overcame>()
        items.forEachIndexed { i, it ->
            try {
                val o = it as? Jv.Obj ?: throw Bad("not an object")
                val item = Overcame(
                    title = storyLine(o.map["title"], STORY_TITLE_CHARS),
                    thenQuote = quoteId(o.map["then_quote"], quotes),
                    did = storyLine(o.map["did"], STORY_LINE_CHARS),
                    now = storyLine(o.map["now"], STORY_LINE_CHARS),
                )
                val text = listOf(item.title, item.did, item.now).joinToString("\n")
                if (plainTextProblems(text).isNotEmpty()) throw Bad("not plain text")
                if (!TextCheck.verifyQuotesInText(text, hisMessages).ok) throw Bad("a quotation that is not his")
                if (!TextCheck.numberCheck(text, inputs).ok) throw Bad("a number not in the inputs")
                out.add(item)
            } catch (e: Bad) {
                dropped.add("overcame ${i + 1}: ${e.message}")
            }
        }
        return StoryResult(true, emptyList(), short, thenQ, nowQ, out, dropped)
    }
}
