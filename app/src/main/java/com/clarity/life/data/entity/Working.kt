package com.clarity.life.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** One row per local day: the hard cap on background AI calls (docs/CLARITY-V2-BUILD-SPEC.md section 5). */
@Entity(tableName = "usage_day")
data class UsageDayRow(
    @PrimaryKey val day: String,
    @ColumnInfo(name = "bg_calls", defaultValue = "0") val bgCalls: Long = 0,
    @ColumnInfo(name = "chat_messages", defaultValue = "0") val chatMessages: Long = 0,
)

/** What they are working on (spec section 6): big things only; the keeper proposes, they confirm. */
@Entity(
    tableName = "working_item",
    indices = [Index(value = ["status"], name = "idx_working_item_status")],
)
data class WorkingItemRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(defaultValue = "active") val status: String = "active",
    @ColumnInfo(defaultValue = "0") val confirmed: Int = 0,
    @ColumnInfo(name = "deadline_date") val deadlineDate: String? = null,
    @ColumnInfo(name = "deadline_state", defaultValue = "none") val deadlineState: String = "none",
    @ColumnInfo(name = "size_text") val sizeText: String? = null,
    @ColumnInfo(name = "origin_quote") val originQuote: String,
    @ColumnInfo(name = "origin_day") val originDay: String,
    @ColumnInfo(name = "last_mentioned_day") val lastMentionedDay: String,
    @ColumnInfo(name = "asked_stale", defaultValue = "0") val askedStale: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** Every change to an item, with the owner's quote, so the old version is never lost. */
@Entity(
    tableName = "working_item_event",
    foreignKeys = [
        ForeignKey(
            entity = WorkingItemRow::class,
            parentColumns = ["id"],
            childColumns = ["item_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["item_id", "id"], name = "idx_working_item_event_item")],
)
data class WorkingItemEventRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "item_id") val itemId: Long,
    val kind: String,
    val day: String,
    @ColumnInfo(defaultValue = "") val quote: String = "",
    @ColumnInfo(defaultValue = "") val detail: String = "",
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
