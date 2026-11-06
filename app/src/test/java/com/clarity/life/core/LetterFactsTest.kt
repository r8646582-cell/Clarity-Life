package com.clarity.life.core

import org.junit.Assert.assertEquals
import org.junit.Test

class LetterFactsTest {
    @Test
    fun aBusyWeekIsCountedInPlainStatements() {
        val text = LetterFacts.lines(
            LetterFacts.Input(
                totalDays = 7, chatDays = 5, messages = 41,
                events = listOf(
                    LetterFacts.Event("started", "Accounting exam", "1 November"),
                    LetterFacts.Event("progress", "Accounting exam", "3 November", "finished 12 lectures"),
                    LetterFacts.Event("paused", "Blog", "4 November"),
                ),
                newNotes = 2, seenAgain = 1,
            ),
        )
        assertEquals(
            "You talked to me on 5 of the 7 days.\n" +
                "You wrote 41 messages.\n" +
                "You started: Accounting exam.\n" +
                "You said you finished 12 lectures on Accounting exam (on 3 November).\n" +
                "You paused: Blog.\n" +
                "2 new things came up about how you work.\n" +
                "1 earlier thing came up again.",
            text,
        )
    }

    @Test
    fun aQuietPeriodSaysSoAndInventsNothing() {
        assertEquals(
            "You did not talk to me in this period.",
            LetterFacts.lines(LetterFacts.Input(7, 0, 0, emptyList(), 0, 0)),
        )
    }

    @Test
    fun oneMessageIsNotPlural() {
        assertEquals(
            "You talked to me on 1 of the 7 days.\nYou wrote 1 message.",
            LetterFacts.lines(LetterFacts.Input(7, 1, 1, emptyList(), 0, 0)),
        )
    }
}
