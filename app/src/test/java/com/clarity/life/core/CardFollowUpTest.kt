package com.clarity.life.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardFollowUpTest {

    private val mode = """[[suggest: {"type":"mode","to":"plan"}]]"""
    private val ownerReply = "Do you want to build it properly with me? If yes, tap below and we start."

    @Test
    fun theStepRunsAfterEveryReplyWithNoValidCard() {
        // The owner's case: Free talk, "tap below and we start", no card line at all.
        assertTrue(CardFollowUp.needed(CardParser.parseReply(ownerReply, "free"), "stop"))
        // Every reply without a card goes through it, not only those the app recognises as pointing at a button.
        assertTrue(CardFollowUp.needed(CardParser.parseReply("Shall we turn this into a plan?", "free"), "stop"))
        // A card line written wrongly too.
        assertTrue(CardFollowUp.needed(CardParser.parseReply("Tap Switch if that fits.\n`$mode`", "free"), "stop"))
        // A valid card arrived with the reply: nothing to do.
        assertFalse(CardFollowUp.needed(CardParser.parseReply("Tap Switch if that fits.\n$mode", "free"), "stop"))
        // A reply cut off by the length limit is shown as cut off.
        assertFalse(CardFollowUp.needed(CardParser.parseReply(ownerReply, "free"), "length"))
        // No card signal: skips the follow-up when prose does not point at a button (C2).
        assertFalse(CardFollowUp.needed(CardParser.parseReply("That sounds good.\n\n[[cards: none]]", "free"), "stop"))
        // But if the prose points at a button, follow-up is still run even with [[cards: none]] (C2).
        assertTrue(CardFollowUp.needed(CardParser.parseReply("Tap below to start.\n\n[[cards: none]]", "free"), "stop"))
    }

    @Test
    fun theReasonNamesRulesNotContent() {
        assertEquals("your last reply has no card line", CardFollowUp.reason(emptyList()))
        assertEquals(
            "the card line in your last reply was thrown away (shape; bad mode)",
            CardFollowUp.reason(listOf("shape", "bad mode", "shape")),
        )
    }

    @Test
    fun aJsonAnswerBecomesTheButton() {
        val merged = CardFollowUp.merged(ownerReply, """{"cards": [{"type":"mode","to":"plan"}]}""", "free", KnownIds())
        val parsed = CardParser.parseReply(merged!!, "free")
        assertEquals(ownerReply, parsed.prose) // the step's answer is never shown as words
        assertEquals(listOf("mode"), parsed.cards.map { it.type })
        assertEquals("plan", parsed.cards.single().str("to"))
        assertTrue(parsed.dropped.isEmpty())
    }

    @Test
    fun anEmptyListOrABadCardShowsNothing() {
        assertNull(CardFollowUp.merged("How was today?", """{"cards": []}""", "free", KnownIds()))
        assertNull(CardFollowUp.merged(ownerReply, """{"cards": [{"type":"mode","to":"nowhere"}]}""", "free", KnownIds()))
        assertNull(CardFollowUp.merged(ownerReply, """{"something": "else"}""", "free", KnownIds()))
        // The chat type's allowlist still holds: no promise card in Untangle.
        assertNull(
            CardFollowUp.merged(
                ownerReply,
                """{"cards": [{"type":"promise","text":"x","when":"today 17:00"}]}""",
                "untangle",
                KnownIds(),
            ),
        )
        // A bracket pair smuggled into a text is refused by the parser, never shown.
        assertNull(
            CardFollowUp.merged(ownerReply, """{"cards": [{"type":"focus","name":"a ]] b","why":"c"}]}""", "free", KnownIds()),
        )
    }

    @Test
    fun aProviderWithoutJsonModeMayAnswerWithCardLines() {
        val merged = CardFollowUp.merged(ownerReply, "Sure:\n$mode", "free", KnownIds())
        assertEquals(listOf("mode"), CardParser.parseReply(merged!!, "free").cards.map { it.type })
        assertNull(CardFollowUp.merged(ownerReply, "NONE", "free", KnownIds()))
    }
}
