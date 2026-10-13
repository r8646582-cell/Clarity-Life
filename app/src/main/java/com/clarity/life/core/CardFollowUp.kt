package com.clarity.life.core

/**
 * One more try for a card that did not arrive. A long reply sometimes ends "tap below" and forgets the card
 * line, or writes it so it is thrown away. Then the app asks the coach once, with prompts/card_followup.md,
 * for only the card line, and reads the answer with the same strict [CardParser]. Nothing is saved: a card
 * that arrives this way is an offer like any other and still needs his tap. Pure, so it is tested on the JVM.
 */
object CardFollowUp {

    /**
     * Whether to ask: no card survived, and the reply either pointed at a button or carried a card line that
     * was thrown away. A reply cut off by the length limit is not asked again; it is shown as cut off.
     */
    fun needed(parsed: ParsedReply, finishReason: String?): Boolean =
        parsed.cards.isEmpty() &&
            finishReason != "length" &&
            (parsed.dropped.isNotEmpty() || CardTalk.pointsAtAButton(parsed.prose))

    /** What fills {{REASON}}: rule names only, never content. */
    fun reason(dropped: List<String>): String =
        if (dropped.isEmpty()) {
            "no card line was in it"
        } else {
            "its card line was thrown away (" + dropped.distinct().take(3).joinToString("; ") + ")"
        }

    /**
     * The reply as the app stores it: its prose, then the cards from [answer] as card lines rebuilt from the
     * validated cards. Words in the answer are never shown. Null when the answer holds no valid card (NONE,
     * a broken line, a type this chat does not allow), and the reply then stays as it was.
     */
    fun merged(prose: String, answer: String, chatType: String, known: KnownIds): String? {
        val got = CardParser.parseReply(answer.trim(), chatType, known)
        if (got.cards.isEmpty()) return null
        return prose + "\n\n" + got.cards.joinToString("\n") { "[[suggest: ${CardParser.toJson(it)}]]" }
    }
}
