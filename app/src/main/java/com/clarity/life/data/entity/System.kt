package com.clarity.life.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The app's own housekeeping: settings, prompt overrides, the content-free error log, the usage counter and
 * the job table that makes background work idempotent. Columns match db/schema.sql exactly.
 */

/** Plain preferences only. The API keys live in the Android Keystore, never in the database. */
@Entity(tableName = "settings")
data class SettingRow(
    @PrimaryKey val key: String,
    val value: String,
)

@Entity(tableName = "prompt_override")
data class PromptOverrideRow(
    @PrimaryKey val name: String,
    val text: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** Never any message text, key or personal content: an area, a code and a short content-free detail. */
// Schema 7: Health and the yearly clean-up read the log by time.
@Entity(tableName = "error_log", indices = [Index(value = ["at"], name = "idx_error_at")])
data class ErrorLogRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    /** chat, reflection, review, letter, card, quote, number, backup ... */
    val area: String,
    /** http_429, json_rejected, card_dropped, quote_rejected ... */
    val code: String,
    @ColumnInfo(defaultValue = "") val detail: String = "",
)

@Entity(tableName = "usage_month")
data class UsageMonthRow(
    @PrimaryKey val month: String,
    @ColumnInfo(defaultValue = "0") val calls: Long = 0,
    @ColumnInfo(name = "tokens_in", defaultValue = "0") val tokensIn: Long = 0,
    @ColumnInfo(name = "tokens_out", defaultValue = "0") val tokensOut: Long = 0,
    /** Proves the provider's prefix cache is working (contracts/context.md). */
    @ColumnInfo(name = "tokens_cached", defaultValue = "0") val tokensCached: Long = 0,
)

/**
 * One row per unit of background work, with a deterministic key and a unique index, so a job that runs
 * twice is a no-op the second time (spec section 18, point 7).
 */
// Schema 7: start-up and Health find waiting or running jobs without reading every job ever run.
@Entity(tableName = "job", indices = [Index(value = ["key"], unique = true), Index(value = ["status"], name = "idx_job_status")])
data class JobRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** type + period or conversation + a hash of the inputs. */
    val key: String,
    val type: String,
    @ColumnInfo(defaultValue = "pending") val status: String = "pending",
    @ColumnInfo(defaultValue = "0") val attempts: Int = 0,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "started_at") val startedAt: Long? = null,
    @ColumnInfo(name = "finished_at") val finishedAt: Long? = null,
)
