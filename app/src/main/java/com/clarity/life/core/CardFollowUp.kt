package com.clarity.life.core

/**
 * The card step (contracts/context.md): after every finished reply that carries no valid card, the app asks
 * the coach once, with prompts/card_followup.md and the provider's JSON mode, for the cards that reply offered,
 * as `{"cards": [...]}`. A long reply often ends "tap below" and forgets its card line; the button then no
 * longer depends on that line. Every card is read by the same strict [CardParser] with the same ids and
 * allowlist, and an empty list is the normal answer. Nothing is saved: a card that arrives this way is an
 * offer like any other and still needs his tap. Pure, so it is tested on the JVM.
 */
object CardFollowUp {

    /**
     * Follow-up is needed when a finished reply carries no valid card, EXCEPT when the coach explicitly
     * signaled no card ([[cards: none]]) and the prose does not point at a button (C2).
     */
    fun needed(parsed: ParsedReply, finishReason: String?): Boolean {
        if (parsed.cards.isNotEmpty() || finishReason == "length" || parsed.prose.isBlank()) return false
        if (parsed.noCardSignal && !CardTalk.pointsAtAButton(parsed.prose)) return false
        return true
    }

    /** What fills {{REASON}}: rule names only, never content. */
    fun reason(dropped: List<String>): String =
        if (dropped.isEmpty()) {
            "your last reply has no card line"
        } else {
            "the card line in your last reply was thrown away (" + dropped.distinct().take(3).joinToString("; ") + ")"
        }

    /**
     * The cards in the step's [answer] as card lines, ready for [CardParser.parseReply]. The answer should be
     * `{"cards": [...]}` (JSON mode); a provider without JSON mode may answer with card lines instead, which
     * are passed on as they are. Anything else gives no lines.
     */
    fun cardLines(answer: String): String {
        val text = answer.trim()
        val root = try {
            StrictJson.parse(text)
        } catch (e: Exception) {
            return text // not JSON: card lines, NONE, or words; the parser keeps only valid card lines
        }
        val items = root.asObj?.map?.get("cards")?.asList ?: return ""
        return items.joinToString("\n") { "[[suggest: ${StrictJson.write(it)}]]" }
    }

    /**
     * The reply as the app stores it: its prose, then the cards from [answer] as card lines rebuilt from the
     * validated cards. Words in the answer are never shown. Null when the answer holds no valid card (an empty
     * list, a broken card, a type this chat does not allow), and the reply then stays as it was.
     */
    fun merged(prose: String, answer: String, chatType: String, known: KnownIds): String? {
        val got = CardParser.parseReply(cardLines(answer), chatType, known)
        if (got.cards.isEmpty()) return null
        return prose + "\n\n" + got.cards.joinToString("\n") { "[[suggest: ${CardParser.toJson(it)}]]" }
    }
}
