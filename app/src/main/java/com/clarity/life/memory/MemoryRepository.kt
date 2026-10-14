package com.clarity.life.memory

import androidx.room.withTransaction
import com.clarity.life.core.Evidence
import com.clarity.life.core.JobOp
import com.clarity.life.core.TextCheck
import com.clarity.life.data.ClarityDatabase
import com.clarity.life.data.StateMachines
import com.clarity.life.data.Values
import com.clarity.life.data.entity.EventRow
import com.clarity.life.data.entity.JobRow
import com.clarity.life.data.entity.NoteEvidenceRow
import com.clarity.life.data.entity.NoteRow
import com.clarity.life.data.entity.PersonRow
import com.clarity.life.data.entity.ProfileEntryRow
import com.clarity.life.data.entity.QuoteRow
import com.clarity.life.data.entity.ReflectionItemRow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one writer for memory (spec section 18, point 2). Each command is one transaction.
 *
 * [applyReflection] is the only way the model's output reaches these tables without a tap, and only after
 * [com.clarity.life.core.ReflectionApplier] validated it. It still re-checks every note and profile entry
 * against the database as it is now, because he may have edited, confirmed or deleted something while the
 * model was writing: his action always wins over a job that started before it.
 */
@Singleton
class MemoryRepository @Inject constructor(
    private val db: ClarityDatabase,
    private val search: SearchIndex,
) {

    private val dao get() = db.memory()

    /** What one reflection changed, as counts only (they go to the content-free log). */
    data class Applied(val applied: Int, val skipped: Int)

    /** A new message of his, so evidence and quotes are dated by the day he said them. */
    data class Said(val text: String, val day: String)

    /**
     * ApplyReflection. Returns null, changing nothing, when this work was already applied (a replay) or the
     * conversation was forgotten in the meantime.
     */
    suspend fun applyReflection(
        conversationId: Long,
        upTo: Long,
        job: JobRow,
        ops: List<JobOp>,
        said: List<Said>,
        today: String,
        now: Long,
    ): Applied? = db.withTransaction {
        val conversation = db.conversations().get(conversationId) ?: return@withTransaction null
        if (conversation.reflectedUpToMessageId >= upTo) return@withTransaction null

        fun dayOf(quote: String): String =
            said.firstOrNull { TextCheck.quoteIsHis(quote, listOf(it.text), 1) }?.day ?: today

        var applied = 0
        var skipped = 0
        var title = ""
        var summary = conversation.summary
        for (op in ops) {
            val done: Boolean = when (op) {
                is JobOp.ConversationSet -> { title = op.title; true }
                is JobOp.ConversationSummary -> { summary = op.summary; true }
                is JobOp.ProfileSet -> setProfile(op, now)
                is JobOp.PersonAdd -> putPerson(op.name, op.relation, op.notes, now, createIfMissing = true)
                // A person he removed since the job started is not brought back.
                is JobOp.PersonUpdate -> putPerson(op.name, op.relation, op.notes, now, createIfMissing = false)
                is JobOp.EventAdd -> {
                    val row = EventRow(
                        conversationId = conversationId,
                        whenText = op.whenText,
                        situation = op.situation,
                        feelingBefore = op.feelingBefore,
                        action = op.action,
                        payoff = op.payoff,
                        outcome = op.outcome,
                        createdAt = now,
                    )
                    val id = dao.insertEvent(row)
                    if (conversation.offTheRecord == 0) search.indexEvent(row.copy(id = id))
                    true
                }
                is JobOp.NoteAdd -> addNote(op, conversationId, today, now, ::dayOf)
                is JobOp.NoteUpdate -> updateNote(op, conversationId, today, ::dayOf)
                is JobOp.NoteRetire -> retireNote(op.id)
                is JobOp.QuoteAdd -> {
                    if (dao.quoteCount(op.text) == 0) {
                        val id = dao.insertQuote(QuoteRow(conversationId = conversationId, text = op.text, saidDay = dayOf(op.text)))
                        if (conversation.offTheRecord == 0) search.indexQuote(id, op.text)
                    }
                    true
                }
                is JobOp.IdeaAdd -> item(conversationId, "idea", op.tag, "", today, now)
                is JobOp.DisagreementAdd -> item(conversationId, "disagreement", op.claim, op.hisPosition, today, now)
                is JobOp.ContradictionAdd -> item(conversationId, "contradiction", op.quoteA, op.quoteB, today, now)
                is JobOp.FocusSummary -> {
                    val focusId = conversation.focusId
                    if (focusId != null && dao.focus(focusId) != null) {
                        dao.setFocusSummary(focusId, op.summary, now)
                        true
                    } else {
                        false
                    }
                }
                // Gardening operations never come from a reflection; the applier cannot produce them here.
                is JobOp.NoteMerge, is JobOp.NoteRewrite, is JobOp.PersonMerge -> false
            }
            if (done) applied++ else skipped++
        }

        // The reflection's title replaces only the placeholder Talk wrote from his first message (or none);
        // a title he typed himself always stays.
        val placeholder = db.messages().ofConversation(conversationId).firstOrNull { it.role == "user" }
            ?.content?.trim()?.take(PLACEHOLDER_TITLE_CHARS)
        val newTitle = if (title.isNotBlank() && (conversation.title.isBlank() || conversation.title == placeholder)) {
            title
        } else {
            conversation.title
        }
        if (dao.markReflected(conversationId, upTo, summary, newTitle) != 1) {
            throw IllegalStateException("the conversation changed while it was being reflected")
        }
        if (conversation.offTheRecord == 0) search.indexSummary(conversationId, summary)
        // A conversation started before this summary existed (often seconds after this one ended, while it was
        // still being written) would otherwise not see it, or the notes it brought, until tomorrow.
        db.conversations().clearPrefixesExcept(conversationId)
        db.jobs().setState(
            id = job.id,
            status = StateMachines.move(StateMachines.JOB, job.status, "succeeded"),
            attempts = job.attempts,
            lastError = null,
            startedAt = job.startedAt,
            finishedAt = now,
        )
        Applied(applied, skipped)
    }

    /**
     * ApplyGardening: the monthly tidy (prompts/gardening.md), after [com.clarity.life.core.GardeningApplier]
     * validated it. One transaction. Every note, person and profile entry is checked again as it is now: a
     * note he edited, confirmed, deleted or that was retired since the job read it is left alone, and no
     * merged or rewritten text may come close to a note he deleted (rule 5). Returns null for a replay.
     */
    suspend fun applyGardening(job: JobRow, ops: List<JobOp>, now: Long): Applied? = db.withTransaction {
        val current = db.jobs().byKey(job.key)
        if (current == null || current.status != "running" || current.attempts != job.attempts) {
            return@withTransaction null
        }
        var applied = 0
        var skipped = 0
        for (op in ops) {
            val done: Boolean = when (op) {
                is JobOp.NoteMerge -> mergeNotes(op)
                is JobOp.NoteRewrite -> rewriteNote(op)
                is JobOp.NoteRetire -> retireNote(op.id)
                is JobOp.PersonMerge -> mergePeople(op, now)
                // Gardening may only rewrite an entry he has not confirmed: verified stays false.
                is JobOp.ProfileSet -> !op.verified && dao.profileEntry(op.key)?.let { it.confirmed == 0 } == true &&
                    setProfile(op, now)
                // Only reflection produces these; the gardening applier cannot.
                else -> false
            }
            if (done) applied++ else skipped++
        }
        db.jobs().setState(
            id = job.id,
            status = StateMachines.move(StateMachines.JOB, job.status, "succeeded"),
            attempts = job.attempts,
            lastError = null,
            startedAt = job.startedAt,
            finishedAt = now,
        )
        Applied(applied, skipped)
    }

    // ---------- his taps on What I know (each one transaction) ----------

    fun watchNotes() = dao.watchNotes()

    fun watchEvidence() = dao.watchEvidence()

    fun watchProfile() = dao.watchProfile()

    fun watchPeople() = dao.watchPeople()

    fun watchFocuses() = dao.watchFocuses()

    /** A focus with its current step, its last answered steps and its conversations, for the Focus page. */
    data class FocusDetail(
        val focus: com.clarity.life.data.entity.FocusRow,
        val current: com.clarity.life.data.entity.PromiseRow?,
        val last: List<com.clarity.life.data.entity.PromiseRow>,
        val conversations: List<com.clarity.life.data.entity.ConversationRow>,
    )

    suspend fun focusDetail(id: Long): FocusDetail? {
        val f = dao.focus(id) ?: return null
        return FocusDetail(f, dao.focusCurrentStep(id), dao.focusLastSteps(id, 6), dao.focusConversations(id))
    }

    fun watchChapters() = dao.watchChapters()

    /** "That's right": only his tap makes a note confirmed, and then no job may change it again. */
    suspend fun confirmNote(id: Long) = db.withTransaction {
        val n = dao.note(id) ?: return@withTransaction false
        if (n.status != "active") return@withTransaction false
        dao.updateNote(n.copy(confidence = "confirmed"))
        sendFreshMemory()
        true
    }

    /** Edit: his words replace the note's, and an edited note is his (confirmed, never touched by a job). */
    suspend fun editNote(id: Long, text: String) = db.withTransaction {
        val clean = text.trim()
        require(clean.isNotEmpty()) { "a note cannot be empty" }
        require(clean.length <= MAX_EDIT_CHARS) { "a note is at most $MAX_EDIT_CHARS characters" }
        val n = dao.note(id) ?: return@withTransaction false
        if (n.status != "active") return@withTransaction false
        dao.updateNote(n.copy(text = clean, confidence = "confirmed", editedByUser = 1))
        sendFreshMemory()
        true
    }

    /**
     * Delete: final (rule 5). The row stays as deleted_by_user, so no job can ever re-create the note or
     * anything close to it, and it is never shown or sent again.
     */
    suspend fun deleteNote(id: Long) = db.withTransaction {
        val n = dao.note(id) ?: return@withTransaction false
        if (n.status == "deleted_by_user") return@withTransaction true
        dao.updateNote(n.copy(status = StateMachines.move(StateMachines.NOTE, n.status, "deleted_by_user")))
        search.removeNote(id)
        sendFreshMemory()
        true
    }

    suspend fun confirmProfile(key: String) = db.withTransaction {
        val e = dao.profileEntry(key) ?: return@withTransaction false
        dao.putProfile(e.copy(confirmed = 1))
        sendFreshMemory()
        true
    }

    /** His correction wins and is marked confirmed (spec section 7). */
    suspend fun editProfile(key: String, value: String, now: Long) = db.withTransaction {
        val clean = value.trim()
        require(clean.isNotEmpty()) { "an entry cannot be empty" }
        require(clean.length <= MAX_EDIT_CHARS) { "an entry is at most $MAX_EDIT_CHARS characters" }
        val e = dao.profileEntry(key) ?: return@withTransaction false
        dao.putProfile(e.copy(value = clean, confirmed = 1, updatedAt = now))
        sendFreshMemory()
        true
    }

    suspend fun deleteProfile(key: String) = db.withTransaction {
        dao.deleteProfile(key)
        sendFreshMemory()
    }

    suspend fun editPerson(id: Long, relation: String, notes: String, now: Long) = db.withTransaction {
        val p = dao.person(id) ?: return@withTransaction false
        dao.updatePerson(p.copy(relation = relation.trim().take(60), notes = notes.trim().take(MAX_EDIT_CHARS), updatedAt = now))
        sendFreshMemory()
        true
    }

    suspend fun deletePerson(id: Long) = db.withTransaction {
        dao.deletePerson(id)
        sendFreshMemory()
    }

    /**
     * EditFocusSummary: his own words replace the rolling summary. The next reflection inside the focus
     * rewrites it from his version (spec section 3: focus summaries are shown and editable).
     */
    suspend fun editFocusSummary(id: Long, summary: String, now: Long) = db.withTransaction {
        dao.focus(id) ?: return@withTransaction false
        dao.setFocusSummary(id, summary.trim().take(MAX_FOCUS_SUMMARY_CHARS), now)
        sendFreshMemory()
        true
    }

    /** Pause, Done or Make active again, on his tap. A focus is never deleted, so its timeline stays. */
    suspend fun setFocusStatus(id: Long, status: String, now: Long) = db.withTransaction {
        com.clarity.life.data.Values.requireValid("focus.status", status)
        dao.focus(id) ?: return@withTransaction false
        dao.setFocusStatus(id, status, now)
        true
    }

    // ---------- the pieces of ApplyReflection ----------

    /**
     * His change to what the coach knows reaches every open conversation with its next message. The memory
     * blocks are otherwise frozen in each conversation's prefix for the day (contracts/context.md), so a note
     * he deleted or corrected was still sent, in its old words, to a conversation opened before (rule 5).
     * Each open conversation pays one uncached request for it, which is the right trade.
     */
    private suspend fun sendFreshMemory() = db.conversations().clearAllPrefixes()

    private suspend fun setProfile(op: JobOp.ProfileSet, now: Long): Boolean {
        val existing = dao.profileEntry(op.key)
        // He may have confirmed the entry after the job read it: then only his exact words may change it.
        if (existing != null && existing.confirmed == 1 && !op.verified) return false
        if (existing == null && dao.profile().size >= PROFILE_CAP) return false
        // Nothing a job returns makes an entry confirmed, and words he did not confirm cannot keep his badge.
        val confirmed = if (existing != null && existing.value == op.value) existing.confirmed else 0
        dao.putProfile(ProfileEntryRow(op.key, op.value, confirmed, now))
        return true
    }

    private suspend fun putPerson(
        name: String,
        relation: String,
        notes: String,
        now: Long,
        createIfMissing: Boolean,
    ): Boolean {
        val existing = dao.personNamed(name)
        if (existing == null) {
            if (!createIfMissing) return false
            dao.insertPerson(PersonRow(name = name, relation = relation, notes = notes, updatedAt = now))
        } else {
            dao.updatePerson(
                existing.copy(
                    relation = relation.ifBlank { existing.relation },
                    notes = notes.ifBlank { existing.notes },
                    updatedAt = now,
                ),
            )
        }
        return true
    }

    private suspend fun addNote(
        op: JobOp.NoteAdd,
        conversationId: Long,
        today: String,
        now: Long,
        dayOf: (String) -> String,
    ): Boolean {
        Values.requireValid("note.kind", op.kind)
        if (dao.activeCount(op.kind) >= NOTE_CAP_PER_KIND) return false
        // Checked again against the notes as they are now: he may have deleted a similar one while the job ran.
        if (dao.deletedNoteTexts().any { com.clarity.life.core.JobFields.similarity(op.text, it) >= com.clarity.life.core.JobFields.DELETED_SIMILARITY }) return false
        if (dao.activeNotes().any { com.clarity.life.core.JobFields.similarity(op.text, it.text) >= com.clarity.life.core.JobFields.DUPLICATE_SIMILARITY }) return false
        // Whatever the model asked for, a new note starts as a guess (the applier already made it so).
        val id = dao.insertNote(
            NoteRow(
                kind = op.kind,
                text = op.text,
                confidence = "guess",
                firstSeen = today,
                lastSeen = today,
                createdAt = now,
            ),
        )
        addEvidence(id, op.evidence, conversationId, today, dayOf)
        return true
    }

    private suspend fun updateNote(
        op: JobOp.NoteUpdate,
        conversationId: Long,
        today: String,
        dayOf: (String) -> String,
    ): Boolean {
        val n = dao.note(op.id) ?: return false
        // Deleted or retired since the job read it: his deletion is final, a retired note stays retired.
        if (n.status != "active") return false
        val his = n.editedByUser == 1 || n.confidence == "confirmed"
        // Only evidence this note does not have yet counts: the same count line cited every day is one sighting.
        val fresh = op.evidence.filter { dao.evidenceCount(n.id, it.kind, it.text) == 0 }
        val confidence = when {
            his -> n.confidence
            // Never to confirmed, and never more than one step up (the applier allows guess to likely only).
            op.confidence == "confirmed" -> n.confidence
            n.confidence == "guess" && op.confidence == "likely" && !(op.seenAgain && fresh.isNotEmpty()) ->
                n.confidence
            else -> op.confidence
        }
        Values.requireValid("note.confidence", confidence)
        val seen = op.seenAgain && fresh.isNotEmpty()
        val timesSeen = n.timesSeen + if (seen) 1 else 0
        Values.requireTimesSeen(timesSeen)
        val lastSeen = if (fresh.isNotEmpty() && today > n.lastSeen) today else n.lastSeen
        dao.updateNote(
            n.copy(
                text = if (his) n.text else op.text ?: n.text,
                confidence = confidence,
                timesSeen = timesSeen,
                lastSeen = lastSeen,
            ),
        )
        addEvidence(n.id, op.evidence, conversationId, today, dayOf)
        return true
    }

    /** A note a job may still change: active, never edited by him, not confirmed. */
    private fun jobMayTouch(n: NoteRow?): Boolean =
        n != null && n.status == "active" && n.editedByUser == 0 && n.confidence != "confirmed"

    private suspend fun closeToDeleted(text: String): Boolean =
        dao.deletedNoteTexts().any {
            com.clarity.life.core.JobFields.similarity(text, it) >= com.clarity.life.core.JobFields.DELETED_SIMILARITY
        }

    /**
     * The lowest id survives with the merged text; the others are retired (kept as history, like the archive
     * in What I know) and their evidence is copied onto the survivor, so nothing he said is lost. Confidence
     * is the highest the notes have now, never raised; times seen is their sum.
     */
    private suspend fun mergeNotes(op: JobOp.NoteMerge): Boolean {
        val rows = op.ids.map { dao.note(it) }
        if (!rows.all(::jobMayTouch)) return false
        val notes = rows.filterNotNull()
        if (notes.map { it.kind }.toSet().size != 1) return false
        if (closeToDeleted(op.text)) return false
        val conf = com.clarity.life.core.JobFields.CONF
        val confidence = notes.map { it.confidence }.maxBy { conf.indexOf(it) }
        val survivor = notes.minBy { it.id }
        val timesSeen = notes.sumOf { it.timesSeen }
        Values.requireTimesSeen(timesSeen)
        dao.updateNote(
            survivor.copy(
                text = op.text,
                confidence = confidence,
                timesSeen = timesSeen,
                firstSeen = notes.minOf { it.firstSeen },
                lastSeen = notes.maxOf { it.lastSeen },
            ),
        )
        for (other in notes) {
            if (other.id == survivor.id) continue
            for (e in dao.evidenceOf(other.id)) {
                if (dao.evidenceCount(survivor.id, e.kind, e.text) == 0) {
                    dao.insertEvidence(e.copy(id = 0, noteId = survivor.id))
                }
            }
            dao.updateNote(other.copy(status = StateMachines.move(StateMachines.NOTE, other.status, "retired")))
            search.indexRetiredNote(other.id, other.text)
        }
        return true
    }

    private suspend fun rewriteNote(op: JobOp.NoteRewrite): Boolean {
        val n = dao.note(op.id)
        if (!jobMayTouch(n) || n == null) return false
        if (closeToDeleted(op.text)) return false
        dao.updateNote(n.copy(text = op.text))
        return true
    }

    /**
     * The lowest id survives under the merged name; the others go. A name that already belongs to someone
     * outside the merge is left alone (two different people are never folded together by accident).
     */
    private suspend fun mergePeople(op: JobOp.PersonMerge, now: Long): Boolean {
        val people = op.ids.map { dao.person(it) ?: return false }
        val named = dao.personNamed(op.name)
        if (named != null && named.id !in op.ids) return false
        val survivor = people.minBy { it.id }
        for (p in people) if (p.id != survivor.id) dao.deletePerson(p.id)
        dao.updatePerson(
            survivor.copy(
                name = op.name,
                relation = op.relation.ifBlank { survivor.relation },
                notes = op.notes.ifBlank { survivor.notes },
                updatedAt = now,
            ),
        )
        return true
    }

    private suspend fun retireNote(id: Long): Boolean {
        val n = dao.note(id) ?: return false
        if (n.status != "active" || n.editedByUser == 1 || n.confidence == "confirmed") return false
        dao.updateNote(n.copy(status = StateMachines.move(StateMachines.NOTE, n.status, "retired")))
        // A retired note leaves NOTES but stays findable, like the archive in What I know.
        search.indexRetiredNote(n.id, n.text)
        return true
    }

    private suspend fun addEvidence(
        noteId: Long,
        evidence: List<Evidence>,
        conversationId: Long,
        today: String,
        dayOf: (String) -> String,
    ) {
        for (e in evidence) {
            Values.requireValid("note_evidence.kind", e.kind)
            if (dao.evidenceCount(noteId, e.kind, e.text) > 0) continue
            dao.insertEvidence(
                NoteEvidenceRow(
                    noteId = noteId,
                    kind = e.kind,
                    text = e.text,
                    day = if (e.kind == "quote") dayOf(e.text) else today,
                    conversationId = conversationId,
                ),
            )
        }
    }

    private suspend fun item(
        conversationId: Long,
        kind: String,
        text: String,
        hisWords: String,
        today: String,
        now: Long,
    ): Boolean {
        Values.requireValid("reflection_item.kind", kind)
        dao.insertItem(
            ReflectionItemRow(
                conversationId = conversationId,
                kind = kind,
                text = text,
                hisWords = hisWords,
                day = today,
                createdAt = now,
            ),
        )
        return true
    }

    companion object {
        /** Spec section 7: about 15 active notes per kind; gardening merges, a job never goes past it. */
        const val NOTE_CAP_PER_KIND = 15

        /** Spec section 7: at most about 30 durable profile entries. */
        const val PROFILE_CAP = 30

        /** Talk names a new conversation after the first 40 characters of his first message. */
        const val PLACEHOLDER_TITLE_CHARS = 40

        /** What he may type into one note or profile entry. */
        const val MAX_EDIT_CHARS = 500

        /** A focus summary is about 200 words (prompts/reflection.md); his edit gets room for more. */
        const val MAX_FOCUS_SUMMARY_CHARS = 2000
    }
}
