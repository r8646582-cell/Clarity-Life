package com.clarity.life.core

import com.clarity.life.core.JobFields.pyLen
import com.clarity.life.core.JobFields.strip
import com.clarity.life.core.JobFields.words

/**
 * Checks AI-written text before it is published: the weekly review note, the letter, the chapter, and the
 * monthly review. A 1:1 port of `check_text_job` and `apply_monthly_review` in tools/appliers.py.
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

    /** [kind] is review_weekly, letter or chapter. [inputs] = every string the model was given. */
    fun check(kind: String, rawText: String, inputs: List<String>, hisMessages: List<String>): TextResult {
        val (lo, hi) = when (kind) {
            "review_weekly" -> 30 to 230
            "letter", "chapter" -> 120 to 700
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
}
