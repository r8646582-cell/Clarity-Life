package com.clarity.life.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The conversation side of the database: focuses, conversations, messages and the cards under a reply.
 * Every column matches db/schema.sql exactly (SchemaTest compares the two), and the allowed values of the
 * TEXT columns live in [com.clarity.life.data.Values] so the CHECK lists in the schema and the code agree.
 */

@Entity(tableName = "focus")
data class FocusRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val why: String,
    @ColumnInfo(defaultValue = "active") val status: String = "active",
    @ColumnInfo(defaultValue = "") val summary: String = "",
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "conversation",
    foreignKeys = [
        ForeignKey(
            entity = FocusRow::class,
            parentColumns = ["id"],
            childColumns = ["focus_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    // Schema 7 (Max plan Phase 5): the drawer lists conversations newest first without sorting all of them.
    indices = [Index("focus_id"), Index(value = ["started_at"], name = "idx_conversation_started")],
)
data class ConversationRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(defaultValue = "") val title: String = "",
    @ColumnInfo(name = "chat_type", defaultValue = "free") val chatType: String = "free",
    @ColumnInfo(name = "focus_id") val focusId: Long? = null,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "ended_at") val endedAt: Long? = null,
    @ColumnInfo(defaultValue = "") val summary: String = "",
    @ColumnInfo(name = "reflected_up_to_message_id", defaultValue = "0") val reflectedUpToMessageId: Long = 0,
    @ColumnInfo(name = "off_the_record", defaultValue = "0") val offTheRecord: Int = 0,
    @ColumnInfo(name = "opened_from") val openedFrom: String? = null,
    @ColumnInfo(name = "opened_from_id") val openedFromId: Long? = null,
    /** The frozen Part 1 + Part 2 of the context packet (contracts/context.md). */
    @ColumnInfo(name = "prefix_json") val prefixJson: String? = null,
    /** The day key the prefix was built on; a later day rebuilds it. */
    @ColumnInfo(name = "prefix_day") val prefixDay: String? = null,
    /** First message id in the history window. It only ever moves forward. */
    @ColumnInfo(name = "history_start_id", defaultValue = "0") val historyStartId: Long = 0,
)

@Entity(
    tableName = "message",
    foreignKeys = [
        ForeignKey(
            entity = ConversationRow::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    // Schema 7: the start-up sweep finds replies still "streaming" without reading every message.
    indices = [
        Index(value = ["conversation_id", "id"], name = "idx_message_conv"),
        Index(value = ["status"], name = "idx_message_status"),
    ],
)
data class MessageRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "conversation_id") val conversationId: Long,
    val role: String,
    val content: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(defaultValue = "complete") val status: String = "complete",
    val model: String? = null,
    @ColumnInfo(name = "tokens_in") val tokensIn: Long? = null,
    @ColumnInfo(name = "tokens_out") val tokensOut: Long? = null,
)

/**
 * A card the model offered under a reply. It is only ever an offer: nothing reaches the rest of the
 * database until the owner taps its button, and then [savedRef] holds the real result the card shows as Saved.
 */
@Entity(
    tableName = "card",
    foreignKeys = [
        ForeignKey(
            entity = MessageRow::class,
            parentColumns = ["id"],
            childColumns = ["message_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["message_id"], name = "idx_card_message")],
)
data class CardRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "message_id") val messageId: Long,
    val type: String,
    /** The validated, normalised card as JSON. */
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    @ColumnInfo(defaultValue = "offered") val status: String = "offered",
    @ColumnInfo(name = "saved_ref") val savedRef: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "decided_at") val decidedAt: Long? = null,
)
