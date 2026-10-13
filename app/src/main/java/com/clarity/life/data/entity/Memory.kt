package com.clarity.life.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Memory: notes with their evidence, the profile, people, behaviour chains, verified quotes, the written
 * pieces (reviews, chapters, letters) and the derived search rows. Columns match db/schema.sql exactly.
 *
 * Only his own words and actions are evidence (rule 4): a coach claim never raises a note's confidence,
 * and a note he edited or confirmed is never touched by a background job.
 */

@Entity(
    tableName = "note",
    indices = [Index(value = ["status", "kind"], name = "idx_note_active")],
)
data class NoteRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val text: String,
    @ColumnInfo(defaultValue = "guess") val confidence: String = "guess",
    @ColumnInfo(defaultValue = "active") val status: String = "active",
    @ColumnInfo(name = "times_seen", defaultValue = "1") val timesSeen: Int = 1,
    @ColumnInfo(name = "first_seen") val firstSeen: String,
    @ColumnInfo(name = "last_seen") val lastSeen: String,
    @ColumnInfo(name = "edited_by_user", defaultValue = "0") val editedByUser: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "note_evidence",
    foreignKeys = [
        ForeignKey(
            entity = NoteRow::class,
            parentColumns = ["id"],
            childColumns = ["note_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ConversationRow::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["note_id"], name = "idx_evidence_note"),
        Index(value = ["conversation_id"], name = "idx_evidence_conv"),
    ],
)
data class NoteEvidenceRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "note_id") val noteId: Long,
    /** quote (his exact words, verified) or count (a figure copied from the app's own facts). */
    val kind: String,
    val text: String,
    val day: String,
    @ColumnInfo(name = "conversation_id") val conversationId: Long? = null,
)

@Entity(tableName = "profile_entry")
data class ProfileEntryRow(
    @PrimaryKey val key: String,
    val value: String,
    @ColumnInfo(defaultValue = "0") val confirmed: Int = 0,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "person", indices = [Index(value = ["name"], unique = true)])
data class PersonRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "name", collate = ColumnInfo.NOCASE) val name: String,
    @ColumnInfo(defaultValue = "") val relation: String = "",
    @ColumnInfo(defaultValue = "") val notes: String = "",
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** The behaviour chain a reflection may record: when, situation, feeling, action, payoff, outcome. */
@Entity(
    tableName = "event",
    foreignKeys = [
        ForeignKey(
            entity = ConversationRow::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversation_id")],
)
data class EventRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "conversation_id") val conversationId: Long? = null,
    @ColumnInfo(name = "when_text", defaultValue = "") val whenText: String = "",
    @ColumnInfo(defaultValue = "") val situation: String = "",
    @ColumnInfo(name = "feeling_before", defaultValue = "") val feelingBefore: String = "",
    @ColumnInfo(defaultValue = "") val action: String = "",
    @ColumnInfo(defaultValue = "") val payoff: String = "",
    @ColumnInfo(defaultValue = "") val outcome: String = "",
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(tableName = "review", indices = [Index(value = ["key"], unique = true)])
data class ReviewRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    @ColumnInfo(name = "period_start") val periodStart: String,
    @ColumnInfo(name = "period_end") val periodEnd: String,
    /** Derived and rebuildable from the raw rows. */
    @ColumnInfo(name = "facts_json") val factsJson: String,
    @ColumnInfo(defaultValue = "") val narrative: String = "",
    @ColumnInfo(name = "revisit_json", defaultValue = "[]") val revisitJson: String = "[]",
    @ColumnInfo(name = "replan_flag", defaultValue = "0") val replanFlag: Int = 0,
    @ColumnInfo(name = "read_at") val readAt: Long? = null,
    /** review:week:2026-10-05, so a period can only ever have one review. */
    val key: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "chapter",
    indices = [Index(value = ["period_start", "period_end"], unique = true)],
)
data class ChapterRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "period_start") val periodStart: String,
    @ColumnInfo(name = "period_end") val periodEnd: String,
    val title: String,
    val body: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(tableName = "letter", indices = [Index(value = ["key"], unique = true)])
data class LetterRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "period_start") val periodStart: String,
    @ColumnInfo(name = "period_end") val periodEnd: String,
    val title: String,
    val body: String,
    @ColumnInfo(name = "ready_at") val readyAt: Long,
    @ColumnInfo(name = "read_at") val readAt: Long? = null,
    /** On delete the title and body are blanked; the row stays so the letter is never regenerated. */
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    val key: String,
)

/**
 * Your story (schema 4): the monthly story job's short version, the then and now quotes it picked (by quote
 * id, so they are always word for word), and at most three "overcame" items as checked JSON. One per month.
 */
@Entity(tableName = "story", indices = [Index(value = ["key"], unique = true)])
data class StoryRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "period_start") val periodStart: String,
    @ColumnInfo(name = "period_end") val periodEnd: String,
    val short: String,
    @ColumnInfo(name = "then_quote_id") val thenQuoteId: Long? = null,
    @ColumnInfo(name = "now_quote_id") val nowQuoteId: Long? = null,
    @ColumnInfo(name = "overcame_json", defaultValue = "[]") val overcameJson: String = "[]",
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /** story:2026-10, so a month can only ever have one story. */
    val key: String,
)

/**
 * The morning brief (spec section 8): a few lines the coach writes each morning from finished facts. Inserted
 * only after TextJobCheck passes (kind "brief"); one per day. Nothing else reads it but the screens.
 */
@Entity(tableName = "brief", indices = [Index(value = ["key"], unique = true)])
data class BriefRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "day_key") val dayKey: String,
    val body: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /** brief:2026-10-20, so a day can only ever have one brief. */
    val key: String,
)

/** Only inserted after QuoteCheck passes, so a stored quote is always word for word his. */
@Entity(
    tableName = "quote",
    foreignKeys = [
        ForeignKey(
            entity = ConversationRow::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversation_id")],
)
data class QuoteRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "conversation_id") val conversationId: Long? = null,
    val text: String,
    @ColumnInfo(name = "said_day") val saidDay: String,
)

/**
 * What reflection learns that is neither a note nor a quote (schema version 2): an idea tag the coach used
 * (so it does not repeat itself), a disagreement he held his ground on, or two of his statements that do not
 * fit together. [hisWords] and, for a contradiction, [text] passed QuoteCheck before they were stored.
 */
@Entity(
    tableName = "reflection_item",
    foreignKeys = [
        ForeignKey(
            entity = ConversationRow::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["kind", "id"], name = "idx_reflection_item_kind"),
        Index("conversation_id"),
    ],
)
data class ReflectionItemRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "conversation_id") val conversationId: Long? = null,
    /** idea, disagreement or contradiction. */
    val kind: String,
    val text: String,
    @ColumnInfo(name = "his_words", defaultValue = "") val hisWords: String = "",
    val day: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** Derived search rows, rebuildable from messages, notes, reviews, letters, chapters and summaries. */
@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "search_doc")
data class SearchDocRow(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowId: Long? = null,
    val kind: String,
    @ColumnInfo(name = "ref_id") val refId: String,
    val title: String,
    val body: String,
)

@Entity(tableName = "embedding_row", primaryKeys = ["doc_kind", "doc_id", "model"])
data class EmbeddingRow(
    @ColumnInfo(name = "doc_kind") val docKind: String,
    @ColumnInfo(name = "doc_id") val docId: Long,
    val model: String,
    val dim: Int,
    val vector: ByteArray,
) {
    // A ByteArray needs these by hand, or two different vectors could compare equal.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EmbeddingRow) return false
        return docKind == other.docKind && docId == other.docId && model == other.model &&
            dim == other.dim && vector.contentEquals(other.vector)
    }

    override fun hashCode(): Int {
        var result = docKind.hashCode()
        result = 31 * result + docId.hashCode()
        result = 31 * result + model.hashCode()
        result = 31 * result + dim
        result = 31 * result + vector.contentHashCode()
        return result
    }
}
