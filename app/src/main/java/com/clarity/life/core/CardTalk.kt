package com.clarity.life.core

/**
 * Whether the coach's words point at a button under the reply ("tap it below") when no button is there.
 * A model sometimes says so and then forgets the card line, or writes it wrongly so it is thrown away; the
 * app then says that plainly instead of leaving the owner looking for a button. Pure, so it is tested on the JVM.
 */
object CardTalk {

    private val POINTS = Regex(
        "\\b(tap|tapping)\\b[^.?!\\n]*\\bbelow\\b|\\bbelow\\b[^.?!\\n]*\\b(tap|tapping)\\b|" +
            "\\b(put|placed) (it|that|this) below\\b|\\b(is|are) below\\b",
        RegexOption.IGNORE_CASE,
    )

    fun pointsAtAButton(prose: String): Boolean = POINTS.containsMatchIn(prose)

    /** The quiet line to show under a finished reply, or null when nothing is missing. */
    fun missingLine(prose: String, offered: Int, dropped: Int): String? = when {
        offered > 0 -> null
        dropped > 0 -> "The coach tried to show a button here but wrote it wrongly, so nothing is shown. You can ask for it again."
        pointsAtAButton(prose) -> "The coach spoke of a button below but did not send one. You can ask for it again."
        else -> null
    }
}
