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
        "DELETE FROM search_doc WHERE rowid IN (SELECT rowid FROM search_doc WHERE search_doc MATCH 'ref_id:' || :refId) " +
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
}

data class MessageHit(val messageId: Long, val conversationId: Long, val title: String, val at: Long, val text: String)

data class DocHit(val id: Long, val conversationId: Long?, val title: String, val at: Long, val text: String)

data class SummaryHit(val conversationId: Long, val title: String, val at: Long, val text: String)
