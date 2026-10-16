package com.clarity.life.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.clarity.life.data.entity.SearchDocRow

/**
 * The keyword index (FTS4 over search_doc). Derived data: everything in it can be rebuilt from the raw
 * rows ([com.clarity.life.memory.SearchIndex.rebuild]), so a broken index costs a rebuild, never history.
 */
@Dao
interface SearchDao {
    @Insert
    suspend fun insert(row: SearchDocRow): Long

    /**
     * FTS4 has no index on a column compared with "=", so that alone reads every row (every message he ever
     * sent, on every message he sends). The full-text index finds the few rows with this id first.
     */
    @Query(
        "DELETE FROM search_doc WHERE rowid IN (SELECT rowid FROM search_doc WHERE search_doc MATCH 'ref_id:\"' || :refId || '\"') " +
            "AND kind = :kind AND ref_id = :refId",
    )
    suspend fun delete(kind: String, refId: String)

    @Query("DELETE FROM search_doc")
    suspend fun clear()

    /**
     * The newest matches first (without an order SQLite gives the oldest, and recent words would be lost),
     * leaving out the open conversation's own messages and summary: they are in its history already, and
     * read here they used up the places meant for his other conversations.
     */
    @Query(
        "SELECT rowid, kind, ref_id, title, body FROM search_doc WHERE search_doc MATCH :query " +
            "AND NOT (kind = 'message' AND ref_id IN " +
            "(SELECT CAST(id AS TEXT) FROM message WHERE conversation_id = :exceptConversation)) " +
            "AND NOT (kind = 'summary' AND ref_id = CAST(:exceptConversation AS TEXT)) " +
            "ORDER BY rowid DESC LIMIT :limit",
    )
    suspend fun match(query: String, exceptConversation: Long, limit: Int): List<SearchDocRow>

    @Query("SELECT COUNT(*) FROM search_doc")
    suspend fun count(): Int

    /** Forget: the index rows of a conversation's messages go before the messages themselves. */
    @Query(
        "DELETE FROM search_doc WHERE kind = 'message' AND ref_id IN " +
            "(SELECT CAST(id AS TEXT) FROM message WHERE conversation_id = :conversationId)",
    )
    suspend fun deleteMessagesOf(conversationId: Long)

    /** What a message hit is labelled with: its conversation, the conversation's title and when he said it. */
    @Query(
        "SELECT m.id AS messageId, m.conversation_id AS conversationId, c.title AS title, " +
            "m.created_at AS at, m.content AS text FROM message m JOIN conversation c ON c.id = m.conversation_id " +
            "WHERE m.id IN (:ids)",
    )
    suspend fun messageHits(ids: List<Long>): List<MessageHit>

    @Query(
        "SELECT id AS conversationId, title, started_at AS at, summary AS text FROM conversation WHERE id IN (:ids)",
    )
    suspend fun summaryHits(ids: List<Long>): List<SummaryHit>

    /** His verified quotes, with the conversation they came from (for the label, and to skip the open one). */
    @Query(
        "SELECT q.id AS id, q.conversation_id AS conversationId, COALESCE(c.title, '') AS title, " +
            "COALESCE(c.started_at, 0) AS at, q.text AS text FROM quote q LEFT JOIN conversation c ON c.id = q.conversation_id " +
            "WHERE q.id IN (:ids)",
    )
    suspend fun quoteHits(ids: List<Long>): List<DocHit>

    @Query("SELECT * FROM event WHERE id IN (:ids)")
    suspend fun events(ids: List<Long>): List<com.clarity.life.data.entity.EventRow>

    /** Retired notes only: an active note is in NOTES already, and a deleted one is never shown again. */
    @Query("SELECT * FROM note WHERE id IN (:ids) AND status = 'retired'")
    suspend fun retiredNotes(ids: List<Long>): List<com.clarity.life.data.entity.NoteRow>

    @Query("SELECT id AS id, conversation_id AS conversationId, '' AS title, 0 AS at, text AS text FROM quote")
    suspend fun allQuotes(): List<DocHit>

    @Query("SELECT * FROM event")
    suspend fun allEvents(): List<com.clarity.life.data.entity.EventRow>

    @Query("SELECT * FROM promise WHERE id IN (:ids)")
    suspend fun promises(ids: List<Long>): List<com.clarity.life.data.entity.PromiseRow>

    @Query("SELECT * FROM promise")
    suspend fun allPromises(): List<com.clarity.life.data.entity.PromiseRow>

    @Query("SELECT * FROM note WHERE status = 'retired'")
    suspend fun allRetiredNotes(): List<com.clarity.life.data.entity.NoteRow>

    /** Forget: the index rows of the conversation's quotes and events go before the rows themselves. */
    @Query(
        "DELETE FROM search_doc WHERE (kind = 'quote' AND ref_id IN (SELECT CAST(id AS TEXT) FROM quote WHERE conversation_id = :conversationId)) " +
            "OR (kind = 'event' AND ref_id IN (SELECT CAST(id AS TEXT) FROM event WHERE conversation_id = :conversationId))",
    )
    suspend fun deleteQuotesAndEventsOf(conversationId: Long)

    /** The nightly check: his messages with words in them that the keyword index does not hold. */
    @Query(
        "SELECT COUNT(*) FROM message m JOIN conversation c ON c.id = m.conversation_id " +
            "WHERE m.role = 'user' AND c.off_the_record = 0 AND TRIM(m.content) != '' " +
            "AND CAST(m.id AS TEXT) NOT IN (SELECT ref_id FROM search_doc WHERE kind = 'message')",
    )
    suspend fun unindexedMessages(): Int

    @Query(
        "SELECT COUNT(*) FROM conversation WHERE summary != '' AND off_the_record = 0 " +
            "AND CAST(id AS TEXT) NOT IN (SELECT ref_id FROM search_doc WHERE kind = 'summary')",
    )
    suspend fun unindexedSummaries(): Int

    /** Rebuild: every message of his, from every conversation that was not off the record. */
    @Query(
        "SELECT m.id AS messageId, m.conversation_id AS conversationId, c.title AS title, m.created_at AS at, " +
            "m.content AS text FROM message m JOIN conversation c ON c.id = m.conversation_id " +
            "WHERE m.role = 'user' AND c.off_the_record = 0 ORDER BY m.id",
    )
    suspend fun allHisMessages(): List<MessageHit>

    @Query(
        "SELECT id AS conversationId, title, started_at AS at, summary AS text FROM conversation " +
            "WHERE summary != '' AND off_the_record = 0",
    )
    suspend fun allSummaries(): List<SummaryHit>
    // ---------- Max plan Phase 2: every record kept is searchable, and periods are looked up ----------

    /** The index rows of one kind ([query] is "kind:letter"), to compare with the records they come from. */
    @Query("SELECT kind, ref_id AS refId, body FROM search_doc WHERE search_doc MATCH :query")
    suspend fun bodies(query: String): List<IndexedBody>

    @Query("SELECT COUNT(*) FROM search_doc WHERE search_doc MATCH :query")
    suspend fun countMatching(query: String): Int

    @Query("SELECT * FROM letter WHERE deleted_at IS NULL AND body != ''")
    suspend fun allLetters(): List<com.clarity.life.data.entity.LetterRow>

    @Query("SELECT * FROM review")
    suspend fun allReviews(): List<com.clarity.life.data.entity.ReviewRow>

    @Query("SELECT * FROM chapter")
    suspend fun allChapters(): List<com.clarity.life.data.entity.ChapterRow>

    @Query("SELECT * FROM goal")
    suspend fun allGoals(): List<com.clarity.life.data.entity.GoalRow>

    @Query(
        "SELECT r.id AS id, g.title AS goalTitle, r.text AS text, r.reason AS reason, r.created_at AS createdAt " +
            "FROM goal_revision r JOIN goal g ON g.id = r.goal_id",
    )
    suspend fun allRevisions(): List<RevisionHit>

    @Query("SELECT * FROM target")
    suspend fun allTargets(): List<com.clarity.life.data.entity.TargetRow>

    /** Check-ins with words in them (a reason or a note): the ones worth searching by word. */
    @Query(
        "SELECT c.id AS id, c.day_key AS day, c.level AS level, c.amount AS amount, c.reason AS reason, c.note AS note, " +
            "t.name AS targetName, t.unit AS unit FROM checkin c JOIN target t ON t.id = c.target_id " +
            "WHERE TRIM(COALESCE(c.reason, '')) != '' OR TRIM(COALESCE(c.note, '')) != ''",
    )
    suspend fun checkinsWithWords(): List<CheckinHit>

    @Query("SELECT * FROM letter WHERE id IN (:ids) AND deleted_at IS NULL AND body != ''")
    suspend fun letters(ids: List<Long>): List<com.clarity.life.data.entity.LetterRow>

    @Query("SELECT * FROM review WHERE id IN (:ids)")
    suspend fun reviews(ids: List<Long>): List<com.clarity.life.data.entity.ReviewRow>

    @Query("SELECT * FROM chapter WHERE id IN (:ids)")
    suspend fun chapters(ids: List<Long>): List<com.clarity.life.data.entity.ChapterRow>

    @Query("SELECT * FROM goal WHERE id IN (:ids)")
    suspend fun goals(ids: List<Long>): List<com.clarity.life.data.entity.GoalRow>

    @Query(
        "SELECT r.id AS id, g.title AS goalTitle, r.text AS text, r.reason AS reason, r.created_at AS createdAt " +
            "FROM goal_revision r JOIN goal g ON g.id = r.goal_id WHERE r.id IN (:ids)",
    )
    suspend fun revisions(ids: List<Long>): List<RevisionHit>

    @Query("SELECT * FROM target WHERE id IN (:ids)")
    suspend fun targets(ids: List<Long>): List<com.clarity.life.data.entity.TargetRow>

    @Query(
        "SELECT c.id AS id, c.day_key AS day, c.level AS level, c.amount AS amount, c.reason AS reason, c.note AS note, " +
            "t.name AS targetName, t.unit AS unit FROM checkin c JOIN target t ON t.id = c.target_id WHERE c.id IN (:ids)",
    )
    suspend fun checkins(ids: List<Long>): List<CheckinHit>

    /** The period list: promises due in it ([from] inclusive, [to] exclusive, epoch milliseconds). */
    @Query("SELECT * FROM promise WHERE due_at >= :from AND due_at < :to")
    suspend fun promisesDueBetween(from: Long, to: Long): List<com.clarity.life.data.entity.PromiseRow>

    /** A question about promises with no period: the newest [limit] of them. */
    @Query("SELECT * FROM promise ORDER BY due_at DESC LIMIT :limit")
    suspend fun latestPromises(limit: Int): List<com.clarity.life.data.entity.PromiseRow>

    /**
     * Conversations held in the period (a message in it), with a summary, never off the record; [at] is the
     * last message's time. Read only when he names a period.
     */
    @Query(
        "SELECT c.id AS conversationId, c.title AS title, MAX(m.created_at) AS at, c.summary AS text " +
            "FROM conversation c JOIN message m ON m.conversation_id = c.id " +
            "WHERE c.off_the_record = 0 AND c.summary != '' AND c.started_at < :to " +
            "GROUP BY c.id HAVING MAX(m.created_at) >= :from ORDER BY at DESC LIMIT :limit",
    )
    suspend fun conversationsBetween(from: Long, to: Long, limit: Int): List<SummaryHit>

    @Query("SELECT * FROM letter WHERE deleted_at IS NULL AND body != '' AND period_start <= :to AND period_end >= :from")
    suspend fun lettersOverlapping(from: String, to: String): List<com.clarity.life.data.entity.LetterRow>

    @Query("SELECT * FROM review WHERE period_start <= :to AND period_end >= :from")
    suspend fun reviewsOverlapping(from: String, to: String): List<com.clarity.life.data.entity.ReviewRow>

    @Query("SELECT * FROM chapter WHERE period_start <= :to AND period_end >= :from")
    suspend fun chaptersOverlapping(from: String, to: String): List<com.clarity.life.data.entity.ChapterRow>

    @Query(
        "SELECT r.id AS id, g.title AS goalTitle, r.text AS text, r.reason AS reason, r.created_at AS createdAt " +
            "FROM goal_revision r JOIN goal g ON g.id = r.goal_id WHERE r.created_at >= :from AND r.created_at < :to",
    )
    suspend fun revisionsBetween(from: Long, to: Long): List<RevisionHit>

    @Query(
        "SELECT c.id AS id, c.day_key AS day, c.level AS level, c.amount AS amount, c.reason AS reason, c.note AS note, " +
            "t.name AS targetName, t.unit AS unit FROM checkin c JOIN target t ON t.id = c.target_id " +
            "WHERE c.day_key >= :from AND c.day_key <= :to",
    )
    suspend fun checkinsBetween(from: String, to: String): List<CheckinHit>
}

data class IndexedBody(val kind: String, val refId: String, val body: String)

data class RevisionHit(val id: Long, val goalTitle: String, val text: String, val reason: String, val createdAt: Long)

data class CheckinHit(
    val id: Long,
    val day: String,
    val level: String,
    val amount: Double,
    val reason: String?,
    val note: String?,
    val targetName: String,
    val unit: String,
)


data class MessageHit(val messageId: Long, val conversationId: Long, val title: String, val at: Long, val text: String)

data class DocHit(val id: Long, val conversationId: Long?, val title: String, val at: Long, val text: String)

data class SummaryHit(val conversationId: Long, val title: String, val at: Long, val text: String)
