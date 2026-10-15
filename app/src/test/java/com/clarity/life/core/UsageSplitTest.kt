package com.clarity.life.core

import org.junit.Assert.assertEquals
import org.junit.Test

class UsageSplitTest {

    @Test
    fun eachKindGetsItsCallsCacheShareAndShareOfAllInput() {
        val lines = UsageSplit.lines(
            listOf(
                UsageSplit.Kind("chat", 40, 300_000, 9_000, 270_000),
                UsageSplit.Kind("card_step", 1, 100_000, 50, 0),
            ),
        )
        assertEquals(
            listOf(
                "  Chat replies" to "40 calls, 300,000 in (90% cached), 9,000 out, 75% of all input",
                "  Card follow-up" to "1 call, 100,000 in (0% cached), 50 out, 25% of all input",
            ),
            lines,
        )
    }

    @Test
    fun aSingleKindHasNoShareAndAKindWithNoCallsIsLeftOut() {
        val lines = UsageSplit.lines(listOf(UsageSplit.Kind("letter", 1, 0, 800, 0), UsageSplit.Kind("bench", 0, 0, 0, 0)))
        assertEquals(listOf("  Letters" to "1 call, 0 in, 800 out"), lines)
    }

    @Test
    fun everyKindTheSchemaAllowsHasAName() {
        val schema = Vectors.readText("db/schema.sql")
        val list = Regex("kind IN \\(([^)]*)\\)").findAll(schema).map { it.groupValues[1] }
            .first { "card_step" in it }.split(",").map { it.trim().trim('\'') }
        assertEquals(list.toSet(), UsageSplit.NAMES.keys)
    }
}
