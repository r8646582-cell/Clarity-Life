package com.clarity.life.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.clarity.life.data.entity.BacklogRow
import com.clarity.life.data.entity.FocusRow
import com.clarity.life.data.entity.NoteRow
import com.clarity.life.data.entity.QuoteRow
import com.clarity.life.data.entity.StoryRow
import kotlinx.coroutines.flow.Flow

/**
 * Your story (spec 2.5): the monthly story rows, and the reads the story page and the story job share. Only
 * the story job writes here, and only its own table; nothing here changes a goal, a plan, a target, a promise
 * or a note.
 */
@Dao
interface StoryDao {

    /** The key is unique: a second run for the same month inserts nothing and returns -1. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertStory(row: StoryRow): Long

    @Query("SELECT * FROM story WHERE `key` = :key")
    suspend fun storyByKey(key: String): StoryRow?

    @Query("SELECT * FROM story ORDER BY period_start DESC LIMIT 1")
    fun watchLatestStory(): Flow<StoryRow?>

    @Query("SELECT * FROM story WHERE period_end < :before ORDER BY period_end DESC LIMIT 1")
    suspend fun storyBefore(before: String): StoryRow?

    // ---------- where the story begins ----------

    @Query("SELECT MIN(started_at) FROM conversation")
    suspend fun firstConversationAt(): Long?

    @Query("SELECT MIN(day_key) FROM checkin")
    suspend fun firstCheckinDay(): String?

    // ---------- what it counts ----------

    /** Every backlog but the archived ones: a finished one stays part of the story. */
    @Query("SELECT * FROM backlog WHERE status != 'archived' ORDER BY id")
    suspend fun storyBacklogs(): List<BacklogRow>

    @Query("SELECT COUNT(*) FROM promise WHERE status = 'kept' AND resolved_at >= :from AND resolved_at < :to")
    suspend fun promisesKept(from: Long, to: Long): Int

    @Query("SELECT COUNT(*) FROM promise WHERE status != 'open' AND resolved_at >= :from AND resolved_at < :to")
    suspend fun promisesAnswered(from: Long, to: Long): Int

    @Query("SELECT COUNT(DISTINCT day_key) FROM checkin WHERE level != 'rest' AND day_key >= :from AND day_key <= :to")
    suspend fun checkinDays(from: String, to: String): Int

    /** The focuses he marked done: the things he got through. */
    @Query("SELECT * FROM focus WHERE status = 'done' ORDER BY updated_at")
    suspend fun doneFocuses(): List<FocusRow>

    /** The steps he kept in a focus: kept promises set from a conversation in it. */
    @Query(
        "SELECT COUNT(*) FROM promise p JOIN conversation c ON p.source_conversation_id = c.id " +
            "WHERE c.focus_id = :focusId AND p.status = 'kept'",
    )
    suspend fun focusStepsKept(focusId: Long): Int

    @Query(
        "SELECT p.text FROM promise p JOIN conversation c ON p.source_conversation_id = c.id " +
            "WHERE c.focus_id = :focusId AND p.status = 'kept' ORDER BY p.resolved_at, p.id",
    )
    suspend fun focusStepsKeptText(focusId: Long): List<String>

    /** What helps him and what he is good at, the most seen first. */
    @Query(
        "SELECT * FROM note WHERE status = 'active' AND kind IN ('what_helps', 'strength') " +
            "ORDER BY times_seen DESC, id",
    )
    fun watchHelpsAndStrengths(): Flow<List<NoteRow>>

    // ---------- his words ----------

    @Query("SELECT * FROM quote WHERE id IN (:ids)")
    suspend fun quotes(ids: List<Long>): List<QuoteRow>

    @Query("SELECT * FROM quote ORDER BY said_day, id LIMIT :limit")
    suspend fun earliestQuotes(limit: Int): List<QuoteRow>

    @Query("SELECT * FROM quote ORDER BY said_day DESC, id DESC LIMIT :limit")
    suspend fun latestQuotes(limit: Int): List<QuoteRow>
}
