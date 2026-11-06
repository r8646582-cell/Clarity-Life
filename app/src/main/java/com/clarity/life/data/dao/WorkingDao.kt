package com.clarity.life.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.clarity.life.data.entity.UsageDayRow
import com.clarity.life.data.entity.WorkingItemEventRow
import com.clarity.life.data.entity.WorkingItemRow
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkingDao {
    @Insert
    suspend fun insert(row: WorkingItemRow): Long

    @Update
    suspend fun update(row: WorkingItemRow)

    @Insert
    suspend fun insertEvent(row: WorkingItemEventRow): Long

    @Query("SELECT * FROM working_item WHERE id = :id")
    suspend fun get(id: Long): WorkingItemRow?

    /** Not done and not dropped: the WORKING ON list, active first. */
    @Query("SELECT * FROM working_item WHERE status IN ('active','paused') ORDER BY CASE status WHEN 'active' THEN 0 ELSE 1 END, id")
    fun watchLive(): Flow<List<WorkingItemRow>>

    @Query("SELECT * FROM working_item WHERE status IN ('active','paused') ORDER BY CASE status WHEN 'active' THEN 0 ELSE 1 END, id")
    suspend fun live(): List<WorkingItemRow>

    @Query("SELECT * FROM working_item ORDER BY id")
    suspend fun all(): List<WorkingItemRow>

    @Query("SELECT COUNT(*) FROM working_item WHERE status = 'active'")
    suspend fun activeCount(): Int

    @Query("SELECT * FROM working_item_event WHERE item_id = :id ORDER BY id")
    suspend fun events(id: Long): List<WorkingItemEventRow>

    @Query("DELETE FROM working_item WHERE id = :id")
    suspend fun delete(id: Long)

    // ---- the daily counter (background cap) ----

    @Query("SELECT * FROM usage_day WHERE day = :day")
    suspend fun usageDay(day: String): UsageDayRow?

    @Query("INSERT OR IGNORE INTO usage_day (day, bg_calls, chat_messages) VALUES (:day, 0, 0)")
    suspend fun ensureDay(day: String)

    @Query("UPDATE usage_day SET bg_calls = bg_calls + 1 WHERE day = :day")
    suspend fun addBackgroundCall(day: String)

    @Query("UPDATE usage_day SET chat_messages = chat_messages + 1 WHERE day = :day")
    suspend fun addChatMessage(day: String)
}
