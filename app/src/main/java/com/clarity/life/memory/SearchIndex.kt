package com.clarity.life.memory

import androidx.room.InvalidationTracker
import androidx.room.withTransaction
import com.clarity.life.core.Block
import com.clarity.life.core.Planner
import com.clarity.life.core.RecallTime
import com.clarity.life.core.Retrieval
import com.clarity.life.core.ReviewFacts
import com.clarity.life.core.SearchText
import com.clarity.life.core.TimeFacts
import com.clarity.life.data.ClarityDatabase
import com.clarity.life.data.dao.CheckinHit
import com.clarity.life.data.dao.MessageHit
import com.clarity.life.data.dao.RevisionHit
import com.clarity.life.data.dao.SummaryHit
import com.clarity.life.data.entity.SearchDocRow
import com.clarity.life.data.entity.SettingRow
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps search_doc in step with the raw rows, and answers RELEVANT_PAST (contracts/context.md, Part 3). The choice
 * of what is sent is pure and lives in [Retrieval] (a port of tools/retrieval.py, measured by
 * tools/eval/memory_exam2.py); this class finds the candidates in the database.
 *
 * What is indexed: his messages ("message"), conversation summaries ("summary"), his verified quotes ("quote"),
 * the events reflection recorded ("event"), retired notes ("note"; active notes are in NOTES already, and a note
 * he deleted is never shown again), his promises with their outcome ("promise"), and since Max plan Phase 2 every
 * other record he keeps: weekly letters, reviews, chapters, goals (finished ones too), goal changes, targets
 * (ended ones too) and the check-ins that carry a reason or a note ([RECORD_KINDS], written by [SearchText]).
 * The coach's replies are not indexed: only his own words are evidence about him (rule 4).
 *
 * Records are kept in step without a hook in every place that writes them: a change to their tables marks the
 * index stale, and the next search first brings those rows up to date ([syncRecords]).
 *
 * One request searches (Phase 2): the period he names, read in code ([RecallTime]), as a list of its own; the
 * new message by keyword and by meaning; and, when the conversation has earlier messages, the same again for his
 * last few messages with the new one ([Retrieval.conversationQuery]). The lists are fused with weights and
 * [Retrieval.chooseFinal] picks what is sent.
 */
@Singleton
class SearchIndex @Inject constructor(
    private val db: ClarityDatabase,
    private val embedder: Embedder,
    private val vectors: VectorCache = VectorCache(db),
) {

    private val dao get() = db.search()

    /** A record table changed since the last [syncRecords]; true at start, so the first search catches up. */
    private val recordsStale = AtomicBoolean(true)
    private val recordLock = Mutex()

    /** The rows in the index, counted now and then for [Retrieval.rareTerms] (counting FTS4 rows reads them all). */
    @Volatile private var total = -1
    @Volatile private var totalAt = 0L

    init {
        db.invalidationTracker.addObserver(
            object : InvalidationTracker.Observer(RECORD_TABLES) {
                override fun onInvalidated(tables: Set<String>) = recordsStale.set(true)
            },
        )
    }

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
        for (r in records()) {
            dao.insert(SearchDocRow(kind = r.kind, refId = r.id.toString(), title = "", body = r.text))
            n++
        }
        recordsStale.set(false)
        total = -1
        n
    }

    /**
     * The nightly check: when any message of his or any summary is missing from the index (a write that failed,
     * a crash between two steps), the index is rebuilt from the raw rows; the records are brought up to date.
     * Returns how many were missing.
     */
    suspend fun repairIfIncomplete(): Int {
        val missing = dao.unindexedMessages() + dao.unindexedSummaries()
        if (missing > 0) rebuild() else syncRecords(force = true)
        return missing
    }

    /** On start: an empty index with messages behind it (the first start after an update) is built once. */
    suspend fun rebuildIfEmpty() {
        if (dao.count() == 0 && dao.allHisMessages().isNotEmpty()) rebuild()
    }

    /**
     * On start: an index written by an older version of the app (a promise read differently, the records not in
     * it) is rebuilt once, and the vectors of texts that changed are made again; otherwise as [rebuildIfEmpty].
     */
    suspend fun rebuildIfStale() {
        val settings = db.settings()
        if (settings.get(VERSION_KEY) != INDEX_VERSION.toString()) {
            rebuild()
            for (kind in CHANGED_IN_VERSION) db.embeddings().deleteKind(kind)
            settings.put(SettingRow(VERSION_KEY, INDEX_VERSION.toString()))
        } else {
            rebuildIfEmpty()
        }
    }

    // ---------- records: letters, reviews, chapters, goals, goal changes, targets, check-ins ----------

    data class Record(val kind: String, val id: Long, val text: String)

    /** Every record as search reads it, from the raw rows. */
    suspend fun records(): List<Record> {
        val out = ArrayList<Record>()
        for (l in dao.allLetters()) out += Record(LETTER, l.id, SearchText.letter(l.title, l.body))
        for (r in dao.allReviews()) out += Record(REVIEW, r.id, reviewText(r))
        for (c in dao.allChapters()) out += Record(CHAPTER, c.id, SearchText.chapter(c.title, c.periodStart, c.periodEnd, c.body))
        for (g in dao.allGoals()) out += Record(GOAL, g.id, SearchText.goal(g.title, g.status, g.deadline))
        val tz = timeZone()
        for (r in dao.allRevisions()) out += Record(REVISION, r.id, revisionText(r, tz))
        for (t in dao.allTargets()) out += Record(TARGET, t.id, targetText(t))
        for (c in dao.checkinsWithWords()) out += Record(CHECKIN, c.id, checkinText(c))
        return out.filter { it.text.isNotBlank() }
    }

    /**
     * Brings the records' index rows up to date with their tables: new ones added, changed ones written again
     * (their vector goes and is made again), gone ones removed (a deleted letter leaves search at once). Cheap
     * when nothing changed: it runs only after a record table was written, unless [force]d. Returns the rows
     * changed.
     */
    suspend fun syncRecords(force: Boolean = false): Int = recordLock.withLock {
        if (!recordsStale.getAndSet(false) && !force) return@withLock 0
        db.withTransaction {
            val want = records().associateBy { it.kind to it.id.toString() }
            val have = HashMap<Pair<String, String>, String>()
            for (kind in RECORD_KINDS) {
                for (b in dao.bodies("kind:$kind")) if (b.kind == kind) have[b.kind to b.refId] = b.body
            }
            var changed = 0
            for ((key, body) in have) {
                val w = want[key]
                if (w == null || w.text != body) {
                    dao.delete(key.first, key.second)
                    key.second.toLongOrNull()?.let { db.embeddings().delete(key.first, it) }
                    changed++
                }
            }
            for ((key, r) in want) {
                if (have[key] != r.text) {
                    dao.insert(SearchDocRow(kind = r.kind, refId = r.id.toString(), title = "", body = r.text))
                    changed++
                }
            }
            if (changed > 0) total = -1
            changed
        }
    }

    /** The records that have no meaning vector for [model] yet, for the indexer. */
    suspend fun recordsWithoutVectors(model: String, limit: Int): List<Record> {
        syncRecords()
        val present = db.embeddings().present(model, RECORD_KINDS).map { it.kind to it.docId }.toHashSet()
        val out = ArrayList<Record>()
        for (kind in RECORD_KINDS) {
            for (b in dao.bodies("kind:$kind")) {
                if (b.kind != kind) continue
                val id = b.refId.toLongOrNull() ?: continue
                if ((kind to id) !in present && b.body.isNotBlank()) out += Record(kind, id, b.body)
                if (out.size >= limit) return out
            }
        }
        return out
    }

    private suspend fun timeZone(): String = db.settings().get(com.clarity.life.settings.SettingsRepository.Keys.TIME_ZONE)?.takeIf { it.isNotBlank() }
        ?: java.util.TimeZone.getDefault().id

    private fun reviewText(r: com.clarity.life.data.entity.ReviewRow): String = SearchText.review(
        r.kind,
        r.periodStart,
        r.periodEnd,
        ReviewFacts.fromJson(r.factsJson)?.text.orEmpty(),
        r.narrative,
    )

    private fun revisionText(r: RevisionHit, tz: String): String {
        val day = Instant.ofEpochMilli(r.createdAt).atZone(ZoneId.of(tz)).toLocalDate().toString()
        return SearchText.revision(r.goalTitle, day, r.text, r.reason)
    }

    private fun targetText(t: com.clarity.life.data.entity.TargetRow): String =
        SearchText.target(t.name, t.target, t.minimum, t.unit, t.schedule, t.startDay, t.endDay, t.status)

    private fun checkinText(c: CheckinHit): String =
        SearchText.checkin(c.targetName, c.day, c.level, c.amount, c.unit, c.reason, c.note)

    // ---------- RELEVANT_PAST ----------

    /**
     * RELEVANT_PAST for his new message: the strongest matches from other conversations and from the records he
     * keeps, each labelled with its day, time, how long ago and conversation title. An empty block (omitted from
     * the request) when nothing is strong. [earlier] are his messages in this conversation before the new one,
     * oldest first, and [title] the conversation's title: they make the conversation query. [boundary] is his
     * day-boundary hour and [now] the request's moment, so "today" here means what it means in NOW.
     */
    suspend fun relevantPast(
        conversationId: Long,
        newText: String,
        tz: String,
        boundary: Int = 0,
        now: Long = System.currentTimeMillis(),
        earlier: List<String> = emptyList(),
        title: String = "",
        leftOutNotes: List<com.clarity.life.data.entity.NoteRow> = emptyList(),
    ): Block {
        syncRecords()
        val resolver = Resolver(conversationId, tz, boundary, now)
        val today = Planner.dayKey(Instant.ofEpochMilli(now).toString(), tz, boundary)
        val period = RecallTime.parse(newText, today)
        val rest = if (period.start != null) period.rest else newText

        val lists = ArrayList<Pair<Double, List<Key>>>()
        if (period.start != null || period.promises) {
            val items = datedItems(period, tz, boundary, resolver)
            val chosen = Retrieval.dated(items, Retrieval.terms(rest), period.promises, period.outcome)
            lists += Retrieval.W_DATED to chosen.mapNotNull { Key.parse(it) }
        }
        lists += Retrieval.W_NEW to keywordHits(Retrieval.terms(rest), resolver)
        lists += Retrieval.W_NEW to meaningHits(newText, resolver)
        if (leftOutNotes.isNotEmpty()) lists += Retrieval.W_NEW to leftOutNoteHits(leftOutNotes, Retrieval.terms(rest), resolver)
        Retrieval.conversationQuery(newText, earlier, title)?.let { q ->
            lists += Retrieval.W_CONTEXT to keywordHits(Retrieval.terms(q), resolver)
            lists += Retrieval.W_CONTEXT to meaningHits(q, resolver)
        }
        val fused = Retrieval.fuse(lists)
        if (fused.isEmpty()) return Retrieval.block(emptyList())
        val docs = resolver.resolve(fused)
        val chosen = Retrieval.chooseFinal(fused, docs.mapValues { (_, d) -> Retrieval.Doc(d.candidate.text, d.conversation) })
        return Retrieval.block(chosen.mapNotNull { k -> docs[k]?.let { Retrieval.Hit(it.candidate, 0) } })
    }

    /** One earlier conversation summary or event shown to reflection: its code-written label and its text. */
    data class Earlier(val label: String, val text: String, val at: Long)

    /**
     * Reflection's view of the whole history (Max plan Phase 3 step 1): the conversation summaries and events
     * most like what he said in [hisNew] (his new messages, oldest first), from every conversation but
     * [conversationId], leaving out the events in [shownEvents] (its RECENT EVENTS). Every new message joined
     * is searched by keyword, and his newest ones one by one by keyword and by meaning
     * ([Retrieval.reflectionQueries]); [Retrieval.reflectionChoose] picks at most [Retrieval.REFLECTION_RESULTS],
     * returned oldest first. tools/eval/reflection_exam.py measures the same search on exam 2.
     */
    suspend fun earlierForReflection(
        conversationId: Long,
        hisNew: List<String>,
        shownEvents: Set<Long>,
        tz: String,
        boundary: Int,
        now: Long = System.currentTimeMillis(),
    ): List<Earlier> {
        val r = Resolver(conversationId, tz, boundary, now)
        val q = Retrieval.reflectionQueries(hisNew)
        val lists = ArrayList<Pair<Double, List<Key>>>()
        lists += Retrieval.W_NEW to keywordHits(Retrieval.terms(q.all), r)
        for (text in q.each) {
            lists += Retrieval.W_CONTEXT to keywordHits(Retrieval.terms(text), r)
            lists += Retrieval.W_CONTEXT to meaningHits(text, r)
        }
        val fused = Retrieval.fuse(lists).filter { it.kind in Retrieval.REFLECTION_KINDS }
        if (fused.isEmpty()) return emptyList()
        val docs = r.resolve(fused)
        val chosen = Retrieval.reflectionChoose(
            fused,
            docs.mapValues { (k, d) -> Retrieval.KindDoc(k.kind, d.candidate.text, d.conversation) },
            conversationId,
            shownEvents.map { Key(EVENT, it) }.toSet(),
        )
        return chosen.mapNotNull { k -> docs[k]?.candidate?.let { Earlier(it.label, it.text, it.at) } }.sortedBy { it.at }
    }

    /** A record found, with the conversation it belongs to (null for a letter, a goal, a promise...). */
    private data class Found(val candidate: Retrieval.Candidate, val conversation: Long?)

    data class Key(val kind: String, val id: Long) {
        override fun toString() = "$kind:$id"

        companion object {
            fun parse(s: String): Key? {
                val i = s.lastIndexOf(':')
                if (i <= 0) return null
                return s.substring(i + 1).toLongOrNull()?.let { Key(s.substring(0, i), it) }
            }
        }
    }

    /**
     * Reads text, time and label of keys once per request, leaving out anything from the open conversation (it is
     * in the history already) and anything that no longer exists.
     */
    private inner class Resolver(val conversationId: Long, val tz: String, val boundary: Int, val now: Long) {
        private val cache = HashMap<Key, Found?>()
        private val today = Planner.dayKey(Instant.ofEpochMilli(now).toString(), tz, boundary)

        fun stamp(at: Long) = TimeFacts.pastStamp(Instant.ofEpochMilli(at), Instant.ofEpochMilli(now), tz, boundary)

        /** "Sun 11 Oct (3 days ago)" for a record that belongs to a day, not a moment. */
        fun dayStamp(day: String): String {
            val d = LocalDate.parse(day)
            return "${TimeFacts.shortDay(d)}${if (d.year != today.year) " ${d.year}" else ""} (${TimeFacts.ago(d, today)})"
        }

        fun dayOf(at: Long): String = Planner.dayKey(Instant.ofEpochMilli(at).toString(), tz, boundary).toString()

        fun put(key: Key, found: Found) {
            cache[key] = found
        }

        suspend fun resolve(keys: List<Key>): Map<Key, Found> {
            val todo = keys.filter { it !in cache }
            fun ids(kind: String) = todo.filter { it.kind == kind }.map { it.id }.distinct()
            for (k in todo) cache[k] = null
            ids(MESSAGE).chunked(CHUNK).forEach { chunk ->
                for (m in dao.messageHits(chunk)) {
                    if (m.conversationId != conversationId) cache[Key(MESSAGE, m.messageId)] = said(m)
                }
            }
            ids(SUMMARY).chunked(CHUNK).forEach { chunk ->
                for (s in dao.summaryHits(chunk)) {
                    if (s.conversationId != conversationId) cache[Key(SUMMARY, s.conversationId)] = summed(s)
                }
            }
            ids(QUOTE).chunked(CHUNK).forEach { chunk ->
                for (q in dao.quoteHits(chunk)) {
                    if (q.conversationId == conversationId) continue
                    cache[Key(QUOTE, q.id)] = Found(
                        Retrieval.Candidate(q.text, q.at, "${stamp(q.at)}${titled(q.title)}, words of theirs worth keeping"),
                        q.conversationId,
                    )
                }
            }
            ids(EVENT).chunked(CHUNK).forEach { chunk ->
                val events = dao.events(chunk)
                val titles = dao.summaryHits(events.mapNotNull { it.conversationId }.distinct()).associateBy { it.conversationId }
                for (e in events) {
                    if (e.conversationId == conversationId) continue
                    val title = e.conversationId?.let { titles[it]?.title }.orEmpty()
                    cache[Key(EVENT, e.id)] = Found(
                        Retrieval.Candidate(eventText(e), e.createdAt, "${stamp(e.createdAt)}${titled(title)}, something that happened"),
                        e.conversationId,
                    )
                }
            }
            ids(NOTE).chunked(CHUNK).forEach { chunk ->
                for (n in dao.retiredNotes(chunk)) {
                    cache[Key(NOTE, n.id)] = Found(
                        Retrieval.Candidate(n.text, n.createdAt, "An older note, no longer active (${n.kind}, last seen ${n.lastSeen})"),
                        null,
                    )
                }
            }
            ids(PROMISE).chunked(CHUNK).forEach { chunk ->
                for (p in dao.promises(chunk)) cache[Key(PROMISE, p.id)] = promised(p)
            }
            ids(LETTER).chunked(CHUNK).forEach { chunk ->
                for (l in dao.letters(chunk)) {
                    cache[Key(LETTER, l.id)] = Found(
                        Retrieval.Candidate(SearchText.letter(l.title, l.body), l.readyAt, "${dayStamp(l.periodEnd)}, their weekly letter"),
                        null,
                    )
                }
            }
            ids(REVIEW).chunked(CHUNK).forEach { chunk ->
                for (r in dao.reviews(chunk)) {
                    cache[Key(REVIEW, r.id)] = Found(
                        Retrieval.Candidate(reviewText(r), r.createdAt, "${dayStamp(r.periodEnd)}, a ${if (r.kind == "month") "month" else "week"} review"),
                        null,
                    )
                }
            }
            ids(CHAPTER).chunked(CHUNK).forEach { chunk ->
                for (c in dao.chapters(chunk)) {
                    cache[Key(CHAPTER, c.id)] = Found(
                        Retrieval.Candidate(
                            SearchText.chapter(c.title, c.periodStart, c.periodEnd, c.body),
                            c.createdAt,
                            "${dayStamp(c.periodEnd)}, a chapter of their story",
                        ),
                        null,
                    )
                }
            }
            ids(GOAL).chunked(CHUNK).forEach { chunk ->
                for (g in dao.goals(chunk)) {
                    cache[Key(GOAL, g.id)] = Found(
                        Retrieval.Candidate(SearchText.goal(g.title, g.status, g.deadline), g.createdAt, "${dayStamp(dayOf(g.createdAt))}, a goal they set"),
                        null,
                    )
                }
            }
            ids(REVISION).chunked(CHUNK).forEach { chunk ->
                for (r in dao.revisions(chunk)) {
                    cache[Key(REVISION, r.id)] = Found(
                        Retrieval.Candidate(revisionText(r, tz), r.createdAt, "${dayStamp(dayOf(r.createdAt))}, a change to a goal"),
                        null,
                    )
                }
            }
            ids(TARGET).chunked(CHUNK).forEach { chunk ->
                for (t in dao.targets(chunk)) {
                    cache[Key(TARGET, t.id)] = Found(
                        Retrieval.Candidate(targetText(t), dayMillis(t.startDay), "${dayStamp(t.startDay)}, a target they set"),
                        null,
                    )
                }
            }
            ids(CHECKIN).chunked(CHUNK).forEach { chunk ->
                for (c in dao.checkins(chunk)) cache[Key(CHECKIN, c.id)] = checkedIn(c)
            }
            return keys.mapNotNull { k -> cache[k]?.let { k to it } }.toMap()
        }

        fun said(m: MessageHit) = Found(Retrieval.Candidate(m.text, m.at, "${stamp(m.at)}${titled(m.title)}, they wrote"), m.conversationId)

        fun summed(s: SummaryHit) = Found(Retrieval.Candidate(s.text, s.at, "${stamp(s.at)}${titled(s.title)}, summary"), s.conversationId)

        fun promised(p: com.clarity.life.data.entity.PromiseRow) =
            Found(Retrieval.Candidate(promiseText(p), p.dueAt, "${stamp(p.dueAt)}, a promise they set"), null)

        fun checkedIn(c: CheckinHit) =
            Found(Retrieval.Candidate(checkinText(c), dayMillis(c.day), "${dayStamp(c.day)}, a check-in"), null)

        private fun dayMillis(day: String): Long =
            LocalDate.parse(day).atTime(12, 0).atZone(ZoneId.of(tz)).toInstant().toEpochMilli()
    }

    /** What falls in the period he named (or every promise, for a question about promises with no period). */
    private suspend fun datedItems(period: RecallTime.Period, tz: String, boundary: Int, r: Resolver): List<Retrieval.DatedItem> {
        val out = ArrayList<Retrieval.DatedItem>()
        fun promiseItem(p: com.clarity.life.data.entity.PromiseRow) {
            val key = Key(PROMISE, p.id)
            r.put(key, r.promised(p))
            out += Retrieval.DatedItem(key.toString(), PROMISE, promiseText(p), r.dayOf(p.dueAt), p.status)
        }
        val start = period.start
        val end = period.end
        if (start == null || end == null) {
            if (period.promises) dao.latestPromises(ALL_PROMISES).forEach(::promiseItem)
            return out
        }
        // The period's days, as moments: from the start of its first day to the start of the day after its last,
        // with his day boundary, so "yesterday" here is the day NOW calls yesterday.
        val zone = ZoneId.of(tz)
        val from = start.atTime(LocalTime.of(boundary, 0)).atZone(zone).toInstant().toEpochMilli()
        val to = end.plusDays(1).atTime(LocalTime.of(boundary, 0)).atZone(zone).toInstant().toEpochMilli()
        dao.promisesDueBetween(from, to).forEach(::promiseItem)
        // A question about promises keeps only them ([Retrieval.dated]), unless none falls in the period.
        if (period.promises && out.isNotEmpty()) return out
        for (s in dao.conversationsBetween(from, to, CONVERSATIONS_IN_PERIOD)) {
            if (s.conversationId == r.conversationId) continue
            val key = Key(SUMMARY, s.conversationId)
            r.put(key, r.summed(s))
            out += Retrieval.DatedItem(key.toString(), SUMMARY, s.text, r.dayOf(s.at))
        }
        val a = start.toString()
        val b = end.toString()
        for (l in dao.lettersOverlapping(a, b)) out += Retrieval.DatedItem("$LETTER:${l.id}", LETTER, SearchText.letter(l.title, l.body), l.periodEnd)
        for (v in dao.reviewsOverlapping(a, b)) out += Retrieval.DatedItem("$REVIEW:${v.id}", REVIEW, reviewText(v), v.periodEnd)
        for (c in dao.chaptersOverlapping(a, b)) {
            out += Retrieval.DatedItem("$CHAPTER:${c.id}", CHAPTER, SearchText.chapter(c.title, c.periodStart, c.periodEnd, c.body), c.periodEnd)
        }
        for (v in dao.revisionsBetween(from, to)) out += Retrieval.DatedItem("$REVISION:${v.id}", REVISION, revisionText(v, tz), r.dayOf(v.createdAt))
        if (java.time.temporal.ChronoUnit.DAYS.between(start, end) < Retrieval.CHECKIN_DAYS) {
            for (c in dao.checkinsBetween(a, b)) {
                val key = Key(CHECKIN, c.id)
                r.put(key, r.checkedIn(c))
                out += Retrieval.DatedItem(key.toString(), CHECKIN, checkinText(c), c.day)
            }
        }
        return out
    }

    private suspend fun keywordHits(terms: List<String>, r: Resolver): List<Key> {
        if (terms.isEmpty()) return emptyList()
        // One read per word, so the newest matches of a common word never crowd out a rare one.
        val rows = LinkedHashMap<Pair<String, String>, SearchDocRow>()
        val found = HashMap<String, Int>()
        for (term in terms) {
            val hits = try {
                dao.match(Retrieval.termQuery(term), r.conversationId, Retrieval.CANDIDATES_PER_TERM)
            } catch (e: Exception) {
                // A query FTS cannot read is a reason to send nothing for that word, never to fail the reply.
                emptyList()
            }
            found[term] = hits.size
            for (h in hits) rows.putIfAbsent(h.kind to h.refId, h)
        }
        val keys = rows.values.mapNotNull { h -> h.refId.toLongOrNull()?.let { Key(h.kind, it) } }
        val docs = r.resolve(keys)
        // The key goes with its candidate through the scoring, so two equal texts can never be confused.
        val tagged = keys.mapNotNull { k -> docs[k]?.let { d -> d.candidate.copy(label = k.toString()) } }
        val rare = Retrieval.rareTerms(terms, found, indexSize())
        return Retrieval.choose(tagged, terms, Retrieval.FUSE_DEPTH, rare).mapNotNull { Key.parse(it.candidate.label) }
    }

    /**
     * Active notes the frozen NOTES block had no room for (Max plan Phase 2 step 5), by keyword: they come back
     * as soon as a message is about them. Never a retired or deleted note: the caller passes active ones only.
     */
    private fun leftOutNoteHits(notes: List<com.clarity.life.data.entity.NoteRow>, terms: List<String>, r: Resolver): List<Key> {
        if (terms.isEmpty()) return emptyList()
        val byKey = notes.associateBy { Key(ACTIVE_NOTE, it.id).toString() }
        val cands = notes.map { Retrieval.Candidate(it.text, it.createdAt, Key(ACTIVE_NOTE, it.id).toString()) }
        return Retrieval.choose(cands, terms).mapNotNull { h ->
            val n = byKey[h.candidate.label] ?: return@mapNotNull null
            val key = Key(ACTIVE_NOTE, n.id)
            r.put(key, Found(Retrieval.Candidate(n.text, n.createdAt, "A note that is not in the list above (${n.kind}, ${n.confidence}, seen ${n.timesSeen} times)"), null))
            key
        }
    }

    private suspend fun indexSize(): Int {
        val now = System.currentTimeMillis()
        if (total < 0 || now - totalAt > TOTAL_TTL_MS) {
            total = dao.count()
            totalAt = now
        }
        return total
    }

    /**
     * Meaning matches above [Retrieval.SEMANTIC_MIN], best first, each text once. Empty when meaning search is off.
     * Ranked by the in-memory scores ([VectorCache], within [com.clarity.life.core.VectorIndex.MARGIN] of the
     * exact cosine); only the ones kept are read back and scored exactly, so a search reads about
     * [Retrieval.FUSE_DEPTH] vectors from the database, not every candidate (the ten-year timing run, 14 Oct 2026).
     */
    private suspend fun meaningHits(text: String, r: Resolver): List<Key> {
        if (Retrieval.wordCount(text) < Retrieval.SEMANTIC_MIN_QUERY_WORDS) return emptyList()
        val q = embedder.embed(text) ?: return emptyList()
        val candidates = vectors.search(q, Retrieval.SEMANTIC_MIN, Retrieval.MEANING_CANDIDATES) { e ->
            when (e.kind) {
                MESSAGE, SUMMARY -> e.conversationId >= 0 && e.conversationId != r.conversationId
                else -> true
            }
        }
        val keys = candidates.map { Key(it.entry.kind, it.entry.docId) }
        // Anything from the open conversation or gone since is dropped here; small talk is all alike, so a past
        // message needs a few words of substance to count, and twenty copies of one line count once.
        val docs = r.resolve(keys)
        val usable = keys.filter { k ->
            val d = docs[k] ?: return@filter false
            k.kind != MESSAGE || Retrieval.wordCount(d.candidate.text) >= Retrieval.SEMANTIC_MIN_DOC_WORDS
        }
        val entries = candidates.associateBy { Key(it.entry.kind, it.entry.docId) }
        val out = ArrayList<Key>()
        for (k in Retrieval.distinctTexts(usable, usable.associateWith { docs.getValue(it).candidate.text })) {
            val exact = vectors.exact(q, entries.getValue(k).entry) ?: continue
            if (exact >= Retrieval.SEMANTIC_MIN) out += k
            if (out.size >= Retrieval.FUSE_DEPTH) break
        }
        return out
    }

    private fun titled(title: String) = if (title.isNotBlank()) ", in \"$title\"" else ""

    companion object {
        const val MESSAGE = "message"
        const val SUMMARY = "summary"
        const val QUOTE = "quote"
        const val EVENT = "event"
        const val NOTE = "note"
        const val PROMISE = "promise"
        const val LETTER = "letter"
        const val REVIEW = "review"
        const val CHAPTER = "chapter"
        const val GOAL = "goal"
        const val REVISION = "revision"
        const val TARGET = "target"
        const val CHECKIN = "checkin"

        /** An active note, found only among those the frozen NOTES block left out; never written to the index. */
        private const val ACTIVE_NOTE = "active_note"

        /** The kinds kept in step with their tables by [syncRecords]. */
        val RECORD_KINDS = listOf(LETTER, REVIEW, CHAPTER, GOAL, REVISION, TARGET, CHECKIN)
        private val RECORD_TABLES = arrayOf("letter", "review", "chapter", "goal", "goal_revision", "target", "checkin")

        /** Bump when what the index holds or how a kind reads changes; the next start rebuilds it once. */
        const val INDEX_VERSION = 2
        private const val VERSION_KEY = "search_index_version"

        /** Version 2 changed how a promise reads ("Promise: ..."), so their vectors are made again. */
        private val CHANGED_IN_VERSION = listOf(PROMISE)

        /** Ids per query, under SQLite's 999 variables. */
        private const val CHUNK = 500
        private const val CONVERSATIONS_IN_PERIOD = 200
        private const val ALL_PROMISES = 500
        private const val TOTAL_TTL_MS = 10 * 60 * 1000L

        fun promiseText(p: com.clarity.life.data.entity.PromiseRow) = com.clarity.life.core.PromiseText.searchText(
            com.clarity.life.core.PromiseText.Promise(p.id, p.text, Instant.ofEpochMilli(p.dueAt), p.status, p.reason),
        )

        fun eventText(e: com.clarity.life.data.entity.EventRow) =
            Retrieval.eventText(e.whenText, e.situation, e.feelingBefore, e.action, e.payoff, e.outcome)
    }
}
