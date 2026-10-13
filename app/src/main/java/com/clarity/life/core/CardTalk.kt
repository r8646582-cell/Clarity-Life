package com.clarity.life.core

/**
 * Whether the coach's words point at a button under the reply ("tap it below") when no button is there.
 * A model sometimes says so and then forgets the card line, or writes it wrongly so it is thrown away; the
 * app then says that plainly instead of leaving the owner looking for a button. Pure, so it is tested on the JVM.
 */
object CardTalk {

    private val POINTS = Regex(
        "\\b(tap|tapping)\\b[^.?!\\n]*\\bbelow\\b|\\bbelow\\b[^.?!\\n]*\\b(tap|tapping)\\b|" +
            "\\b(put|placed) (it|that|this) below\\b|\\b(is|are) below\\b|" +
            // "here is your card", "here's the button", "I've added a card", "I'll attach the plan card"
            "\\bhere(?:'s|’s| is| are)\\b[^.?!\\n]*\\b(card|cards|button|buttons)\\b|" +
            "\\b(add|added|attach|attached|sent|included|made|put)\\b[^.?!\\n]*\\b(card|cards|button|buttons)\\b|" +
            "\\b(card|cards|button|buttons)\\b[^.?!\\n]*\\b(below|attached|added|ready)\\b|" +
            // "tap Set it", "tap Save plan", "tap Switch": the buttons by name
            "\\b(tap|tapping|press|hit)\\b[^.?!\\n]*\\b(set it|save plan|save it|switch|keep it)\\b|" +
            // "Here it is. Tap it and we start": the owner's case of 13 Oct 2026, with no card under it
            "\\btap (it|this|that|here|the button)\\b|\\bhere (it is|you go)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun pointsAtAButton(prose: String): Boolean = POINTS.containsMatchIn(prose)

    /**
     * What the coach is told on its next turn about a card the app threw away: the parser's own reason, with
     * a plain hint for the common ones, so it can correct exactly that instead of trying again blindly.
     */
    fun dropLine(reason: String): String {
        val hint = when {
            reason == "shape" || reason == "stray brackets in prose" ->
                "a card must be one line of the exact form [[suggest: {...}]], alone, after all your words, with nothing after it"
            reason == "review_date is not one of the review options" ->
                "copy review_date exactly from one of the Review options in PLAN_STATE, for example 17 Oct"
            reason == "no review options were offered" ->
                "PLAN_STATE gives no Review options, so a plan card cannot be shown now; say that plainly and do not send one"
            reason.startsWith("not allowed in") -> "this type of card is not allowed in this chat type; offer a switch of conversation with a mode card"
            reason.startsWith("unknown id") -> "copy goal, target and backlog ids exactly from PLAN_STATE, or leave the goal out to start a new one"
            reason.startsWith("too long") ->
                "that text is over its length limit; write it much shorter (a title in a few words, a rationale or reason in one short sentence)"
            reason == "bad schedule" -> "schedule must be daily, weekdays, a day list such as Mon,Wed,Fri, a range such as Sat-Thu, or a count such as 3x/week"
            reason.startsWith("too many") || reason.startsWith("duplicate") -> "send fewer cards: one of each type"
            else -> "check that field against the card rules"
        }
        return "A card in your last reply was thrown away and they saw no button. Reason: $reason. Fix: $hint. " +
            "If you still mean to offer it, send the corrected card as the last line of this reply, and do not say it was there before."
    }

    /** The quiet line to show under a finished reply, or null when nothing is missing. */
    fun missingLine(prose: String, offered: Int, dropped: Int): String? = when {
        offered > 0 -> null
        dropped > 0 -> "The coach tried to show a button here but wrote it wrongly, so nothing is shown. You can ask for it again."
        pointsAtAButton(prose) -> "The coach spoke of a button below but did not send one. You can ask for it again."
        else -> null
    }
}
