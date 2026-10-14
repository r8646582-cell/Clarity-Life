package com.clarity.life.core

/**
 * Keyword retrieval for RELEVANT_PAST (contracts/context.md, Part 3): which words of his new message are
 * searched for, how a match is scored, and when a match is strong enough to send. Pure, tested on the JVM
 * (RetrievalTest). The database side (FTS4 over search_doc) only finds candidates; the choice is made here.
 *
 * Retrieval finds and ranks only. It never writes text and never changes a note's confidence (spec
 * section 7).
 */
object Retrieval {

    const val MAX_TERMS = 8
    const val MAX_RESULTS = 5
    /** Keyword candidates read from the index, newest first, before they are scored. */
    const val CANDIDATES = 200
    const val SNIPPET_CHARS = 280

    /** RELEVANT_PAST's drop ranks start here, above [LastChat]'s, so search results go before the last chat. */
    const val DROP_RANK = 100

    /** How many strong matches each search method brings to the fusion. */
    const val FUSE_DEPTH = 20

    /**
     * Meaning search: the cosine a past message needs to count (bge-small-en-v1.5 puts unrelated sentences
     * near 0.45 and related ones from about 0.58 up; measured 10 Oct 2026, a starting value to tune).
     */
    const val SEMANTIC_MIN = 0.62

    /** Meaning search is skipped for a new message shorter than this ("ok", "hi there") ... */
    const val SEMANTIC_MIN_QUERY_WORDS = 3

    /** ... and ignores past messages shorter than this, because small talk is all alike. */
    const val SEMANTIC_MIN_DOC_WORDS = 4

    fun wordCount(text: String): Int = WORD.findAll(TextCheck.norm(text)).count()

    /** Words too common to say anything about what he means. */
    private val STOP = setOf(
        "about", "after", "again", "also", "always", "because", "been", "before", "being", "could", "didn",
        "does", "doesn", "doing", "done", "dont", "even", "ever", "every", "from", "going", "gonna", "have",
        "having", "here", "into", "just", "know", "like", "make", "many", "maybe", "more", "most", "much",
        "need", "never", "only", "other", "really", "said", "same", "should", "some", "still", "such",
        "sure", "than", "that", "their", "them", "then", "there", "these", "they", "thing", "things",
        "think", "this", "those", "today", "very", "want", "wanna", "well", "were", "what", "when",
        "where", "which", "while", "will", "with", "would", "yeah", "your", "yours", "okay",
    )

    private val WORD = Regex("[a-z0-9]+")

    /** The words worth searching for, longest first (longer words say more), at most [MAX_TERMS]. */
    fun terms(text: String): List<String> =
        WORD.findAll(TextCheck.norm(text)).map { it.value }
            .filter { it.length >= 4 && it !in STOP && !it.all(Char::isDigit) }
            .distinct()
            .sortedWith(compareByDescending<String> { it.length }.thenBy { it })
            .take(MAX_TERMS)
            .toList()

    /** An FTS4 MATCH expression: each term quoted, joined with OR. Null when there is nothing to search. */
    /**
     * Only the words he or the coach wrote are searched (body and title), never the kind and id columns, or
     * "message" would match every message and "12" any row 12. The terms are plain [a-z0-9] words, so no
     * quoting is needed (FTS4 does not accept a quoted term after a column name).
     */
    fun matchQuery(terms: List<String>): String? =
        if (terms.isEmpty()) null else terms.joinToString(" OR ") { "body:$it OR title:$it" }

    /** How many of the terms appear in the text as whole words. */
    fun score(text: String, terms: List<String>): Int {
        val words = WORD.findAll(TextCheck.norm(text)).map { it.value }.toSet()
        return terms.count { it in words }
    }

    /** A match is strong when it shares at least two of the searched words (or the only one there is). */
    fun strongEnough(score: Int, termCount: Int): Boolean = score >= minOf(2, termCount) && score > 0

    data class Candidate(val text: String, val at: Long, val label: String)

    /** How an event reads in search and in RELEVANT_PAST: the parts he described, in order. */
    fun eventText(whenText: String, situation: String, feeling: String, action: String, payoff: String, outcome: String): String =
        listOf(
            whenText,
            situation,
            feeling.takeIf { it.isNotBlank() }?.let { "felt $it" }.orEmpty(),
            action,
            payoff.takeIf { it.isNotBlank() }?.let { "it gave $it" }.orEmpty(),
            outcome,
        ).filter { it.isNotBlank() }.joinToString("; ")

    data class Hit(val candidate: Candidate, val score: Int)

    /** The best [MAX_RESULTS] strong matches: the most shared words first, then the most recent. */
    fun choose(candidates: List<Candidate>, terms: List<String>, max: Int = MAX_RESULTS): List<Hit> =
        candidates.map { Hit(it, score(it.text, terms)) }
            .filter { strongEnough(it.score, terms.size) }
            .distinctBy { TextCheck.norm(it.candidate.text) }
            .sortedWith(compareByDescending<Hit> { it.score }.thenByDescending { it.candidate.at })
            .take(max)

    /** RELEVANT_PAST: best first; the weakest is the first to go when the budget is tight. */
    fun block(hits: List<Hit>): Block = Block(
        "RELEVANT_PAST",
        hits.mapIndexed { i, h ->
            val text = h.candidate.text.replace(Regex("\\s+"), " ").trim()
            val clipped = if (text.length > SNIPPET_CHARS) text.take(SNIPPET_CHARS).trimEnd() + "..." else text
            // Ranked above LAST_CHAT's lines (contracts/context.md): a tight budget drops search results first.
            BlockItem("${h.candidate.label}: $clipped", DROP_RANK + i + 1)
        },
    )
}
