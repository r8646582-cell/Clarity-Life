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
    /**
     * Keyword candidates read from the index for each searched word, newest first, before they are scored.
     * One read per word, so a rare word ("CAF") is never pushed out by the newest matches of a common one
     * ("study"). At most [MAX_TERMS] x this many rows, which stays under SQLite's 999 query variables.
     */
    const val CANDIDATES_PER_TERM = 100
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
        // Three-letter words are searched too ("CAF", "nap", "gym", "mom"), except these.
        "the", "and", "you", "for", "but", "not", "are", "was", "has", "had", "his", "her", "she", "him", "our",
        "out", "can", "all", "any", "get", "got", "did", "its", "one", "too", "now", "how", "why", "who", "let",
        "yes", "lol", "way", "off", "lot", "bit", "use", "see", "say", "put", "new", "own", "try", "ive", "don",
        "isn", "won", "ain", "hey", "huh", "omg", "idk", "btw", "may", "yet", "via", "per", "etc", "ill",
        "whom", "youre", "cant", "wont", "didnt", "isnt", "thats",
    )

    private val WORD = Regex("[a-z0-9]+")

    /** The words worth searching for, longest first (longer words say more), at most [MAX_TERMS]. */
    fun terms(text: String): List<String> =
        WORD.findAll(TextCheck.norm(text)).map { it.value }
            .filter { it.length >= 3 && it !in STOP && !it.all(Char::isDigit) }
            .distinct()
            .sortedWith(compareByDescending<String> { it.length }.thenBy { it })
            .take(MAX_TERMS)
            .toList()

    /**
     * A simple stem, so the forms of one word meet: "lecture", "lectures", "lecturing" and "lectured" all give
     * "lectur"; "nap", "naps" and "napping" give "nap"; "study", "studies", "studied" and "studying" give
     * "study". Irregular forms ("woke" and "wake", "slept" and "sleep") stay apart; meaning search covers some.
     */
    fun stem(word: String): String {
        var w = word.lowercase()
        w = when {
            w.length > 4 && (w.endsWith("ies") || w.endsWith("ied")) -> w.dropLast(3) + "y"
            w.length > 5 && w.endsWith("ing") -> w.dropLast(3)
            w.length > 4 && w.endsWith("ed") -> w.dropLast(2)
            w.length > 3 && w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us") && !w.endsWith("is") -> w.dropLast(1)
            else -> w
        }
        if (w.length > 3 && w[w.length - 1] == w[w.length - 2] && w.last() !in "lsz") w = w.dropLast(1)
        if (w.length > 3 && w.endsWith("e")) w = w.dropLast(1)
        return w
    }

    /**
     * The FTS4 MATCH expression for one searched word: a prefix search on its stem, so every form of the word
     * is a candidate ("lectur*"); [score] then keeps only real matches. Only the words he or the coach wrote
     * are searched (body and title), never the kind and id columns, or "message" would match every message
     * and "12" any row 12. The terms are plain [a-z0-9] words, so no quoting is needed (FTS4 does not accept
     * a quoted term after a column name).
     */
    fun termQuery(term: String): String {
        val s = stem(term)
        val prefix = if (s.length > 3 && s.endsWith("y")) s.dropLast(1) else s
        return "body:$prefix* OR title:$prefix*"
    }

    /** How many of the terms appear in the text, in any of their forms ([stem]). */
    fun score(text: String, terms: List<String>): Int {
        val stems = WORD.findAll(TextCheck.norm(text)).map { stem(it.value) }.toSet()
        return terms.count { stem(it) in stems }
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
