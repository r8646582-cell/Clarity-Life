package com.clarity.life.core

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LAST_CHAT: the conversation he was just in reaches a new one word for word, with times the app counted.
 * The scene is the one from 14 Oct 2026: a nap planned at 9 am, a new chat at 11:20 am.
 */
class LastChatTest {

    private val tz = "Asia/Karachi"
    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    private val plan = listOf(
        LastChat.Msg("user", "ok so I sleep now and wake up at 11, if the lights are on I do one lecture", at("2026-10-14T04:28:00Z")),
        LastChat.Msg("coach", "Deal. Alarm for 11, and if the power is back, one lecture.", at("2026-10-14T04:29:00Z")),
        LastChat.Msg("user", "ok good night", at("2026-10-14T04:31:00Z")),
    )

    @Test
    fun aNewChatSeesThePlanWithItsTimes() {
        val block = LastChat.block(
            "Power cut", at("2026-10-14T04:02:00Z"), at("2026-10-14T04:31:00Z"), plan,
            now = at("2026-10-14T06:20:00Z"), tz = tz, boundaryHour = 0,
        )
        assertEquals("LAST_CHAT", block.name)
        assertEquals(
            listOf(
                "Your other conversation \"Power cut\", Wed 14 Oct, 9:02 am to 9:31 am (today); its last message was 1 hour 49 minutes ago. Its last messages:",
                "[9:28 am] They: ok so I sleep now and wake up at 11, if the lights are on I do one lecture",
                "Coach: Deal. Alarm for 11, and if the power is back, one lecture.",
                "[9:31 am] They: ok good night",
            ),
            block.items.map { it.text },
        )
        assertEquals("the header is never dropped", 0, block.items[0].drop)
        assertTrue("the oldest message goes first", block.items[1].drop > block.items[3].drop)
        assertTrue("search results go before any of it", Retrieval.DROP_RANK > block.items.maxOf { it.drop })
    }

    @Test
    fun onlyTheLastMessagesAreSentAndLongOnesAreCut() {
        val many = (1..12).map { LastChat.Msg("user", "message $it " + "x".repeat(if (it == 12) 600 else 0), at("2026-10-14T04:00:00Z") + it * 60_000L) }
        val block = LastChat.block("", many.first().at, many.last().at, many, at("2026-10-14T05:00:00Z"), tz, 0)
        assertEquals(1 + LastChat.MESSAGES, block.items.size)
        assertTrue(block.items[0].text.startsWith("Your other conversation, Wed 14 Oct"))
        assertTrue(block.items[1].text.contains("message 5"))
        assertTrue(block.items.last().text.endsWith("..."))
    }

    @Test
    fun acrossMidnightTheDayComesWithTheTime() {
        val late = listOf(LastChat.Msg("user", "going to sleep", at("2026-10-13T18:50:00Z"))) // Tue 11:50 pm
        val block = LastChat.block("", late[0].at, late[0].at, late, at("2026-10-13T20:00:00Z"), tz, 0) // Wed 1:00 am
        assertEquals("[Tue 13 Oct, 11:50 pm] They: going to sleep", block.items[1].text)
        assertTrue(block.items[0].text.contains("(yesterday)"))
    }

    @Test
    fun itIsFreshForSixHours() {
        val last = at("2026-10-14T04:31:00Z")
        assertTrue(LastChat.fresh(last, last + 5 * 3_600_000L))
        assertFalse(LastChat.fresh(last, last + 6 * 3_600_000L))
        assertFalse("a clock set back is not fresh", LastChat.fresh(last, last - 60_000L))
        assertTrue(LastChat.block("", 0, 0, emptyList(), last, tz, 0).items.isEmpty())
    }
}
