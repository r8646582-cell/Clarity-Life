package com.clarity.life.core

/**
 * Retrieval for RELEVANT_PAST (contracts/context.md, Part 3): which words of his new message are searched for,
 * how a match is scored, how the result lists are merged and which results are sent. Pure, a 1:1 port of
 * tools/retrieval.py: RetrievalVectorsTest runs every case of contracts/retrieval_vectors.json, and
 * tools/eval/memory_exam2.py measures retrieval with the Python twin. The database side (FTS4 over search_doc,
 * the vectors) only finds candidates; the choice is made here.
 *
 * Max plan Phase 2 (docs/MAX-PLAN.md): besides the new message, a second query made of his last few messages in
 * this conversation is searched ([conversationQuery]); a period he names ("in August") is read in code
 * ([RecallTime]) and what falls in it is a list of its own ([dated]); the lists are merged by weighted reciprocal
 * rank fusion ([fuse]); near-duplicates go, one result per conversation is kept, and up to [MAX_RESULTS] are sent
 * ([chooseFinal]). A single rare word counts as a keyword match ([rareTerms]).
 *
 * Retrieval finds and ranks only. It never writes text and never changes a note's confidence (spec
 * section 7).
 */
object Retrieval {

    const val MAX_TERMS = 8

    /** Results sent at most (5 before Phase 2); the 4,000-token budget of Part 3 drops the weakest first. */
    const val MAX_RESULTS = 12

    /** Results from one conversation (its messages, summary, quotes and events): a pattern needs its months. */
    const val MAX_PER_CONVERSATION = 1

    /** Results the period list brings. */
    const val DATED_MAX = 10

    /** His earlier messages in this conversation that join the conversation query. */
    const val CONTEXT_MESSAGES = 3

    /** Meaning matches scored exactly before identical texts are counted once. */
    const val MEANING_CANDIDATES = 80

    /** A word found in at most this share of the index is rare enough to match alone. */
    const val RARE_SHARE = 0.05

    /** Share of word stems two results have in common to count as the same thing. */
    const val NEAR_DUPLICATE = 0.8

    /** The period list brings single check-ins only for a period of at most this many days. */
    const val CHECKIN_DAYS = 7

    /** How much each list counts in the fusion: the period read in code, the new message, the conversation. */
    const val W_DATED = 2.0
    const val W_NEW = 1.0
    const val W_CONTEXT = 0.5
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
     * Meaning search: the cosine a past message needs to count. The line belongs to the model: 0.62 for
     * bge-small-en-v1.5 (10 Oct 2026), 0.56 for mdbr-leaf-mt (Max plan Phase 4), the cosine the same share of
     * random pairs of texts reaches (about 18%; tools/eval/compare_embedders.py). Change it with the model.
     */
    const val SEMANTIC_MIN = 0.56

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
        // Question words that say nothing about what he means ("what happened in March?").
        "happen", "happens", "happened", "happening", "tell", "told",
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

    fun stems(text: String): Set<String> = WORD.findAll(TextCheck.norm(text)).map { stem(it.value) }.toSet()

    /** How many of the terms appear in the text, in any of their forms ([stem]). */
    fun score(text: String, terms: List<String>): Int {
        val stems = stems(text)
        return terms.count { stem(it) in stems }
    }

    /**
     * The searched words that are rare in everything kept: [found] is how many rows each word's search read (at
     * most [CANDIDATES_PER_TERM]), [total] the rows in the index. A word in at most [RARE_SHARE] of the rows says
     * a lot ("power", "physio"); one in many says little ("study").
     */
    fun rareTerms(terms: List<String>, found: Map<String, Int>, total: Int): Set<String> {
        if (total <= 0) return emptySet()
        return terms.filter {
            val n = found[it] ?: 0
            n in 1 until CANDIDATES_PER_TERM && n <= RARE_SHARE * total
        }.toSet()
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

    /**
     * The best strong matches: the most shared words first, then the most recent; the same text once. A match of
     * a single [rare] word also counts, after the strong ones.
     */
    fun choose(candidates: List<Candidate>, terms: List<String>, max: Int = FUSE_DEPTH, rare: Set<String> = emptySet()): List<Hit> {
        val rareStems = rare.map(::stem).toSet()
        return candidates.map { Hit(it, score(it.text, terms)) }
            .filter { strongEnough(it.score, terms.size) || (it.score == 1 && stems(it.candidate.text).any { s -> s in rareStems }) }
            .distinctBy { TextCheck.norm(it.candidate.text) }
            .sortedWith(compareByDescending<Hit> { it.score }.thenByDescending { it.candidate.at })
            .take(max)
    }

    /** Keys in order, each text once (the first, best one, is kept): small talk repeats itself word for word. */
    fun <K> distinctTexts(keys: List<K>, texts: Map<K, String>): List<K> {
        val seen = HashSet<String>()
        return keys.filter { k -> seen.add(TextCheck.norm(texts.getValue(k))) }
    }

    /**
     * The second query: his last [CONTEXT_MESSAGES] messages in this conversation before the new one, oldest
     * first, then the new one, after the conversation's title when it has one. Null when there is nothing before
     * the new message: the new message alone is searched then.
     */
    fun conversationQuery(new: String, earlier: List<String>, title: String = ""): String? {
        val before = earlier.filter { it.isNotBlank() }.takeLast(CONTEXT_MESSAGES)
        if (before.isEmpty() && title.isBlank()) return null
        return (listOfNotNull(title.trim().takeIf { it.isNotEmpty() }) + before + new).joinToString(" ")
    }

    /**
     * Weighted reciprocal rank fusion: each list is (weight, keys best first); a key scores the sum of
     * weight / (k + rank) over the lists it is in. Ties keep the order of first appearance.
     */
    fun <K> fuse(lists: List<Pair<Double, List<K>>>): List<K> {
        val score = LinkedHashMap<K, Double>()
        for ((weight, keys) in lists) {
            keys.forEachIndexed { i, k -> score[k] = (score[k] ?: 0.0) + weight / (VectorMath.RRF_K + i + 1) }
        }
        return score.entries.sortedByDescending { it.value }.map { it.key }
    }

    /** Two texts are the same thing when most of their word stems are shared (Jaccard of the stem sets). */
    fun nearDuplicate(a: String, b: String): Boolean {
        val sa = stems(a)
        val sb = stems(b)
        if (sa.isEmpty() || sb.isEmpty()) return TextCheck.norm(a) == TextCheck.norm(b)
        return (sa intersect sb).size.toDouble() / (sa union sb).size >= NEAR_DUPLICATE
    }

    /** A result's text and its conversation (null for a letter, a review, a promise...). */
    data class Doc(val text: String, val conversation: Long?)

    /**
     * What RELEVANT_PAST sends, from the fused keys (best first): keys without a doc are skipped, a near-duplicate
     * of a result already chosen is left out, and at most [MAX_PER_CONVERSATION] come from one conversation.
     */
    fun <K> chooseFinal(keys: List<K>, docs: Map<K, Doc>, max: Int = MAX_RESULTS): List<K> {
        val out = ArrayList<K>()
        val per = HashMap<Long, Int>()
        for (k in keys) {
            val d = docs[k] ?: continue
            val c = d.conversation
            if (c != null && (per[c] ?: 0) >= MAX_PER_CONVERSATION) continue
            if (out.any { nearDuplicate(d.text, docs.getValue(it).text) }) continue
            out += k
            if (c != null) per[c] = (per[c] ?: 0) + 1
            if (out.size >= max) break
        }
        return out
    }

    // ---------- reflection's search of the whole history (Max plan Phase 3 step 1) ----------

    /** His newest new messages, each searched on its own. */
    const val REFLECTION_MESSAGES = 6

    /** A message shorter than this says too little to search by itself. */
    const val REFLECTION_MIN_WORDS = 4

    /** Earlier summaries and events reflection is shown. */
    const val REFLECTION_RESULTS = 8
    val REFLECTION_KINDS = setOf("summary", "event")

    /** [all]: searched by keyword only; [each]: newest first, each by keyword and by meaning. */
    data class ReflectionQueries(val all: String, val each: List<String>)

    /**
     * What the reflection job searches the history with: every new message of his joined, by keyword (a long
     * text's meaning vector is a blur of everything in it), and his newest [REFLECTION_MESSAGES] messages of at
     * least [REFLECTION_MIN_WORDS] words one by one. Blank messages are left out.
     */
    fun reflectionQueries(hisNew: List<String>): ReflectionQueries {
        val texts = hisNew.filter { JobFields.strip(it).isNotEmpty() }
        val each = texts.asReversed().filter { wordCount(it) >= REFLECTION_MIN_WORDS }.take(REFLECTION_MESSAGES)
        return ReflectionQueries(texts.joinToString(" "), each)
    }

    /** A found record with its kind ("summary", "event"...), for [reflectionChoose]. */
    data class KindDoc(val kind: String, val text: String, val conversation: Long?)

    /**
     * What reflection is shown, from the fused keys (best first): only conversation summaries and events, none
     * from the conversation being reflected, none it is shown already (its RECENT EVENTS); then as
     * [chooseFinal]: one per conversation, near-duplicates out, at most [max].
     */
    fun <K> reflectionChoose(
        keys: List<K>,
        docs: Map<K, KindDoc>,
        conversation: Long,
        shown: Set<K> = emptySet(),
        max: Int = REFLECTION_RESULTS,
    ): List<K> {
        val allowed = docs.filter { (k, d) -> d.kind in REFLECTION_KINDS && d.conversation != conversation && k !in shown }
            .mapValues { (_, d) -> Doc(d.text, d.conversation) }
        return chooseFinal(keys.filter { it in allowed }, allowed, max)
    }

    /** Something that falls in the period he named; [key] is "kind:id", [day] its day ("YYYY-MM-DD"). */
    data class DatedItem(val key: String, val kind: String, val text: String, val day: String, val outcome: String? = null)

    private val KIND_ORDER = listOf("promise", "review", "letter", "summary", "checkin", "revision", "chapter")

    /**
     * The period list: ordered by how many of his other searched words an item shares, then by kind (promises
     * first when he asked about promises, the outcome he named first among them), then the most recent day, then
     * the key. When he named other words, only the items sharing one come, unless none does.
     */
    fun dated(items: List<DatedItem>, restTerms: List<String>, promises: Boolean, outcome: String?, max: Int = DATED_MAX): List<String> {
        fun rank(it: DatedItem): Int = if (promises && it.kind == "promise") {
            if (outcome != null && it.outcome == outcome) -2 else -1
        } else {
            KIND_ORDER.indexOf(it.kind).let { i -> if (i < 0) KIND_ORDER.size else i }
        }
        val pool = if (promises) items.filter { it.kind == "promise" }.ifEmpty { items } else items
        var scored = pool.map { it to if (restTerms.isNotEmpty()) score(it.text, restTerms) else 0 }
        if (restTerms.isNotEmpty() && scored.any { it.second > 0 }) scored = scored.filter { it.second > 0 }
        return scored.sortedWith(
            compareByDescending<Pair<DatedItem, Int>> { it.second }
                .thenBy { rank(it.first) }
                .thenByDescending { it.first.day }
                .thenBy { it.first.key },
        ).map { it.first.key }.take(max)
    }

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
