package com.clarity.life.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardFollowUpTest {

    private val mode = """[[suggest: {"type":"mode","to":"plan"}]]"""

    @Test
    fun askedOnlyWhenAButtonWasPromisedAndNoneArrived() {
        // The owner's case: Free talk, "tap below and we start", no card line at all.
        val forgot = CardParser.parseReply("Do you want to build it with me? If yes, tap below and we start.", "free")
        assertTrue(CardFollowUp.needed(forgot, "stop"))
        // A card line written wrongly is asked again too.
        val broken = CardParser.parseReply("Tap Switch if that fits.\n`$mode`", "free")
        assertTrue(CardFollowUp.needed(broken, "stop"))
        // A card arrived: nothing to ask.
        assertFalse(CardFollowUp.needed(CardParser.parseReply("Tap Switch if that fits.\n$mode", "free"), "stop"))
        // No button promised: a reply with no card is normal.
        assertFalse(CardFollowUp.needed(CardParser.parseReply("How was today?", "free"), "stop"))
        // A reply cut off by the length limit is shown as cut off, not asked again.
        assertFalse(CardFollowUp.needed(forgot, "length"))
    }

    @Test
    fun theReasonNamesRulesNotContent() {
        assertEquals("no card line was in it", CardFollowUp.reason(emptyList()))
        assertEquals(
            "its card line was thrown away (shape; bad mode)",
            CardFollowUp.reason(listOf("shape", "bad mode", "shape")),
        )
    }

    @Test
    fun onlyAValidCardFromTheAnswerIsKept() {
        val prose = "If yes, tap below and we start."
        val merged = CardFollowUp.merged(prose, "Sure, here it is:\n$mode", "free", KnownIds())
        val parsed = CardParser.parseReply(merged!!, "free")
        assertEquals(prose, parsed.prose) // the answer's own words are never shown
        assertEquals(listOf("mode"), parsed.cards.map { it.type })
        assertTrue(parsed.dropped.isEmpty())

        assertNull(CardFollowUp.merged(prose, "NONE", "free", KnownIds()))
        assertNull(CardFollowUp.merged(prose, "```\n$mode\n```", "free", KnownIds()))
        // The chat type's allowlist still holds: no plan card in Untangle.
        assertNull(CardFollowUp.merged(prose, """[[suggest: {"type":"promise","text":"x","when":"today 17:00"}]]""", "untangle", KnownIds()))
    }
}
