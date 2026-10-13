package com.clarity.life.core

import java.time.Duration
import java.time.Instant

/**
 * Everything the app writes about a promise, in code (rule 2): the OPEN_PROMISES lines the coach reads, the
 * kicker above a promise that needs an answer, and which promises those are. Pure, so PromiseTextTest
 * pins it with fixed times, including after midnight.
 */
object PromiseText {

    /** A promise as these functions need it. [status] is one of contracts/states.json "promise". */
    data class Promise(
        val id: Long,
        val text: String,
        val dueAt: Instant,
        val status: String,
        val reason: String? = null,
        val remind: Boolean = false,
    )

    /** The reason chips after "Not done" (spec section 2.2); the same list the check-ins use in stage D. */
    val REASONS = listOf(
        "tired", "no time", "forgot", "avoided it", "felt too big", "sick", "plans changed", "low mood", "other",
    )

    /** How long an answered promise stays in OPEN_PROMISES, so the coach can follow up on it. */
    const val RECENT_DAYS = 14L

    /** At most this many answered promises in OPEN_PROMISES (open ones are never cut). */
    const val RECENT_MAX = 8

    /**
     * One open promise: `P4 "Study 1 hour at 8am", due Monday 5 October, 8:00 am (in 1 day 7 hours).`
     * An overdue one says it has no answer yet, which is never the same as not done (rule 3).
     */
    fun openLine(p: Promise, now: Instant, tz: String): String {
        val due = TimeFacts.duePhrase(p.dueAt, now, tz)
        val tail = if (p.dueAt.isAfter(now)) "" else ", no answer yet"
        return "P${p.id} \"${p.text}\", $due$tail."
    }

    /** One answered promise: `P3 "Walk 20 minutes", was due Thursday 5:00 pm, 2 days ago: not done, reason: tired.` */
    fun outcomeLine(p: Promise, now: Instant, tz: String): String =
        "P${p.id} \"${p.text}\", ${TimeFacts.duePhrase(p.dueAt, now, tz)}: ${outcome(p)}."

    fun outcome(p: Promise): String = when (p.status) {
        "kept" -> "done"
        "not_done" -> "not done" + (p.reason?.takeIf { it.isNotBlank() }?.let { ", reason: $it" } ?: "")
        "moved" -> "moved to a new time"
        "let_go" -> "let go"
        else -> "no answer yet"
    }

    /**
     * OPEN_PROMISES (contracts/context.md): every open promise, soonest first, then what he answered in the
     * last [RECENT_DAYS] days, newest first. Never trimmed. Empty (and so omitted) when there is nothing.
     */
    fun block(open: List<Promise>, answered: List<Promise>, now: Instant, tz: String): Block {
        val lines = open.sortedWith(compareBy({ it.dueAt }, { it.id })).map { openLine(it, now, tz) } +
            recent(answered, now).map { outcomeLine(it, now, tz) }
        return Block("OPEN_PROMISES", lines.map { BlockItem(it, 0) })
    }

    /** Answered promises recent enough for OPEN_PROMISES. */
    fun recent(answered: List<Promise>, now: Instant): List<Promise> {
        val since = now.minus(Duration.ofDays(RECENT_DAYS))
        return answered.filter { it.status != "open" && it.dueAt.isAfter(since) }
            .sortedWith(compareByDescending<Promise> { it.dueAt }.thenByDescending { it.id })
            .take(RECENT_MAX)
    }

    /**
     * Which open promises need an answer (Plan, Promises, with a dot on the Plan tab): those due on his day or
     * earlier and not answered yet, oldest first. A promise later today is shown too, so he can mark it done early.
     */
    fun dueNow(open: List<Promise>, now: Instant, tz: String, boundaryHour: Int): List<Promise> {
        val today = Planner.dayKey(now.toString(), tz, boundaryHour)
        return open.filter { it.status == "open" && !Planner.dayKey(it.dueAt.toString(), tz, boundaryHour).isAfter(today) }
            .sortedWith(compareBy({ it.dueAt }, { it.id }))
    }

    /**
     * The small line above a due item: "Due 6:00 pm, in 2 hours", "Was due 6:00 pm, 3 hours ago", or for
     * an older one "Was due Fri 9 Oct, 6:00 pm".
     */
    fun dueKicker(dueAt: Instant, now: Instant, tz: String, boundaryHour: Int): String {
        val clock = TimeFacts.clock(TimeFacts.local(dueAt, tz))
        val gap = Duration.between(now, dueAt)
        if (!gap.isNegative) return "Due $clock, in ${TimeFacts.spell(gap)}"
        val sameDay = Planner.dayKey(dueAt.toString(), tz, boundaryHour) == Planner.dayKey(now.toString(), tz, boundaryHour)
        return if (sameDay) "Was due $clock, ${TimeFacts.spell(gap)} ago" else "Was due ${TimeFacts.cardTime(dueAt, tz)}"
    }

    /** The text the search index keeps for a promise, so an old one can be found again (RELEVANT_PAST). */
    fun searchText(p: Promise): String = "${p.text} (${outcome(p)})"
}
