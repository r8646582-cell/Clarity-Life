package com.clarity.life.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.clarity.life.data.entity.FocusRow
import com.clarity.life.data.entity.PromiseRow
import kotlinx.coroutines.flow.Flow

/**
 * Promises, and the focus rows a focus card creates. Only [com.clarity.life.promise.PromiseRepository] and
 * [com.clarity.life.chat.CardSaver] write through this; every write follows a tap of his.
 */
@Dao
interface PromiseDao {
    @Insert
    suspend fun insert(row: PromiseRow): Long

    @Query("SELECT * FROM promise WHERE id = :id")
    suspend fun get(id: Long): PromiseRow?

    /** The status moves are checked by StateMachines before this is called. */
    @Query("UPDATE promise SET status = :status, reason = :reason, resolved_at = :resolvedAt WHERE id = :id")
    suspend fun resolve(id: Long, status: String, reason: String?, resolvedAt: Long?)

    @Query("SELECT * FROM promise WHERE status = 'open' ORDER BY due_at, id")
    suspend fun open(): List<PromiseRow>

    @Query("SELECT * FROM promise WHERE status = 'open' ORDER BY due_at, id")
    fun watchOpen(): Flow<List<PromiseRow>>

    /** Past outcomes, newest first, for Plan, Promises. */
    @Query("SELECT * FROM promise WHERE status != 'open' ORDER BY COALESCE(resolved_at, due_at) DESC, id DESC LIMIT :limit")
    fun watchPast(limit: Int): Flow<List<PromiseRow>>

    /** Answered promises due after [since], for the follow-up lines in OPEN_PROMISES. */
    @Query("SELECT * FROM promise WHERE status != 'open' AND due_at > :since ORDER BY due_at DESC, id DESC")
    suspend fun answeredSince(since: Long): List<PromiseRow>

    /** Every open promise that asked for a reminder and is still ahead: set again after a restart. */
    @Query("SELECT * FROM promise WHERE status = 'open' AND remind = 1 AND due_at > :now ORDER BY due_at")
    suspend fun remindersAfter(now: Long): List<PromiseRow>

    @Insert
    suspend fun insertFocus(row: FocusRow): Long
}
