package com.clarity.life.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.clarity.life.data.entity.LetterRow
import com.clarity.life.data.entity.ReviewRow
import kotlinx.coroutines.flow.Flow

/**
 * Weekly and monthly reviews and the weekly letter. Background jobs write the text here; nothing in these
 * tables can change a goal, a plan, a target or a promise. One review per period and one letter per week, month or year,
 * enforced by the unique keys.
 */
@Dao
interface WritingDao {
    // ---------- reviews ----------

    /** The key is unique: a second run for the same period inserts nothing and returns -1. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReview(row: ReviewRow): Long

    @Query("SELECT * FROM review WHERE `key` = :key")
    suspend fun reviewByKey(key: String): ReviewRow?

    @Query("SELECT * FROM review WHERE id = :id")
    suspend fun review(id: Long): ReviewRow?

    @Query("SELECT * FROM review WHERE id = :id")
    fun watchReview(id: Long): Flow<ReviewRow?>

    @Query("SELECT * FROM review ORDER BY period_start DESC, kind")
    fun watchReviews(): Flow<List<ReviewRow>>

    /** The latest review of [kind] that ended before [before]: the previous one, for continuity. */
    @Query("SELECT * FROM review WHERE kind = :kind AND period_end < :before ORDER BY period_end DESC LIMIT 1")
    suspend fun reviewBefore(kind: String, before: String): ReviewRow?

    @Query("SELECT * FROM review WHERE kind = 'week' AND period_start >= :from AND period_end <= :to ORDER BY period_start")
    suspend fun weeklyReviewsWithin(from: String, to: String): List<ReviewRow>

    /** Written once: a second run never replaces a note he may already have read. */
    @Query("UPDATE review SET narrative = :narrative, revisit_json = :revisitJson WHERE id = :id AND narrative = ''")
    suspend fun setNarrative(id: Long, narrative: String, revisitJson: String)

    /** Until its note is written, a review's figures follow the raw rows (a late check-in still counts). */
    @Query("UPDATE review SET facts_json = :factsJson, replan_flag = :replanFlag WHERE id = :id AND narrative = ''")
    suspend fun refreshFacts(id: Long, factsJson: String, replanFlag: Int)

    @Query("UPDATE review SET read_at = :at WHERE id = :id AND read_at IS NULL")
    suspend fun markReviewRead(id: Long, at: Long)

    /** His own messages in a time range, oldest first. Off-the-record conversations are never written about. */
    @Query(
        "SELECT content FROM message WHERE role = 'user' AND created_at >= :from AND created_at < :to " +
            "AND conversation_id IN (SELECT id FROM conversation WHERE off_the_record = 0) ORDER BY id",
    )
    suspend fun hisMessagesBetween(from: Long, to: Long): List<String>

    /** Conversations he wrote in during a time range. */
    @Query(
        "SELECT DISTINCT conversation_id FROM message WHERE role = 'user' AND created_at >= :from AND created_at < :to " +
            "AND conversation_id IN (SELECT id FROM conversation WHERE off_the_record = 0)",
    )
    suspend fun conversationsBetween(from: Long, to: Long): List<Long>

    @Query("SELECT * FROM focus WHERE status = 'active' ORDER BY id")
    suspend fun activeFocuses(): List<com.clarity.life.data.entity.FocusRow>

    // ---------- letters ----------

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLetter(row: LetterRow): Long

    @Query("SELECT * FROM letter WHERE `key` = :key")
    suspend fun letterByKey(key: String): LetterRow?

    @Query("SELECT * FROM letter WHERE id = :id")
    suspend fun letter(id: Long): LetterRow?

    @Query("SELECT * FROM letter WHERE id = :id")
    fun watchLetter(id: Long): Flow<LetterRow?>

    @Query("SELECT * FROM letter WHERE deleted_at IS NULL ORDER BY period_start DESC")
    fun watchLetters(): Flow<List<LetterRow>>

    /** The newest letter of one kind ([prefix] is "letter:week:", "letter:month:" or "letter:year:") before a day. */
    @Query(
        "SELECT * FROM letter WHERE deleted_at IS NULL AND `key` LIKE :prefix || '%' AND period_end < :before " +
            "ORDER BY period_end DESC LIMIT 1",
    )
    suspend fun letterBefore(prefix: String, before: String): LetterRow?

    /** The letters of one kind whose period ends from [from] to [to], oldest first (a month's weeks, a year's months). */
    @Query(
        "SELECT * FROM letter WHERE deleted_at IS NULL AND body != '' AND `key` LIKE :prefix || '%' " +
            "AND period_end >= :from AND period_end <= :to ORDER BY period_start",
    )
    suspend fun lettersEnding(prefix: String, from: String, to: String): List<LetterRow>

    @Query("UPDATE letter SET read_at = :at WHERE id = :id AND read_at IS NULL")
    suspend fun markLetterRead(id: Long, at: Long)

    /** Deleting keeps only the key, so the same week's letter is never written again. */
    @Query("UPDATE letter SET title = '', body = '', deleted_at = :at WHERE id = :id")
    suspend fun deleteLetter(id: Long, at: Long)

    // ---------- quarterly chapters ----------

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE)
    suspend fun insertChapter(row: com.clarity.life.data.entity.ChapterRow): Long

    @Query("SELECT * FROM chapter WHERE period_start = :start LIMIT 1")
    suspend fun chapterStarting(start: String): com.clarity.life.data.entity.ChapterRow?

    @Query("SELECT * FROM chapter WHERE period_end < :before ORDER BY period_end DESC LIMIT 1")
    suspend fun chapterBefore(before: String): com.clarity.life.data.entity.ChapterRow?

    /** CHAPTERS: newest first; the block keeps the last two. */
    @Query("SELECT * FROM chapter ORDER BY period_start DESC LIMIT :limit")
    suspend fun latestChapters(limit: Int): List<com.clarity.life.data.entity.ChapterRow>

    @Query("SELECT * FROM review WHERE kind = 'month' AND period_start >= :from AND period_end <= :to ORDER BY period_start")
    suspend fun monthlyReviewsWithin(from: String, to: String): List<ReviewRow>

    /** The weekly letters inside a quarter (the chapter reads the weeks, not the monthly letters made from them). */
    @Query(
        "SELECT * FROM letter WHERE deleted_at IS NULL AND `key` LIKE 'letter:week:%' AND period_start >= :from " +
            "AND period_end <= :to ORDER BY period_start",
    )
    suspend fun lettersWithin(from: String, to: String): List<LetterRow>

    /** The conversations of a period that reflection summarised (an off-the-record one has no summary). */
    @Query(
        "SELECT * FROM conversation WHERE started_at >= :from AND started_at < :to AND off_the_record = 0 " +
            "AND summary != '' ORDER BY started_at",
    )
    suspend fun summarisedBetween(from: Long, to: Long): List<com.clarity.life.data.entity.ConversationRow>

    @Query("SELECT * FROM quote WHERE said_day >= :from AND said_day <= :to ORDER BY said_day, id")
    suspend fun quotesWithin(from: String, to: String): List<com.clarity.life.data.entity.QuoteRow>

    @Query("SELECT * FROM focus ORDER BY id")
    suspend fun allFocuses(): List<com.clarity.life.data.entity.FocusRow>
}
