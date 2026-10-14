package com.clarity.life.memory

import androidx.room.withTransaction
import com.clarity.life.core.Block
import com.clarity.life.core.Retrieval
import com.clarity.life.core.TimeFacts
import com.clarity.life.core.VectorMath
import com.clarity.life.data.ClarityDatabase
import com.clarity.life.data.dao.MessageHit
import com.clarity.life.data.dao.SummaryHit
import com.clarity.life.data.entity.SearchDocRow
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps search_doc in step with the raw rows, and answers RELEVANT_PAST with hybrid retrieval: keyword
 * matches (FTS4) and meaning matches (bge vectors, [Embedder]) merged by reciprocal rank fusion (spec
 * section 7). When meaning search is off, keywords alone answer.
 *
 * What is indexed: his messages ("message"), conversation summaries ("summary"), his verified quotes
 * ("quote"), the events reflection recorded ("event"), retired notes ("note"; active notes are in NOTES
 * already, and a note he deleted is never shown again) and his promises with their outcome ("promise"; the
 * open and recent ones are in OPEN_PROMISES already, this finds the older ones). Reviews, letters and chapters join with their
 * stages. The coach's replies are not indexed: only his own words are evidence about him (rule 4).
 * tools/eval/memory_exam.py measured what the quotes, events and retired notes add: 75% to 80% of the
 * exam's evidence within reach (without promises, which arrive in stage C).
 */
@Singleton
class SearchIndex @Inject constructor(
    private val db: ClarityDatabase,
    private val embedder: Embedder,
    private val vectors: VectorCache = VectorCache(db),
) {

    private val dao get() = db.search()

    suspend fun indexHisMessage(messageId: Long, text: String) {
        dao.delete(MESSAGE, messageId.toString())
        if (text.isNotBlank()) dao.insert(SearchDocRow(kind = MESSAGE, refId = messageId.toString(), title = "", body = text))
    }

    /** A new summary replaces the old one; its vector goes too and is made again by the indexer. */
    suspend fun indexSummary(conversationId: Long, summary: String) {
        dao.delete(SUMMARY, conversationId.toString())
        db.embeddings().delete(SUMMARY, conversationId)
        if (summary.isNotBlank()) {
            dao.insert(SearchDocRow(kind = SUMMARY, refId = conversationId.toString(), title = "", body = summary))
        }
    }

    suspend fun indexQuote(quoteId: Long, text: String) = put(QUOTE, quoteId, text)

    suspend fun indexEvent(e: com.clarity.life.data.entity.EventRow) = put(EVENT, e.id, eventText(e))

    /** A note that was retired becomes searchable; one he deleted leaves the index for good. */
    suspend fun indexRetiredNote(noteId: Long, text: String) = put(NOTE, noteId, text)

    /** A promise, saved or answered: its text with the outcome, so "did I keep..." finds it later. */
    suspend fun indexPromise(p: com.clarity.life.data.entity.PromiseRow) = put(PROMISE, p.id, promiseText(p))

    suspend fun removeNote(noteId: Long) {
        dao.delete(NOTE, noteId.toString())
        db.embeddings().delete(NOTE, noteId)
    }

    private suspend fun put(kind: String, id: Long, text: String) {
        dao.delete(kind, id.toString())
        db.embeddings().delete(kind, id)
        if (text.isNotBlank()) dao.insert(SearchDocRow(kind = kind, refId = id.toString(), title = "", body = text))
    }

    /** Called inside ForgetConversation's transaction, before the conversation row goes. */
    suspend fun forget(conversationId: Long) {
        dao.deleteQuotesAndEventsOf(conversationId)
        db.embeddings().deleteQuotesAndEventsOf(conversationId)
        dao.deleteMessagesOf(conversationId)
        dao.delete(SUMMARY, conversationId.toString())
        db.embeddings().deleteMessagesOf(conversationId)
        db.embeddings().delete(SUMMARY, conversationId)
    }

    /** Settings, Advanced, Rebuild search: the keyword index is thrown away and made again from the raw rows. */
    suspend fun rebuild(): Int = db.withTransaction {
        dao.clear()
        var n = 0
        for (m in dao.allHisMessages()) {
            dao.insert(SearchDocRow(kind = MESSAGE, refId = m.messageId.toString(), title = "", body = m.text))
            n++
        }
        for (s in dao.allSummaries()) {
            dao.insert(SearchDocRow(kind = SUMMARY, refId = s.conversationId.toString(), title = "", body = s.text))
            n++
        }
        for (q in dao.allQuotes()) {
            dao.insert(SearchDocRow(kind = QUOTE, refId = q.id.toString(), title = "", body = q.text))
            n++
        }
        for (e in dao.allEvents()) {
            dao.insert(SearchDocRow(kind = EVENT, refId = e.id.toString(), title = "", body = eventText(e)))
            n++
        }
        for (note in dao.allRetiredNotes()) {
            dao.insert(SearchDocRow(kind = NOTE, refId = note.id.toString(), title = "", body = note.text))
            n++
        }
        for (p in dao.allPromises()) {
            dao.insert(SearchDocRow(kind = PROMISE, refId = p.id.toString(), title = "", body = promiseText(p)))
            n++
        }
        n
    }

    /**
     * The nightly check: when any message of his or any summary is missing from the index (a write that failed,
     * a crash between two steps), the index is rebuilt from the raw rows. Returns how many were missing.
     */
    suspend fun repairIfIncomplete(): Int {
        val missing = dao.unindexedMessages() + dao.unindexedSummaries()
        if (missing > 0) rebuild()
        return missing
    }

    /** On start: an empty index with messages behind it (the first start after an update) is built once. */
    suspend fun rebuildIfEmpty() {
        if (dao.count() == 0 && dao.allHisMessages().isNotEmpty()) rebuild()
    }

    /**
     * RELEVANT_PAST for his new message: the strongest matches from other conversations, each labelled with
     * its day, time, how long ago and conversation title. An empty block (omitted from the request) when
     * nothing is strong. [boundary] is his day-boundary hour and [now] the request's moment, so "today" here
     * means what it means in NOW and in the history.
     */
    suspend fun relevantPast(
        conversationId: Long,
        newText: String,
        tz: String,
        boundary: Int = 0,
        now: Long = System.currentTimeMillis(),
    ): Block {
        val terms = Retrieval.terms(newText)
        val keyword = keywordHits(conversationId, terms)
        val meaning = if (Retrieval.wordCount(newText) >= Retrieval.SEMANTIC_MIN_QUERY_WORDS) {
            meaningHits(conversationId, newText)
        } else {
            emptyList()
        }
        val fused = VectorMath.fuse(keyword, meaning).take(Retrieval.MAX_RESULTS)
        if (fused.isEmpty()) return Retrieval.block(emptyList())

        val docs = resolve(fused, conversationId, tz, boundary, now)
        return Retrieval.block(fused.mapNotNull { key -> docs[key]?.let { Retrieval.Hit(it, 0) } })
    }

    /**
     * Text, time and label of each key, leaving out anything from the open conversation (it is in the
     * history already) and anything that no longer exists.
     */
    private suspend fun resolve(
        keys: List<Key>,
        conversationId: Long,
        tz: String,
        boundary: Int = 0,
        now: Long = System.currentTimeMillis(),
    ): Map<Key, Retrieval.Candidate> {
        fun stamp(at: Long) = TimeFacts.pastStamp(Instant.ofEpochMilli(at), Instant.ofEpochMilli(now), tz, boundary)
        fun said(m: MessageHit) = Retrieval.Candidate(m.text, m.at, "${stamp(m.at)}${titled(m.title)}, they wrote")
        fun summed(s: SummaryHit) = Retrieval.Candidate(s.text, s.at, "${stamp(s.at)}${titled(s.title)}, summary")
        fun ids(kind: String) = keys.filter { it.kind == kind }.map { it.id }
        val out = HashMap<Key, Retrieval.Candidate>()
        if (ids(MESSAGE).isNotEmpty()) {
            for (m in dao.messageHits(ids(MESSAGE))) {
                if (m.conversationId != conversationId) out[Key(MESSAGE, m.messageId)] = said(m)
            }
        }
        if (ids(SUMMARY).isNotEmpty()) {
            for (s in dao.summaryHits(ids(SUMMARY))) {
                if (s.conversationId != conversationId) out[Key(SUMMARY, s.conversationId)] = summed(s)
            }
        }
        if (ids(QUOTE).isNotEmpty()) {
            for (q in dao.quoteHits(ids(QUOTE))) {
                if (q.conversationId == conversationId) continue
                out[Key(QUOTE, q.id)] = Retrieval.Candidate(q.text, q.at, "${stamp(q.at)}${titled(q.title)}, words of theirs worth keeping")
            }
        }
        if (ids(EVENT).isNotEmpty()) {
            val titles = dao.summaryHits(dao.events(ids(EVENT)).mapNotNull { it.conversationId }).associateBy { it.conversationId }
            for (e in dao.events(ids(EVENT))) {
                if (e.conversationId == conversationId) continue
                val title = e.conversationId?.let { titles[it]?.title }.orEmpty()
                out[Key(EVENT, e.id)] = Retrieval.Candidate(eventText(e), e.createdAt, "${stamp(e.createdAt)}${titled(title)}, something that happened")
            }
        }
        if (ids(NOTE).isNotEmpty()) {
            for (n in dao.retiredNotes(ids(NOTE))) {
                out[Key(NOTE, n.id)] = Retrieval.Candidate(n.text, n.createdAt, "An older note, no longer active (${n.kind}, last seen ${n.lastSeen})")
            }
        }
        if (ids(PROMISE).isNotEmpty()) {
            for (p in dao.promises(ids(PROMISE))) {
                out[Key(PROMISE, p.id)] = Retrieval.Candidate(promiseText(p), p.dueAt, "${stamp(p.dueAt)}, a promise they set")
            }
        }
        return out
    }

    private data class Key(val kind: String, val id: Long)

    private suspend fun keywordHits(conversationId: Long, terms: List<String>): List<Key> {
        if (terms.isEmpty()) return emptyList()
        // One read per word, so the newest matches of a common word never crowd out a rare one.
        val rows = LinkedHashMap<Pair<String, String>, SearchDocRow>()
        for (term in terms) {
            val found = try {
                dao.match(Retrieval.termQuery(term), conversationId, Retrieval.CANDIDATES_PER_TERM)
            } catch (e: Exception) {
                // A query FTS cannot read is a reason to send nothing for that word, never to fail the reply.
                emptyList()
            }
            for (r in found) rows.putIfAbsent(r.kind to r.refId, r)
        }
        val keys = rows.values.mapNotNull { r -> r.refId.toLongOrNull()?.let { Key(r.kind, it) } }
        val docs = resolve(keys, conversationId, "UTC")
        // The key goes with its candidate through the scoring, so two equal texts can never be confused.
        val tagged = keys.mapNotNull { k -> docs[k]?.let { c -> k to c.copy(label = k.kind + ":" + k.id) } }
        val byLabel = tagged.associate { (k, c) -> c.label to k }
        return Retrieval.choose(tagged.map { it.second }, terms, Retrieval.FUSE_DEPTH).mapNotNull { byLabel[it.candidate.label] }
    }

    /** Meaning matches above [Retrieval.SEMANTIC_MIN], best first. Empty when meaning search is off. */
    private suspend fun meaningHits(conversationId: Long, newText: String): List<Key> {
        val q = embedder.embed(newText) ?: return emptyList()
        // Candidates from the vectors in memory, then scored exactly from the stored floats.
        val candidates = vectors.search(q, Retrieval.SEMANTIC_MIN, Retrieval.FUSE_DEPTH * 4) { e ->
            when (e.kind) {
                MESSAGE, SUMMARY -> e.conversationId >= 0 && e.conversationId != conversationId
                else -> true
            }
        }
        val scored = mutableListOf<Pair<Key, Double>>()
        for (hit in candidates) {
            val s = vectors.exact(q, hit.entry) ?: continue
            if (s >= Retrieval.SEMANTIC_MIN) scored += Key(hit.entry.kind, hit.entry.docId) to s
        }
        val best = scored.sortedByDescending { it.second }.take(Retrieval.FUSE_DEPTH * 2).map { it.first }
        // Anything from the open conversation or gone since is dropped here; and small talk is all alike, so a
        // past message needs a few words of substance to count.
        val docs = resolve(best, conversationId, "UTC")
        return best.filter { k ->
            val d = docs[k] ?: return@filter false
            k.kind != MESSAGE || Retrieval.wordCount(d.text) >= Retrieval.SEMANTIC_MIN_DOC_WORDS
        }.take(Retrieval.FUSE_DEPTH)
    }

    private fun titled(title: String) = if (title.isNotBlank()) ", in \"$title\"" else ""

    companion object {
        const val MESSAGE = "message"
        const val SUMMARY = "summary"
        const val QUOTE = "quote"
        const val EVENT = "event"
        const val NOTE = "note"
        const val PROMISE = "promise"

        fun promiseText(p: com.clarity.life.data.entity.PromiseRow) = com.clarity.life.core.PromiseText.searchText(
            com.clarity.life.core.PromiseText.Promise(p.id, p.text, Instant.ofEpochMilli(p.dueAt), p.status, p.reason),
        )

        fun eventText(e: com.clarity.life.data.entity.EventRow) =
            Retrieval.eventText(e.whenText, e.situation, e.feelingBefore, e.action, e.payoff, e.outcome)

        /** The newest vectors compared per request; far more than years of his messages need. */
    }
}
