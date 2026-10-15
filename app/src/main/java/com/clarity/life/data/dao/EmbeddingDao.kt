package com.clarity.life.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.clarity.life.data.entity.EmbeddingRow

/**
 * Meaning-search vectors (embedding_row). Derived data, keyed by the model's name: a new model means
 * Re-index, never a migration (spec section 7), and everything here can be made again from the raw rows.
 */
@Dao
interface EmbeddingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: EmbeddingRow)

    /** His messages without a vector for [model] yet (never an off-the-record conversation). */
    @Query(
        "SELECT m.id AS id, m.content AS text FROM message m JOIN conversation c ON c.id = m.conversation_id " +
            "WHERE m.role = 'user' AND c.off_the_record = 0 AND NOT EXISTS (SELECT 1 FROM embedding_row e " +
            "WHERE e.doc_kind = 'message' AND e.doc_id = m.id AND e.model = :model) ORDER BY m.id DESC LIMIT :limit",
    )
    suspend fun missingMessages(model: String, limit: Int): List<ToEmbed>

    /** Conversation summaries without a vector for [model], or whose summary changed since. */
    @Query(
        "SELECT c.id AS id, c.summary AS text FROM conversation c WHERE c.summary != '' AND c.off_the_record = 0 " +
            "AND NOT EXISTS (SELECT 1 FROM embedding_row e WHERE e.doc_kind = 'summary' AND e.doc_id = c.id " +
            "AND e.model = :model) ORDER BY c.id DESC LIMIT :limit",
    )
    suspend fun missingSummaries(model: String, limit: Int): List<ToEmbed>

    @Query(
        "SELECT q.id AS id, q.text AS text FROM quote q WHERE NOT EXISTS (SELECT 1 FROM embedding_row e " +
            "WHERE e.doc_kind = 'quote' AND e.doc_id = q.id AND e.model = :model) ORDER BY q.id DESC LIMIT :limit",
    )
    suspend fun missingQuotes(model: String, limit: Int): List<ToEmbed>

    @Query(
        "SELECT * FROM event v WHERE NOT EXISTS (SELECT 1 FROM embedding_row e " +
            "WHERE e.doc_kind = 'event' AND e.doc_id = v.id AND e.model = :model) ORDER BY v.id DESC LIMIT :limit",
    )
    suspend fun missingEvents(model: String, limit: Int): List<com.clarity.life.data.entity.EventRow>

    @Query(
        "SELECT n.id AS id, n.text AS text FROM note n WHERE n.status = 'retired' AND NOT EXISTS (SELECT 1 FROM " +
            "embedding_row e WHERE e.doc_kind = 'note' AND e.doc_id = n.id AND e.model = :model) ORDER BY n.id DESC LIMIT :limit",
    )
    suspend fun missingRetiredNotes(model: String, limit: Int): List<ToEmbed>

    /** Promises without a vector; the text (with the outcome) is made in code, as the index makes it. */
    @Query(
        "SELECT * FROM promise p WHERE NOT EXISTS (SELECT 1 FROM embedding_row e " +
            "WHERE e.doc_kind = 'promise' AND e.doc_id = p.id AND e.model = :model) ORDER BY p.id DESC LIMIT :limit",
    )
    suspend fun missingPromises(model: String, limit: Int): List<com.clarity.life.data.entity.PromiseRow>

    /** Which records of these kinds have a vector for [model] (the records themselves are read from search_doc). */
    @Query("SELECT doc_kind AS kind, doc_id AS docId FROM embedding_row WHERE model = :model AND doc_kind IN (:kinds)")
    suspend fun present(model: String, kinds: List<String>): List<KindId>

    @Query(
        "DELETE FROM embedding_row WHERE (doc_kind = 'quote' AND doc_id IN (SELECT id FROM quote WHERE conversation_id = :conversationId)) " +
            "OR (doc_kind = 'event' AND doc_id IN (SELECT id FROM event WHERE conversation_id = :conversationId))",
    )
    suspend fun deleteQuotesAndEventsOf(conversationId: Long)

    /** Every vector of one kind, when how that kind reads has changed; the indexer makes them again. */
    @Query("DELETE FROM embedding_row WHERE doc_kind = :kind")
    suspend fun deleteKind(kind: String)

    @Query("DELETE FROM embedding_row WHERE doc_kind = :kind AND doc_id = :docId")
    suspend fun delete(kind: String, docId: Long)

    /**
     * The in-memory cache's feed: rows added since [after] (by rowid), with the conversation each belongs to
     * (a message's, a summary's own; -1 for the other kinds, -2 when the message is gone). Every model's rows
     * come back, so the cache can count what it has seen; it keeps only the current model's.
     */
    @Query(
        "SELECT e.rowid AS rowId, e.doc_kind AS kind, e.doc_id AS docId, e.model AS model, e.dim AS dim, " +
            "e.vector AS vector, CASE e.doc_kind " +
            "WHEN 'message' THEN COALESCE((SELECT m.conversation_id FROM message m WHERE m.id = e.doc_id), -2) " +
            "WHEN 'summary' THEN e.doc_id ELSE -1 END AS conversationId " +
            "FROM embedding_row e WHERE e.rowid > :after ORDER BY e.rowid LIMIT :limit",
    )
    suspend fun rowsAfter(after: Long, limit: Int): List<CachedRow>

    /** Counted from the primary-key index, without reading the vectors. */
    @Query("SELECT COUNT(*) FROM embedding_row")
    suspend fun countAll(): Int

    @Query("SELECT vector FROM embedding_row WHERE doc_kind = :kind AND doc_id = :docId AND model = :model AND dim = :dim")
    suspend fun vectorOf(kind: String, docId: Long, model: String, dim: Int): ByteArray?

    /** Forget: the vectors of a conversation's messages go before the messages themselves. */
    @Query(
        "DELETE FROM embedding_row WHERE doc_kind = 'message' AND doc_id IN " +
            "(SELECT id FROM message WHERE conversation_id = :conversationId)",
    )
    suspend fun deleteMessagesOf(conversationId: Long)

    /** Re-index: every vector goes and is made again. */
    @Query("DELETE FROM embedding_row")
    suspend fun clear()

    /** Vectors of a model the app no longer runs (Max plan Phase 4): derived data, made again for the new one. */
    @Query("DELETE FROM embedding_row WHERE model != :model")
    suspend fun deleteOtherModels(model: String): Int

    @Query("SELECT COUNT(*) FROM embedding_row WHERE model = :model")
    suspend fun count(model: String): Int
}

data class ToEmbed(val id: Long, val text: String)

data class KindId(val kind: String, val docId: Long)

class CachedRow(
    val rowId: Long,
    val kind: String,
    val docId: Long,
    val model: String,
    val dim: Int,
    val vector: ByteArray,
    val conversationId: Long,
)
