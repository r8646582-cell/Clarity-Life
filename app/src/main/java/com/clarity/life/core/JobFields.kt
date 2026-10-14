package com.clarity.life.core

/**
 * What the background appliers share: the operations they return, the facts they are given, and the field
 * rules of tools/appliers.py (`_text`, `_opt_text`, `_list`, `similarity`).
 *
 * The appliers are the only path by which model output reaches the database without a tap, so these rules
 * copy the Python reference exactly, down to how Python counts characters (code points) and words (its own
 * whitespace set), because ApplierTest runs both against the same contracts/applier_vectors.json.
 */
class FieldBad(message: String) : Exception(message)

/** Thrown when the top level of a result is unusable; the whole result is rejected. */
class ResultReject(message: String) : Exception(message)

data class Evidence(val kind: String, val text: String)

/** One change the repository may make, all of them inside one transaction. */
sealed class JobOp {
    data class ConversationSet(val title: String, val significance: Int, val tone: String) : JobOp()
    data class ConversationSummary(val summary: String) : JobOp()

    /** [verified] = the value is in his exact words, so it may stay (or become) confirmed. */
    data class ProfileSet(val key: String, val value: String, val verified: Boolean) : JobOp()
    data class PersonAdd(val name: String, val relation: String, val notes: String) : JobOp()

    /** [name] is the stored spelling of a known person. */
    data class PersonUpdate(val name: String, val relation: String, val notes: String) : JobOp()
    data class EventAdd(
        val whenText: String,
        val situation: String,
        val feelingBefore: String,
        val action: String,
        val payoff: String,
        val outcome: String,
    ) : JobOp()

    data class NoteAdd(val kind: String, val text: String, val confidence: String, val evidence: List<Evidence>) :
        JobOp()

    /** [text] null = keep the text. [seenAgain] adds one to times_seen. */
    data class NoteUpdate(
        val id: Long,
        val text: String?,
        val confidence: String,
        val seenAgain: Boolean,
        val evidence: List<Evidence>,
    ) : JobOp()

    data class NoteRetire(val id: Long) : JobOp()
    data class QuoteAdd(val text: String) : JobOp()
    data class DisagreementAdd(val claim: String, val hisPosition: String) : JobOp()
    data class ContradictionAdd(val quoteA: String, val quoteB: String) : JobOp()
    data class IdeaAdd(val tag: String) : JobOp()
    data class FocusSummary(val summary: String) : JobOp()
    data class NoteMerge(val ids: List<Long>, val text: String, val confidence: String, val timesSeen: Int) :
        JobOp()

    data class NoteRewrite(val id: Long, val text: String) : JobOp()
    data class PersonMerge(val ids: List<Long>, val name: String, val relation: String, val notes: String) :
        JobOp()
}

/** [ok] false = the whole result was rejected and [ops] is empty. [dropped] are content-free-enough reasons. */
data class ApplyResult(val ok: Boolean, val ops: List<JobOp>, val dropped: List<String>)

/** What a job knows about one existing note. */
data class NoteFacts(
    val kind: String,
    val confidence: String,
    val status: String,
    val edited: Boolean,
    val text: String = "",
    val timesSeen: Int = 1,
)

internal object JobFields {

    val NOTE_KINDS = listOf("pattern", "what_helps", "what_doesnt", "strength", "value", "thread")
    val CONF = listOf("guess", "likely", "confirmed")
    const val DELETED_SIMILARITY = 0.6
    const val DUPLICATE_SIMILARITY = 0.8

    /**
     * Cosine at or above which a text is too close in meaning to a note he deleted (Max plan Phase 3). The lines
     * belong to the model: set in Phase 4 for mdbr-leaf-mt (tools/eval/compare_embedders.py).
     */
    const val DELETED_MEANING = 0.73

    /** Cosine at or above which a note repeats an active note of the same kind that says "not" the same way. */
    const val DUPLICATE_MEANING = 0.91

    private val NEGATIONS = setOf("not", "no", "never", "nor", "without", "cannot", "stop", "stopped", "none", "nothing")

    // ---------- Python string semantics ----------

    /** Python's str.isspace(), so strip() and split() agree with the reference on every character. */
    fun isPySpace(c: Char): Boolean = when (c) {
        '\t', '\n', '\u000B', '\u000C', '\r', '\u001C', '\u001D', '\u001E', '\u001F', ' ', '\u0085', ' ',
        ' ', ' ', ' ', ' ', ' ', '　',
        -> true
        else -> c in ' '..' '
    }

    fun strip(s: String): String = s.trim { isPySpace(it) }

    /** Python's str.split() with no argument. */
    fun words(s: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (c in s) {
            if (isPySpace(c)) {
                if (cur.isNotEmpty()) { out.add(cur.toString()); cur.setLength(0) }
            } else {
                cur.append(c)
            }
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    /** Python's len(): code points, not UTF-16 units, so an emoji counts once. */
    fun pyLen(s: String): Int = s.codePointCount(0, s.length)

    /** `[\x00-\x08\x0b-\x1f\x7f]`: tab and newline are not in it; newline is judged separately. */
    private fun isControl(c: Char): Boolean = c in '\u0000'..'\u0008' || c in '\u000B'..'\u001F' || c == '\u007F'

    // ---------- field rules ----------

    fun text(v: Jv?, maxLen: Int, multiline: Boolean = false): String {
        val s = v?.asStr
        if (s == null || strip(s).isEmpty()) throw FieldBad("empty or not text")
        if (pyLen(s) > maxLen) throw FieldBad("too long")
        if ("[[" in s || "]]" in s) throw FieldBad("brackets")
        if (s.any { isControl(it) } || (!multiline && '\n' in s)) throw FieldBad("control character")
        return strip(s)
    }

    /** Missing, null and "" mean empty; anything else must be valid text. */
    fun optText(v: Jv?, maxLen: Int): String {
        if (v == null || v == Jv.Null || v.asStr == "") return ""
        return text(v, maxLen)
    }

    /** `obj.get(key, [])`: missing or null is empty, a non-list rejects the whole result, extras are dropped. */
    fun list(obj: Jv.Obj, key: String, limit: Int, dropped: MutableList<String>): List<Jv> {
        val v = obj.map[key] ?: return emptyList()
        if (v == Jv.Null) return emptyList()
        val l = v.asList ?: throw ResultReject("$key is not a list")
        if (l.size > limit) dropped.add("$key: over the limit of $limit, extras dropped")
        return l.take(limit)
    }

    /** A Python int: a JSON integer literal (not 4.0, not 4e0, not true). */
    fun pyInt(v: Jv?): Long? {
        val n = v as? Jv.Num ?: return null
        if (!n.isInteger || n.value < -9.0e15 || n.value > 9.0e15) return null
        return n.value.toLong()
    }

    /** Python truthiness of a JSON value, for `obj.get("notes", {}) or {}`. */
    fun falsy(v: Jv?): Boolean = when (v) {
        null, Jv.Null -> true
        is Jv.Bool -> !v.value
        is Jv.Num -> v.value == 0.0
        is Jv.Str -> v.value.isEmpty()
        is Jv.Arr -> v.list.isEmpty()
        is Jv.Obj -> v.map.isEmpty()
    }

    fun isStr(v: Jv?, value: String): Boolean = v?.asStr == value

    private val WORD = Regex("[a-z0-9']+")

    private fun wordSet(s: String): Set<String> = WORD.findAll(TextCheck.norm(s)).map { it.value }.toSet()

    /** Jaccard similarity of the word sets. Simple, deterministic, and the same in Python. */
    fun similarity(a: String, b: String): Double {
        val wa = wordSet(a)
        val wb = wordSet(b)
        if (wa.isEmpty() || wb.isEmpty()) return 0.0
        return (wa intersect wb).size.toDouble() / (wa union wb).size
    }

    /**
     * The cosine of two texts' unit vectors from [vectors] (the on-phone model), or null when either has none:
     * meaning search is off, and the texts are compared by words only, as before Phase 3.
     */
    fun meaning(vectors: (String) -> FloatArray?, a: String, b: String): Double? {
        val va = vectors(a) ?: return null
        val vb = vectors(b) ?: return null
        if (va.size != vb.size) return null
        return VectorMath.dot(va, vb)
    }

    /** Whether a text says "not" in any of its usual ways: "doesn't" and "never" mean the opposite claim. */
    fun negated(text: String): Boolean = wordSet(text).any { it in NEGATIONS || it.endsWith("n't") }

    /** A note he deleted is never re-created, nor anything close to it: in words, or in meaning. */
    fun closeToDeleted(text: String, deleted: List<String>, vectors: (String) -> FloatArray?): Boolean =
        deleted.any { d ->
            similarity(text, d) >= DELETED_SIMILARITY || (meaning(vectors, text, d)?.let { it >= DELETED_MEANING } == true)
        }

    /**
     * [active] are (kind, text) pairs. A repeat in words (of any kind), or in meaning (the same kind and a high
     * score); never when one says "not" and the other does not, which is a change, not a repeat. Opposites
     * score as high as rewordings on this model, hence the guards.
     */
    fun duplicateOfActive(
        text: String,
        kind: String,
        active: List<Pair<String, String>>,
        vectors: (String) -> FloatArray?,
    ): Boolean = active.any { (k, t) ->
        negated(text) == negated(t) && (
            similarity(text, t) >= DUPLICATE_SIMILARITY ||
                (k == kind && (meaning(vectors, text, t)?.let { it >= DUPLICATE_MEANING } == true))
            )
    }

    /** No vectors: meaning search off. */
    val NO_VECTORS: (String) -> FloatArray? = { null }

    /** Parse the model's whole output strictly, or say why not. */
    fun parse(raw: String): Pair<Jv?, String?> = try {
        StrictJson.parse(strip(raw)) to null
    } catch (e: JsonError) {
        null to "not strict JSON: ${e.message}"
    }
}
