package com.clarity.life.core

/**
 * The plain statements a v2 letter is built from (docs/CLARITY-V2-BUILD-SPEC.md section 9, "Letter facts from
 * code"). Everything here is counted by code from the database; the model is only given these lines and never
 * invents a count. With no check-ins in v2, the facts are the days they talked, the working-on events they made
 * or said, the progress they volunteered, and the notes first seen or seen again.
 */
object LetterFacts {
    /** One working-on event, in the owner's words where they gave them. [day] is already written out ("3 November"). */
    data class Event(val kind: String, val item: String, val day: String, val progress: String? = null)

    data class Input(
        val totalDays: Int,
        val chatDays: Int,
        val messages: Int,
        val events: List<Event>,
        val newNotes: Int,
        val seenAgain: Int,
    )

    fun lines(i: Input): String {
        val out = mutableListOf<String>()
        if (i.chatDays > 0) {
            out.add("You talked to me on ${i.chatDays} of the ${i.totalDays} days.")
            out.add("You wrote ${i.messages} message${if (i.messages == 1) "" else "s"}.")
        } else {
            out.add("You did not talk to me in this period.")
        }
        for (e in i.events) {
            out.add(
                when (e.kind) {
                    "started" -> "You started: ${e.item}."
                    "finished" -> "You finished: ${e.item} (on ${e.day})."
                    "paused" -> "You paused: ${e.item}."
                    "dropped" -> "You dropped: ${e.item}."
                    "resumed" -> "You are back on: ${e.item}."
                    "progress" -> "You said you ${e.progress.orEmpty().trim().trimEnd('.')} on ${e.item} (on ${e.day})."
                    else -> continue
                },
            )
        }
        if (i.newNotes > 0) out.add("${i.newNotes} new thing${if (i.newNotes == 1) "" else "s"} came up about how you work.")
        if (i.seenAgain > 0) out.add("${i.seenAgain} earlier thing${if (i.seenAgain == 1) "" else "s"} came up again.")
        return out.joinToString("\n")
    }
}
