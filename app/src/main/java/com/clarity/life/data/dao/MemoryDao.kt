package com.clarity.life.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.clarity.life.data.entity.ChapterRow
import com.clarity.life.data.entity.ConversationRow
import com.clarity.life.data.entity.EventRow
import com.clarity.life.data.entity.FocusRow
import com.clarity.life.data.entity.NoteEvidenceRow
import com.clarity.life.data.entity.NoteRow
import com.clarity.life.data.entity.PersonRow
import com.clarity.life.data.entity.ProfileEntryRow
import com.clarity.life.data.entity.QuoteRow
import com.clarity.life.data.entity.ReflectionItemRow
import kotlinx.coroutines.flow.Flow

/**
 * Memory: notes and their evidence, the profile, people, events, quotes and reflection items. Only
 * [com.clarity.life.memory.MemoryRepository] writes through this, one transaction per command.
 */
@Dao
interface MemoryDao {

    // ---------- notes ----------

    @Query("SELECT * FROM note ORDER BY id")
    suspend fun allNotes(): List<NoteRow>

    @Query("SELECT * FROM note WHERE status = 'active' ORDER BY kind, id")
    suspend fun activeNotes(): List<NoteRow>

    @Query("SELECT * FROM note ORDER BY id")
    fun watchNotes(): Flow<List<NoteRow>>

    @Query("SELECT * FROM note WHERE id = :id")
    suspend fun note(id: Long): NoteRow?

    @Query("SELECT text FROM note WHERE status = 'deleted_by_user' ORDER BY id")
    suspend fun deletedNoteTexts(): List<String>

    @Query("SELECT COUNT(*) FROM note WHERE status = 'active' AND kind = :kind")
    suspend fun activeCount(kind: String): Int

    @Insert
    suspend fun insertNote(row: NoteRow): Long

    @Update
    suspend fun updateNote(row: NoteRow)

    @Insert
    suspend fun insertEvidence(row: NoteEvidenceRow): Long

    @Query("SELECT * FROM note_evidence WHERE note_id = :noteId ORDER BY day, id")
    suspend fun evidenceOf(noteId: Long): List<NoteEvidenceRow>

    @Query("SELECT * FROM note_evidence ORDER BY note_id, day, id")
    fun watchEvidence(): Flow<List<NoteEvidenceRow>>

    @Query("SELECT * FROM note_evidence ORDER BY note_id, day, id")
    suspend fun allEvidence(): List<NoteEvidenceRow>

    @Query("SELECT COUNT(*) FROM note_evidence WHERE note_id = :noteId AND kind = :kind AND text = :text")
    suspend fun evidenceCount(noteId: Long, kind: String, text: String): Int

    @Query("SELECT DISTINCT note_id FROM note_evidence WHERE conversation_id = :conversationId")
    suspend fun notesWithEvidenceFrom(conversationId: Long): List<Long>

    /**
     * Forget this conversation, after its evidence cascaded away: a note learned only from that chat goes
     * with it. A note he edited or confirmed is his own and stays, and so does any note that never had
     * evidence (one he saved by tapping a card).
     */
    @Query(
        "DELETE FROM note WHERE id IN (:ids) AND edited_by_user = 0 AND confidence != 'confirmed' " +
            // A note he deleted stays as its tombstone: it is what keeps reflection from writing it again.
            "AND status != 'deleted_by_user' AND id NOT IN (SELECT note_id FROM note_evidence)",
    )
    suspend fun deleteNotesLeftWithoutEvidence(ids: List<Long>): Int

    // ---------- profile ----------

    @Query("SELECT * FROM profile_entry ORDER BY `key`")
    suspend fun profile(): List<ProfileEntryRow>

    @Query("SELECT * FROM profile_entry ORDER BY `key`")
    fun watchProfile(): Flow<List<ProfileEntryRow>>

    @Query("SELECT * FROM profile_entry WHERE `key` = :key")
    suspend fun profileEntry(key: String): ProfileEntryRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putProfile(row: ProfileEntryRow)

    @Query("DELETE FROM profile_entry WHERE `key` = :key")
    suspend fun deleteProfile(key: String)

    /** Values replaced by newer ones (schema 6), oldest first within a key. */
    @Query("SELECT * FROM profile_past ORDER BY `key`, id")
    suspend fun profilePast(): List<com.clarity.life.data.entity.ProfilePastRow>

    @Query("SELECT * FROM profile_past ORDER BY `key`, id")
    fun watchProfilePast(): Flow<List<com.clarity.life.data.entity.ProfilePastRow>>

    @Insert
    suspend fun insertProfilePast(row: com.clarity.life.data.entity.ProfilePastRow): Long

    /** Deleting an entry deletes what it used to say too: his deletions are final (rule 5). */
    @Query("DELETE FROM profile_past WHERE `key` = :key")
    suspend fun deleteProfilePast(key: String)

    // ---------- people ----------

    @Query("SELECT * FROM person ORDER BY name")
    suspend fun people(): List<PersonRow>

    @Query("SELECT * FROM person ORDER BY name")
    fun watchPeople(): Flow<List<PersonRow>>

    /** The name column is COLLATE NOCASE, so this ignores case like the unique index does. */
    @Query("SELECT * FROM person WHERE name = :name")
    suspend fun personNamed(name: String): PersonRow?

    @Query("SELECT * FROM person WHERE id = :id")
    suspend fun person(id: Long): PersonRow?

    @Insert
    suspend fun insertPerson(row: PersonRow): Long

    @Update
    suspend fun updatePerson(row: PersonRow)

    @Query("DELETE FROM person WHERE id = :id")
    suspend fun deletePerson(id: Long)

    // ---------- events, quotes, reflection items ----------

    @Insert
    suspend fun insertEvent(row: EventRow): Long

    @Query("SELECT * FROM event ORDER BY id DESC LIMIT :limit")
    suspend fun recentEvents(limit: Int): List<EventRow>

    @Insert
    suspend fun insertQuote(row: QuoteRow): Long

    @Query("SELECT * FROM quote ORDER BY id DESC LIMIT :limit")
    suspend fun recentQuotes(limit: Int): List<QuoteRow>

    @Query("SELECT COUNT(*) FROM quote WHERE text = :text")
    suspend fun quoteCount(text: String): Int

    @Insert
    suspend fun insertItem(row: ReflectionItemRow): Long

    @Query("SELECT * FROM reflection_item WHERE kind = :kind ORDER BY id DESC LIMIT :limit")
    suspend fun recentItems(kind: String, limit: Int): List<ReflectionItemRow>

    // ---------- the conversation and its focus ----------

    /**
     * RECENT_SESSIONS: the latest summaries of other conversations (an off-the-record one has none), newest by
     * their last message, so a conversation he came back to today counts as today's.
     */
    @Query(
        "SELECT c.id AS id, c.title AS title, c.summary AS summary, MIN(m.created_at) AS firstAt, " +
            "MAX(m.created_at) AS lastAt FROM conversation c JOIN message m ON m.conversation_id = c.id " +
            "WHERE c.id != :exceptId AND c.off_the_record = 0 AND c.summary != '' " +
            "GROUP BY c.id ORDER BY lastAt DESC LIMIT :limit",
    )
    suspend fun recentSessions(exceptId: Long, limit: Int): List<RecentSession>

    /**
     * LAST_CHAT: the other conversation with the newest message, and that message's time (contracts/context.md).
     * Null when there is none, or only off-the-record ones.
     */
    @Query(
        "SELECT c.id AS id, c.title AS title, c.summary AS summary, MIN(m.created_at) AS firstAt, " +
            "MAX(m.created_at) AS lastAt FROM conversation c JOIN message m ON m.conversation_id = c.id " +
            "WHERE c.id != :exceptId AND c.off_the_record = 0 " +
            "GROUP BY c.id ORDER BY lastAt DESC LIMIT 1",
    )
    suspend fun lastOtherConversation(exceptId: Long): RecentSession?

    @Query("SELECT * FROM focus ORDER BY CASE status WHEN 'active' THEN 0 WHEN 'paused' THEN 1 ELSE 2 END, updated_at DESC")
    fun watchFocuses(): Flow<List<FocusRow>>

    @Query("SELECT * FROM chapter ORDER BY period_start DESC")
    fun watchChapters(): Flow<List<ChapterRow>>

    @Query("SELECT * FROM focus WHERE id = :id")
    suspend fun focus(id: Long): FocusRow?

    @Query("UPDATE focus SET summary = :summary, updated_at = :now WHERE id = :id")
    suspend fun setFocusSummary(id: Long, summary: String, now: Long)

    @Query("UPDATE focus SET status = :status, updated_at = :now WHERE id = :id")
    suspend fun setFocusStatus(id: Long, status: String, now: Long)

    /** FOCUS, current step: the open promise due soonest that he set from a conversation in this focus. */
    @Query(
        "SELECT p.* FROM promise p JOIN conversation c ON p.source_conversation_id = c.id " +
            "WHERE c.focus_id = :focusId AND p.status = 'open' ORDER BY p.due_at LIMIT 1",
    )
    suspend fun focusCurrentStep(focusId: Long): com.clarity.life.data.entity.PromiseRow?

    /** FOCUS, last step results: the promises from this focus he answered most recently. */
    @Query(
        "SELECT p.* FROM promise p JOIN conversation c ON p.source_conversation_id = c.id " +
            "WHERE c.focus_id = :focusId AND p.status != 'open' ORDER BY p.resolved_at DESC, p.id DESC LIMIT :limit",
    )
    suspend fun focusLastSteps(focusId: Long, limit: Int): List<com.clarity.life.data.entity.PromiseRow>

    /** A focus's timeline: its conversations, newest first. */
    @Query("SELECT * FROM conversation WHERE focus_id = :focusId ORDER BY started_at DESC")
    suspend fun focusConversations(focusId: Long): List<ConversationRow>

    @Query("SELECT * FROM focus WHERE status = 'active' ORDER BY updated_at DESC")
    fun watchActiveFocuses(): Flow<List<FocusRow>>

    @Query("SELECT id FROM focus WHERE status != 'done'")
    suspend fun openFocusIds(): List<Long>

    @Query(
        "UPDATE conversation SET reflected_up_to_message_id = :upTo, summary = :summary, " +
            "title = :title " +
            "WHERE id = :id AND reflected_up_to_message_id < :upTo",
    )
    suspend fun markReflected(id: Long, upTo: Long, summary: String, title: String): Int

    /** Conversations with something he said that has not been reflected yet, with their last activity. */
    @Query(
        "SELECT c.id AS conversationId, MAX(m.created_at) AS lastAt FROM conversation c " +
            "JOIN message m ON m.conversation_id = c.id " +
            "WHERE c.off_the_record = 0 AND EXISTS (SELECT 1 FROM message u WHERE u.conversation_id = c.id " +
            "AND u.role = 'user' AND u.id > c.reflected_up_to_message_id) " +
            "GROUP BY c.id",
    )
    suspend fun unreflected(): List<Unreflected>
}

/** A conversation with the time of its first and its last message. */
data class RecentSession(val id: Long, val title: String, val summary: String, val firstAt: Long, val lastAt: Long)

/** A conversation with words of his not reflected yet, and the time of its last message. */
data class Unreflected(val conversationId: Long, val lastAt: Long)
