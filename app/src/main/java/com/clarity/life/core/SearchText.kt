package com.clarity.life.core

import java.time.LocalDate

/**
 * What each kind of record reads like in search and in RELEVANT_PAST (Max plan Phase 2 step 4: everything they
 * keep is searchable). A 1:1 port of the text functions of tools/retrieval.py; RetrievalVectorsTest runs them
 * against contracts/retrieval_vectors.json.
 *
 * Each text starts with what the record is ("Letter", "Goal", "Check-in"), so a question that names the kind of
 * thing ("which letter...", "my September review") finds it, and dates are written with the month name, so "in
 * August" also matches by word. Only the text that is searched and shown is made here; nothing is stored.
 */
object SearchText {

    private val MONTH_ABBR = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private val MONTH_FULL = listOf(
        "January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November",
        "December",
    )

    /** 2026-10-05 -> 5 Oct 2026. */
    fun day(day: String): String {
        val d = LocalDate.parse(day)
        return "${d.dayOfMonth} ${MONTH_ABBR[d.monthValue - 1]} ${d.year}"
    }

    /** 2.0 -> 2, 1.5 -> 1.5. */
    fun amount(x: Double): String {
        if (x == Math.floor(x) && !x.isInfinite()) return x.toLong().toString()
        return String.format(java.util.Locale.ROOT, "%.2f", x).trimEnd('0')
    }

    private fun squash(s: String) = s.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    fun outcome(status: String, reason: String?): String = when (status) {
        "kept" -> "done"
        "not_done" -> "not done" + (reason?.takeIf { it.isNotBlank() }?.let { ", reason: $it" } ?: "")
        "moved" -> "moved to a new time"
        "let_go" -> "let go"
        else -> "no answer yet"
    }

    fun promise(text: String, status: String, reason: String?): String = "Promise: $text (${outcome(status, reason)})"

    fun letter(title: String, body: String): String = "Letter \"$title\": $body"

    /** [kind] week or month; [facts] the review's facts as text, [narrative] its note. */
    fun review(kind: String, first: String, last: String, facts: String, narrative: String): String {
        val head = if (kind == "month") {
            val d = LocalDate.parse(first)
            "Month review, ${MONTH_FULL[d.monthValue - 1]} ${d.year}"
        } else {
            "Week review, ${day(first)} to ${day(last)}"
        }
        val body = listOf(squash(facts), squash(narrative)).filter { it.isNotEmpty() }.joinToString(" ")
        return head + if (body.isNotEmpty()) ": $body" else ""
    }

    fun chapter(title: String, first: String, last: String, body: String): String =
        "Chapter \"$title\", ${day(first)} to ${day(last)}: $body"

    private val GOAL_STATUS = mapOf("active" to "active", "done" to "finished", "archived" to "set aside")

    fun goal(title: String, status: String, deadline: String?): String =
        "Goal \"$title\" (${GOAL_STATUS[status] ?: status}${deadline?.let { ", deadline ${day(it)}" }.orEmpty()})"

    fun revision(goalTitle: String, day: String, text: String, reason: String): String {
        val r = squash(reason)
        return "Goal \"$goalTitle\" changed on ${day(day)}: $text${if (r.isNotEmpty()) ". Reason: $r" else ""}"
    }

    fun target(
        name: String,
        target: Double,
        minimum: Double,
        unit: String,
        schedule: String,
        start: String,
        end: String?,
        status: String,
    ): String {
        val u = if (unit.isNotEmpty()) " $unit" else ""
        return "Target \"$name\": ${amount(target)}$u (minimum ${amount(minimum)}), $schedule, from ${day(start)}" +
            "${end?.let { " to ${day(it)}" }.orEmpty()}, $status"
    }

    private val LEVEL = mapOf(
        "rest" to "rest day",
        "not" to "marked Not",
        "minimum" to "marked Part (minimum)",
        "target" to "done (target)",
        "beyond" to "done, beyond the target",
    )

    fun checkin(targetName: String, day: String, level: String, amount: Double, unit: String, reason: String?, note: String?): String {
        val parts = mutableListOf(LEVEL[level] ?: level)
        if (level in setOf("minimum", "target", "beyond") || amount > 0) parts += amount(amount) + if (unit.isNotEmpty()) " $unit" else ""
        if (!reason.isNullOrBlank()) parts += "reason: ${reason.trim()}"
        if (!note.isNullOrBlank()) parts += "note: ${squash(note)}"
        return "Check-in \"$targetName\", ${day(day)}: ${parts.joinToString(", ")}"
    }
}
