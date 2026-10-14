package com.clarity.life.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.clarity.life.data.entity.CardRow
import com.clarity.life.data.entity.ConversationRow
import com.clarity.life.data.entity.ErrorLogRow
import com.clarity.life.data.entity.JobRow
import com.clarity.life.data.entity.MessageRow
import com.clarity.life.data.entity.PromptOverrideRow
import com.clarity.life.data.entity.SettingRow
import com.clarity.life.data.entity.UsageMonthRow
import kotlinx.coroutines.flow.Flow

/**
 * The data access objects stage A needs. Screens never touch these directly: every write goes through a
 * named repository command, which is one transaction (spec section 18, point 2).
 */

@Dao
interface SettingsDao {
    @Query("SELECT value FROM settings WHERE key = :key")
    suspend fun get(key: String): String?

    @Query("SELECT * FROM settings")
    fun watchAll(): Flow<List<SettingRow>>

    @Upsert
    suspend fun put(row: SettingRow)

    @Query("DELETE FROM settings WHERE key = :key")
    suspend fun remove(key: String)
}

@Dao
interface ConversationDao {
    @Insert
    suspend fun insert(row: ConversationRow): Long

    @Update
    suspend fun update(row: ConversationRow)

    @Query("SELECT * FROM conversation WHERE id = :id")
    suspend fun get(id: Long): ConversationRow?

    @Query("SELECT * FROM conversation WHERE id = :id")
    fun watch(id: Long): Flow<ConversationRow?>

    /** The conversations drawer: newest first, paged by the caller. */
    @Query("SELECT * FROM conversation ORDER BY started_at DESC LIMIT :limit OFFSET :offset")
    fun watchRecent(limit: Int, offset: Int = 0): Flow<List<ConversationRow>>

    @Query(
        "SELECT * FROM conversation WHERE title LIKE '%' || :text || '%' " +
            "ORDER BY started_at DESC LIMIT :limit",
    )
    suspend fun searchByTitle(text: String, limit: Int): List<ConversationRow>

    /**
     * The drawer's search, by title and by the summary reflection wrote. [text] is already escaped for LIKE
     * (backslash before %, _ and backslash); empty lists every conversation, newest first.
     */
    @Query(
        "SELECT * FROM conversation WHERE :text = '' OR title LIKE '%' || :text || '%' ESCAPE '\\' " +
            "OR summary LIKE '%' || :text || '%' ESCAPE '\\' ORDER BY started_at DESC LIMIT :limit",
    )
    fun watchSearch(text: String, limit: Int): Flow<List<ConversationRow>>

    @Query("UPDATE conversation SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String)

    @Query("UPDATE conversation SET prefix_json = :prefixJson, prefix_day = :prefixDay WHERE id = :id")
    suspend fun setPrefix(id: Long, prefixJson: String?, prefixDay: String?)

    /** A prompt was edited or reset: every conversation builds its frozen context again on its next message. */
    @Query("UPDATE conversation SET prefix_json = NULL, prefix_day = NULL")
    suspend fun clearAllPrefixes()

    /** A reflection was applied: every other conversation builds its frozen context again, with the new summary. */
    @Query("UPDATE conversation SET prefix_json = NULL, prefix_day = NULL WHERE id != :exceptId AND prefix_json IS NOT NULL")
    suspend fun clearPrefixesExcept(exceptId: Long)

    @Query("UPDATE conversation SET history_start_id = :firstId WHERE id = :id AND history_start_id < :firstId")
    suspend fun moveHistoryStartForward(id: Long, firstId: Long)

    /** "Forget this conversation": the messages, cards, quotes, events and evidence cascade with it. */
    @Query("DELETE FROM conversation WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface MessageDao {
    @Insert
    suspend fun insert(row: MessageRow): Long

    @Query("SELECT * FROM message WHERE conversation_id = :conversationId ORDER BY id")
    fun watchConversation(conversationId: Long): Flow<List<MessageRow>>

    @Query("SELECT * FROM message WHERE conversation_id = :conversationId ORDER BY id")
    suspend fun ofConversation(conversationId: Long): List<MessageRow>

    @Query("SELECT * FROM message WHERE id = :id")
    suspend fun get(id: Long): MessageRow?

    /** The streaming reply is saved about once a second, so leaving the app never loses it. */
    @Query("UPDATE message SET content = :content WHERE id = :id")
    suspend fun setContent(id: Long, content: String)

    @Query("UPDATE message SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: String)

    @Query(
        "UPDATE message SET content = :content, status = :status, model = :model, " +
            "tokens_in = :tokensIn, tokens_out = :tokensOut WHERE id = :id",
    )
    suspend fun finish(
        id: Long,
        content: String,
        status: String,
        model: String?,
        tokensIn: Long?,
        tokensOut: Long?,
    )

    @Query("SELECT COUNT(*) FROM message WHERE conversation_id = :conversationId AND role = 'user'")
    suspend fun countHisMessages(conversationId: Long): Int

    /** His own words since [since] (not off the record): what the morning brief may quote, word for word. */
    @Query(
        "SELECT m.content FROM message m JOIN conversation c ON c.id = m.conversation_id " +
            "WHERE m.role = 'user' AND m.created_at >= :since AND c.off_the_record = 0 ORDER BY m.id",
    )
    suspend fun userTextSince(since: Long): List<String>
}

@Dao
interface CardDao {
    @Insert
    suspend fun insert(row: CardRow): Long

    @Query("SELECT * FROM card WHERE message_id = :messageId ORDER BY id")
    fun watchForMessage(messageId: Long): Flow<List<CardRow>>

    @Query("SELECT * FROM card WHERE id = :id")
    suspend fun get(id: Long): CardRow?

    /** The card shows "Saved" from the real database result, never from anything the model said. */
    @Query("UPDATE card SET status = :status, saved_ref = :savedRef, decided_at = :decidedAt WHERE id = :id")
    suspend fun decide(id: Long, status: String, savedRef: String?, decidedAt: Long)

    @Query(
        "SELECT * FROM card WHERE message_id IN " +
            "(SELECT id FROM message WHERE conversation_id = :conversationId) ORDER BY id DESC LIMIT :limit",
    )
    suspend fun recentOfConversation(conversationId: Long, limit: Int): List<CardRow>

    /** Every card of a conversation, oldest first: one query for the whole Talk screen. */
    @Query(
        "SELECT * FROM card WHERE message_id IN " +
            "(SELECT id FROM message WHERE conversation_id = :conversationId) ORDER BY id",
    )
    suspend fun ofConversation(conversationId: Long): List<CardRow>

    @Query("SELECT * FROM card WHERE message_id = :messageId ORDER BY id")
    suspend fun ofMessage(messageId: Long): List<CardRow>
}

@Dao
interface PromptOverrideDao {
    @Query("SELECT * FROM prompt_override")
    suspend fun all(): List<PromptOverrideRow>

    @Query("SELECT * FROM prompt_override WHERE name = :name")
    suspend fun get(name: String): PromptOverrideRow?

    @Upsert
    suspend fun put(row: PromptOverrideRow)

    /** Reset to built-in: the asset in the APK always works. */
    @Query("DELETE FROM prompt_override WHERE name = :name")
    suspend fun reset(name: String)
}

@Dao
interface DiagnosticsDao {
    @Insert
    suspend fun log(row: ErrorLogRow)

    @Query("SELECT * FROM error_log ORDER BY at DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<ErrorLogRow>

    @Query("SELECT COUNT(*) FROM error_log WHERE code = :code AND at >= :since")
    suspend fun countSince(code: String, since: Long): Int

    @Query("DELETE FROM error_log WHERE at < :before")
    suspend fun prune(before: Long)

    /** Health: how often each content-free error code was logged since [since], most frequent first. */
    @Query("SELECT code, COUNT(*) AS n FROM error_log WHERE at >= :since GROUP BY code ORDER BY n DESC, code")
    suspend fun codeCounts(since: Long): List<CodeCount>

    /** Health: the last finished run of each kind of background job. */
    @Query(
        "SELECT * FROM job WHERE id IN (SELECT MAX(id) FROM job WHERE status IN ('succeeded', 'failed', 'skipped') " +
            "GROUP BY type) ORDER BY type",
    )
    suspend fun lastFinishedJobs(): List<JobRow>

    @Query("SELECT COUNT(*) FROM job WHERE status IN ('pending', 'running')")
    suspend fun jobsWaiting(): Int

    @Query("SELECT COUNT(*) FROM message WHERE status = 'interrupted'")
    suspend fun interruptedReplies(): Int

    @Upsert
    suspend fun putUsage(row: UsageMonthRow)

    @Query("SELECT * FROM usage_month WHERE month = :month")
    suspend fun usage(month: String): UsageMonthRow?

    @Transaction
    suspend fun addUsage(month: String, calls: Long, tokensIn: Long, tokensOut: Long, tokensCached: Long) {
        val row = usage(month) ?: UsageMonthRow(month)
        putUsage(
            row.copy(
                calls = row.calls + calls,
                tokensIn = row.tokensIn + tokensIn,
                tokensOut = row.tokensOut + tokensOut,
                tokensCached = row.tokensCached + tokensCached,
            ),
        )
    }
}

@Dao
interface JobDao {
    /** The deterministic key is unique, so a second run of the same work simply finds nothing to claim. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfNew(row: JobRow): Long

    @Query("SELECT * FROM job WHERE key = :key")
    suspend fun byKey(key: String): JobRow?

    @Query("SELECT * FROM job WHERE status = :status ORDER BY id")
    suspend fun withStatus(status: String): List<JobRow>

    @Query(
        "UPDATE job SET status = :status, attempts = :attempts, last_error = :lastError, " +
            "started_at = :startedAt, finished_at = :finishedAt WHERE id = :id",
    )
    suspend fun setState(
        id: Long,
        status: String,
        attempts: Int,
        lastError: String?,
        startedAt: Long?,
        finishedAt: Long?,
    )

    @Query("SELECT * FROM job ORDER BY id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<JobRow>
}

/** The startup repair sweep (spec section 18, point 6). Both moves are allowed by contracts/states.json. */
@Dao
interface RepairDao {
    @Query("UPDATE message SET status = 'interrupted' WHERE status = 'streaming'")
    suspend fun interruptStreamingMessages(): Int

    /** Only jobs no worker has touched for a while: WorkManager may have started this process for a live one. */
    @Query("UPDATE job SET status = 'pending' WHERE status = 'running' AND (started_at IS NULL OR started_at < :staleBefore)")
    suspend fun requeueRunningJobs(staleBefore: Long): Int
}

/** One row of [DiagnosticsDao.codeCounts]. */
data class CodeCount(val code: String, val n: Int)
