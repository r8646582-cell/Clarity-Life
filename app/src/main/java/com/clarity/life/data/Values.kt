package com.clarity.life.data

/**
 * The allowed values and the value rules of db/schema.sql, in Kotlin.
 *
 * Why this file exists: Room creates the tables, and Room cannot write SQLite CHECK constraints. So the
 * lists and the range rules the schema states as CHECK (... IN (...)) live here instead, and every write
 * goes through [requireValid]. SchemaTest parses db/schema.sql and fails if a list here and a CHECK list
 * there ever disagree, so the two cannot drift. The owner should know this is the one place where the app does
 * not get the database to enforce a schema rule for it; everything else (foreign keys, unique keys,
 * indexes, NOT NULL, defaults) is enforced by the database itself.
 */
object Values {

    /** table.column to the exact values the schema allows. */
    val ALLOWED: Map<String, Set<String>> = mapOf(
        "focus.status" to setOf("active", "paused", "done"),
        "conversation.chat_type" to setOf("free", "untangle", "decide", "plan"),
        "conversation.opened_from" to setOf("letter", "review"),
        "message.role" to setOf("user", "coach"),
        "message.status" to setOf("streaming", "complete", "interrupted", "failed"),
        "goal.area" to setOf(
            "health", "studies", "relationships", "money", "habits", "mind", "meaning", "rest_joy", "general",
        ),
        "goal.status" to setOf("active", "done", "archived"),
        "plan.status" to setOf("active", "replaced", "ended"),
        "backlog.status" to setOf("active", "done", "archived"),
        "target.status" to setOf("active", "paused", "ended"),
        "checkin.level" to setOf("rest", "not", "minimum", "target", "beyond"),
        "promise.status" to setOf("open", "kept", "not_done", "moved", "let_go"),
        "card.type" to setOf("promise", "plan", "target_change", "backlog", "focus", "note", "mode"),
        "card.status" to setOf("offered", "saved", "dismissed"),
        "note.kind" to setOf("pattern", "what_helps", "what_doesnt", "strength", "value", "thread"),
        "note.confidence" to setOf("guess", "likely", "confirmed"),
        "note.status" to setOf("active", "retired", "deleted_by_user"),
        "note_evidence.kind" to setOf("quote", "count"),
        "review.kind" to setOf("week", "month"),
        "reflection_item.kind" to setOf("idea", "disagreement", "contradiction"),
        "job.type" to setOf(
            "reflection", "review_week", "review_month", "letter", "chapter", "story", "gardening", "backup",
            "index",
        ),
        "job.status" to setOf("pending", "running", "succeeded", "failed", "skipped"),
    )

    /** Columns the schema restricts to 0 or 1. */
    val BOOLEAN_COLUMNS: Set<String> = setOf(
        "conversation.off_the_record",
        "promise.remind",
        "note.edited_by_user",
        "profile_entry.confirmed",
        "review.replan_flag",
    )

    class InvalidValue(message: String) : IllegalArgumentException(message)

    /** Throws [InvalidValue] when a value is not one the schema allows. Null passes: the column is nullable. */
    fun requireValid(column: String, value: String?) {
        if (value == null) return
        val allowed = ALLOWED[column] ?: throw InvalidValue("$column has no list of allowed values")
        if (value !in allowed) {
            throw InvalidValue("$column cannot be \"$value\"; the schema allows ${allowed.sorted()}")
        }
    }

    fun requireFlag(column: String, value: Int) {
        if (value != 0 && value != 1) throw InvalidValue("$column must be 0 or 1, not $value")
    }

    /** The numeric rules the schema states as CHECK, in the order the tables declare them. */
    fun requireTargetLevels(minimum: Double, target: Double) {
        if (minimum < 0) throw InvalidValue("a target's minimum cannot be below zero")
        if (target <= 0) throw InvalidValue("a target must be above zero")
        if (minimum > target) throw InvalidValue("a target's minimum cannot be above its target")
    }

    fun requireBacklogAmounts(total: Double, startDone: Double) {
        if (total <= 0) throw InvalidValue("a backlog total must be above zero")
        if (startDone < 0) throw InvalidValue("a backlog's done count cannot be below zero")
        if (startDone > total) throw InvalidValue("a backlog's done count cannot be above its total")
    }

    fun requireCheckinAmount(amount: Double) {
        if (amount < 0) throw InvalidValue("a check-in amount cannot be below zero")
    }

    fun requireTimesSeen(timesSeen: Int) {
        if (timesSeen < 1) throw InvalidValue("a note has been seen at least once")
    }
}
