package com.clarity.life.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.clarity.life.data.entity.BriefRow
import kotlinx.coroutines.flow.Flow

/** The morning brief (spec section 8). Only the brief job writes here, and only its own table. */
@Dao
interface BriefDao {

    /** The key is unique: a second run for the same day inserts nothing and returns -1. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: BriefRow): Long

    @Query("SELECT * FROM brief WHERE `key` = :key")
    suspend fun byKey(key: String): BriefRow?

    /** The brief for [dayKey], or nothing: an old brief is never shown as today's. */
    @Query("SELECT * FROM brief WHERE day_key = :dayKey ORDER BY id DESC LIMIT 1")
    fun watchFor(dayKey: String): Flow<BriefRow?>

    @Query("SELECT * FROM brief WHERE day_key < :dayKey ORDER BY day_key DESC LIMIT 1")
    suspend fun before(dayKey: String): BriefRow?
}
