package com.clarity.life.core

import com.clarity.life.core.Vectors.bool
import com.clarity.life.core.Vectors.dbl
import com.clarity.life.core.Vectors.int
import com.clarity.life.core.Vectors.str
import com.clarity.life.core.Vectors.strOrNull
import com.clarity.life.core.Vectors.strings
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs every case in contracts/retrieval_vectors.json through [Retrieval] and [SearchText]: the same file
 * tools/test_retrieval.py runs through tools/retrieval.py, so the app and the measured reference cannot drift.
 */
class RetrievalVectorsTest {

    @Test
    fun everyVector() {
        val failures = mutableListOf<String>()
        val cases = Vectors.cases("contracts/retrieval_vectors.json")
        for (c in cases) {
            val a = c.getValue("args").jsonObject
            val got = try {
                Vectors.json(call(c.str("fn"), a))
            } catch (e: Exception) {
                Vectors.json(Vectors.exception(e))
            }
            if (!Vectors.same(got, c.getValue("expect"))) failures.add("${c.str("fn")}: ${c.str("name")}: got $got")
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
        assertTrue("too few vectors", cases.size >= 60)
    }

    private fun JsonObject.maxOr(fallback: Int): Int = this["max_results"]?.jsonPrimitive?.int ?: fallback

    private fun call(fn: String, a: JsonObject): Any? = when (fn) {
        "terms" -> Retrieval.terms(a.str("text"))
        "stem" -> Retrieval.stem(a.str("word"))
        "term_query" -> Retrieval.termQuery(a.str("term"))
        "score" -> Retrieval.score(a.str("text"), a.strings("ts"))
        "strong" -> Retrieval.strongEnough(a.int("s"), a.int("n"))
        "rare_terms" -> Retrieval.rareTerms(
            a.strings("ts"),
            a.getValue("found").jsonObject.mapValues { it.value.jsonPrimitive.int },
            a.int("total"),
        ).sorted()
        "choose" -> {
            val cands = a.getValue("candidates").jsonArray.map {
                val o = it.jsonObject
                Retrieval.Candidate(o.str("text"), o.getValue("at").jsonPrimitive.long, o.str("key"))
            }
            Retrieval.choose(cands, a.strings("ts"), a.maxOr(Retrieval.FUSE_DEPTH), a.strings("rare").toSet())
                .map { it.candidate.label }
        }
        "distinct_texts" -> Retrieval.distinctTexts(
            a.strings("keys"),
            a.getValue("texts").jsonObject.mapValues { it.value.jsonPrimitive.content },
        )
        "conversation_query" -> Retrieval.conversationQuery(a.str("new"), a.strings("earlier"), a.strOrNull("title") ?: "")
        "fuse" -> Retrieval.fuse(
            a.getValue("lists").jsonArray.map { l ->
                val pair = l.jsonArray
                pair[0].jsonPrimitive.double to pair[1].jsonArray.map { it.jsonPrimitive.content }
            },
        )
        "near_duplicate" -> Retrieval.nearDuplicate(a.str("a"), a.str("b"))
        "choose_final" -> Retrieval.chooseFinal(
            a.strings("keys"),
            a.getValue("docs").jsonObject.mapValues { (_, v) ->
                val o = v.jsonObject
                val conv = o["conversation"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.long
                Retrieval.Doc(o.str("text"), conv)
            },
            a.maxOr(Retrieval.MAX_RESULTS),
        )
        "dated" -> Retrieval.dated(
            a.getValue("items").jsonArray.map {
                val o = it.jsonObject
                Retrieval.DatedItem(o.str("key"), o.str("kind"), o.str("text"), o.str("day"), o.strOrNull("outcome"))
            },
            a.strings("rest_terms"),
            a.bool("promises"),
            a.strOrNull("outcome"),
            a.maxOr(Retrieval.DATED_MAX),
        )
        "day_text" -> SearchText.day(a.str("day"))
        "amount_text" -> SearchText.amount(a.dbl("x"))
        "promise_text" -> SearchText.promise(a.str("text"), a.str("status"), a.strOrNull("reason"))
        "letter_text" -> SearchText.letter(a.str("title"), a.str("body"))
        "review_text" -> SearchText.review(a.str("kind"), a.str("first"), a.str("last"), a.str("facts"), a.str("narrative"))
        "chapter_text" -> SearchText.chapter(a.str("title"), a.str("first"), a.str("last"), a.str("body"))
        "goal_text" -> SearchText.goal(a.str("title"), a.str("status"), a.strOrNull("deadline"))
        "revision_text" -> SearchText.revision(a.str("goal_title"), a.str("day"), a.str("text"), a.str("reason"))
        "target_text" -> SearchText.target(
            a.str("name"), a.dbl("target"), a.dbl("minimum"), a.str("unit"), a.str("schedule"), a.str("start"),
            a.strOrNull("end"), a.str("status"),
        )
        "checkin_text" -> SearchText.checkin(
            a.str("target_name"), a.str("day"), a.str("level"), a.dbl("amount"), a.str("unit"), a.strOrNull("reason"),
            a.strOrNull("note"),
        )
        else -> error("unknown fn $fn")
    }
}
