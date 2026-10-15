package com.clarity.life.core

/**
 * The FOCUS block (contracts/context.md): when a conversation belongs to a focus, the coach gets its id, name,
 * why, rolling summary, the current step and the last three step results. Never trimmed. Pure, so the exact
 * lines are tested on the JVM.
 *
 * A "step" is a promise he set from a conversation inside the focus: the open one due soonest is the current
 * step, and the last three he answered are the results. Code writes the outcomes; the coach never infers them.
 */
object FocusBlock {

    data class Focus(val id: Long, val name: String, val why: String, val status: String, val summary: String)

    data class Step(val text: String, val dueText: String, val status: String, val reason: String?)

    const val MAX_RESULTS = 3

    /**
     * The focus's week, from the day key it was made to today's day key: days 1 to 7 are week 1. Day keys, not
     * the phone's calendar date, so it agrees with the rest of the app around a late day end and after travel.
     */
    fun week(startDay: java.time.LocalDate, today: java.time.LocalDate): Long =
        (java.time.temporal.ChronoUnit.DAYS.between(startDay, today) / 7 + 1).coerceAtLeast(1)

    fun block(focus: Focus, current: Step?, results: List<Step>): Block {
        val items = buildList {
            val status = if (focus.status == "active") "" else " (${focus.status})"
            add("F${focus.id} \"${clean(focus.name)}\"$status. Why: ${clean(focus.why)}")
            add(
                "Summary: " + clean(focus.summary).ifBlank {
                    "none yet, this is the first conversation in this focus"
                },
            )
            add(
                if (current == null) {
                    "Current step: none set"
                } else {
                    "Current step: ${clean(current.text)}, due ${clean(current.dueText)}"
                },
            )
            val last = results.take(MAX_RESULTS)
            add(
                if (last.isEmpty()) {
                    "Last steps: none answered yet"
                } else {
                    "Last steps: " + last.joinToString("; ") { "${clean(it.text)}: ${outcome(it)}" }
                },
            )
        }
        return Block("FOCUS", items.map { BlockItem(it, 0) })
    }

    /** The answer he gave, in the same words OPEN_PROMISES uses. */
    fun outcome(step: Step): String = when (step.status) {
        "kept" -> "done"
        "not_done" -> "not done" + (step.reason?.takeIf { it.isNotBlank() }?.let { " (reason: $it)" } ?: "")
        "moved" -> "moved"
        "let_go" -> "let go"
        else -> "no answer yet"
    }

    /** One line each: a newline inside his words would start a fake line of the block. */
    private fun clean(text: String): String = text.replace(Regex("\\s+"), " ").trim()
}
