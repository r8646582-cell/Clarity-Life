package com.clarity.life.core

import com.clarity.life.core.Vectors.boolOr
import com.clarity.life.core.Vectors.int
import com.clarity.life.core.Vectors.str
import com.clarity.life.core.Vectors.strOrNull
import com.clarity.life.core.Vectors.strings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs contracts/card_vectors.json through the Kotlin [CardParser]: the same 60 cases
 * tools/test_cards.py runs through the Python reference parser.
 */
class CardParserTest {

    @Test
    fun everyVectorPasses() {
        val file = Vectors.load("contracts/card_vectors.json")
        val defaultKnown = known(file.getValue("known_ids").jsonObject)
        val vectors = file.getValue("vectors").jsonArray.map { it.jsonObject }
        val failures = mutableListOf<String>()
        for (v in vectors) {
            val name = v.str("name")
            val known = v["known_ids"]?.let { known(it.jsonObject) } ?: defaultKnown
            val result = try {
                CardParser.parseReply(v.str("reply"), v.str("chat_type"), known)
            } catch (e: Exception) {
                failures.add("$name: the parser threw ${Vectors.exception(e)}")
                continue
            }
            val expectCards = v.strings("expect_cards")
            if (result.cards.map { it.type } != expectCards) {
                failures.add("$name: cards ${result.cards.map { it.type }}, expected $expectCards")
            }
            val expectDropped = v.int("expect_dropped")
            if (result.dropped.size != expectDropped) {
                failures.add("$name: ${result.dropped.size} dropped (${result.dropped}), expected $expectDropped")
            }
            val expectNone = v.boolOr("expect_said_none", false)
            if (result.saidNoCard != expectNone) failures.add("$name: saidNoCard ${result.saidNoCard}, expected $expectNone")
            v.strOrNull("prose_has")?.let {
                if (!result.prose.contains(it)) failures.add("$name: the prose is missing \"$it\"")
            }
            v.strOrNull("prose_not")?.let {
                if (result.prose.contains(it)) failures.add("$name: the prose still contains \"$it\"")
            }
        }
        assertTrue(
            "${failures.size} of ${vectors.size} card vectors failed:\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    private fun known(obj: JsonObject) = KnownIds(
        goals = obj.strings("goal").toSet(),
        targets = obj.strings("target").toSet(),
        backlogs = obj.strings("backlog").toSet(),
        focuses = obj.strings("focus").toSet(),
        reviewOptions = obj.strings("review_options").toSet(),
    )
}
