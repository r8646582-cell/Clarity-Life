package com.clarity.life.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The planner side of the database: goals and their revisions, plan versions, backlogs, targets, planned
 * rest days, check-ins and promises. Columns match db/schema.sql exactly.
 *
 * History is never deleted by a cascade from a goal or a target: those are ended or archived. A target with
 * check-ins cannot be deleted at all (RESTRICT).
 */

@Entity(
    tableName = "goal",
    foreignKeys = [
        ForeignKey(
            entity = FocusRow::class,
            parentColumns = ["id"],
            childColumns = ["focus_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("focus_id")],
)
data class GoalRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    @ColumnInfo(defaultValue = "general") val area: String = "general",
    @ColumnInfo(defaultValue = "custom") val horizon: String = "custom",
    val deadline: String? = null,
    @ColumnInfo(defaultValue = "active") val status: String = "active",
    @ColumnInfo(name = "focus_id") val focusId: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "goal_revision",
    foreignKeys = [
        ForeignKey(
            entity = GoalRow::class,
            parentColumns = ["id"],
            childColumns = ["goal_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("goal_id")],
)
data class GoalRevisionRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "goal_id") val goalId: Long,
    val text: String,
    @ColumnInfo(defaultValue = "") val reason: String = "",
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "plan",
    foreignKeys = [
        ForeignKey(
            entity = GoalRow::class,
            parentColumns = ["id"],
            childColumns = ["goal_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ConversationRow::class,
            parentColumns = ["id"],
            childColumns = ["source_conversation_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["goal_id", "version"], unique = true),
        Index("source_conversation_id"),
    ],
)
data class PlanRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "goal_id") val goalId: Long,
    val version: Int,
    val rationale: String,
    @ColumnInfo(name = "review_day") val reviewDay: String,
    @ColumnInfo(defaultValue = "active") val status: String = "active",
    @ColumnInfo(name = "source_conversation_id") val sourceConversationId: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "backlog",
    foreignKeys = [
        ForeignKey(
            entity = GoalRow::class,
            parentColumns = ["id"],
            childColumns = ["goal_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index(value = ["status"], name = "idx_backlog_status"), Index("goal_id")],
)
data class BacklogRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "goal_id") val goalId: Long? = null,
    val name: String,
    val total: Double,
    val unit: String,
    /** Already done before tracking began. Done = start_done + the check-in amounts of its targets. */
    @ColumnInfo(name = "start_done", defaultValue = "0") val startDone: Double = 0.0,
    val deadline: String,
    @ColumnInfo(defaultValue = "active") val status: String = "active",
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /**
     * His local day when it was created, written then (CLAUDE.md, Data and time), so a later change of time
     * zone never moves it. Null only for backlogs made before schema version 3, filled on first read.
     */
    @ColumnInfo(name = "start_day") val startDay: String? = null,
)

@Entity(
    tableName = "target",
    foreignKeys = [
        ForeignKey(
            entity = GoalRow::class,
            parentColumns = ["id"],
            childColumns = ["goal_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = PlanRow::class,
            parentColumns = ["id"],
            childColumns = ["plan_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = BacklogRow::class,
            parentColumns = ["id"],
            childColumns = ["backlog_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["status"], name = "idx_target_status"),
        Index("goal_id"), Index("plan_id"), Index("backlog_id"),
    ],
)
data class TargetRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "goal_id") val goalId: Long? = null,
    @ColumnInfo(name = "plan_id") val planId: Long? = null,
    @ColumnInfo(name = "backlog_id") val backlogId: Long? = null,
    val name: String,
    @ColumnInfo(defaultValue = "") val unit: String = "",
    @ColumnInfo(defaultValue = "0") val minimum: Double = 0.0,
    val target: Double,
    /** daily | weekdays | Mon,Wed,Fri | Sat-Thu | 3x/week, parsed by [com.clarity.life.core.Planner]. */
    val schedule: String,
    @ColumnInfo(name = "start_day") val startDay: String,
    @ColumnInfo(name = "end_day") val endDay: String? = null,
    @ColumnInfo(defaultValue = "active") val status: String = "active",
    /**
     * Schema 8 (Max plan Phase 6): his own estimate of the minutes one session at the target level takes, so the
     * app can add up a day's load. Null: not given (a target counted in minutes or hours needs none).
     */
    val minutes: Double? = null,
)

/**
 * Schema 8 (Max plan Phase 6): a target's values from a day on, so code knows what the plan asked for on any
 * past day and when it changed. One row per day a change took effect; written with every change he taps. A
 * target with no rows (made before schema 8, or restored from an older backup) is read as its current values
 * from its start day, and gets that row the first time it changes.
 */
@Entity(
    tableName = "target_version",
    foreignKeys = [
        ForeignKey(
            entity = TargetRow::class,
            parentColumns = ["id"],
            childColumns = ["target_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["target_id", "from_day"], unique = true, name = "idx_target_version_day")],
)
data class TargetVersionRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "target_id") val targetId: Long,
    @ColumnInfo(name = "from_day") val fromDay: String,
    val minimum: Double,
    val target: Double,
    val schedule: String,
    val status: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** A planned day off for every target: neither done nor missed, and not counted in days left. */
@Entity(tableName = "rest_day")
data class RestDayRow(
    @PrimaryKey @ColumnInfo(name = "day_key") val dayKey: String,
    @ColumnInfo(defaultValue = "") val note: String = "",
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** One answer per target per day. A missing row is "no answer", never a miss. */
@Entity(
    tableName = "checkin",
    foreignKeys = [
        ForeignKey(
            entity = TargetRow::class,
            parentColumns = ["id"],
            childColumns = ["target_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["target_id", "day_key"], unique = true),
        Index(value = ["day_key"], name = "idx_checkin_day"),
    ],
)
data class CheckinRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "target_id") val targetId: Long,
    @ColumnInfo(name = "day_key") val dayKey: String,
    val level: String,
    @ColumnInfo(defaultValue = "0") val amount: Double = 0.0,
    val reason: String? = null,
    val note: String? = null,
    @ColumnInfo(name = "entered_at") val enteredAt: Long,
    /** Schema 6: his local time of answering, minutes after midnight, written at write time (never moved). */
    @ColumnInfo(name = "entered_minute") val enteredMinute: Int? = null,
    /** Schema 6: his day key when he answered; an answer given on a later day says nothing about the day's time. */
    @ColumnInfo(name = "entered_day") val enteredDay: String? = null,
)

@Entity(
    tableName = "promise",
    foreignKeys = [
        ForeignKey(
            entity = ConversationRow::class,
            parentColumns = ["id"],
            childColumns = ["source_conversation_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["status", "due_at"], name = "idx_promise_status"),
        Index("source_conversation_id"),
        // Schema 7: every message reads the promises due in a period (OPEN_PROMISES, search) by due_at alone.
        Index(value = ["due_at"], name = "idx_promise_due"),
    ],
)
data class PromiseRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    @ColumnInfo(defaultValue = "") val why: String = "",
    @ColumnInfo(name = "due_at") val dueAt: Long,
    /** The wording as code resolved it, shown back to him before he taps. */
    @ColumnInfo(name = "due_text") val dueText: String,
    @ColumnInfo(defaultValue = "0") val remind: Int = 0,
    @ColumnInfo(defaultValue = "open") val status: String = "open",
    val reason: String? = null,
    @ColumnInfo(name = "source_conversation_id") val sourceConversationId: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "resolved_at") val resolvedAt: Long? = null,
)
