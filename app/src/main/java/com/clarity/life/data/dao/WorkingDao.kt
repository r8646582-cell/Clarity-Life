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

    /** Every change in a span of days, with the item's name, for the letters' facts and a finished item's history. */
    @Query(
        "SELECT e.id AS id, e.item_id AS itemId, e.kind AS kind, e.day AS day, e.quote AS quote, e.detail AS detail, " +
            "e.created_at AS createdAt, i.name AS name FROM working_item_event e JOIN working_item i ON i.id = e.item_id " +
            "WHERE e.day >= :from AND e.day <= :to ORDER BY e.id",
    )
    suspend fun eventsBetween(from: String, to: String): List<EventWithName>

    data class EventWithName(
        val id: Long,
        val itemId: Long,
        val kind: String,
        val day: String,
        val quote: String,
        val detail: String,
        val createdAt: Long,
        val name: String,
    )

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
