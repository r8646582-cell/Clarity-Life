package com.clarity.life.core

import com.clarity.life.core.Vectors.bool
import com.clarity.life.core.Vectors.json
import com.clarity.life.core.Vectors.str
import com.clarity.life.core.Vectors.strings
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs contracts/applier_vectors.json through [ReflectionApplier], [GardeningApplier] and [TextJobCheck]:
 * the same cases tools/test_appliers.py runs through the Python reference.
 *
 * As in the Python harness, "expect" may list only some keys, and "dropped_count" checks only how many
 * drop reasons there were (the wording of a reason is not part of the contract).
 */
class ApplierTest {

    @Test
    fun everyVectorPasses() {
        val data = Vectors.load("contracts/applier_vectors.json")
        val fixtures = data.getValue("fixtures").jsonObject
        val cases = data.getValue("cases").jsonArray.map { it.jsonObject }
        val failures = mutableListOf<String>()
        for (case in cases) {
            val name = case.str("name")
            val args = case.getValue("args").jsonObject
            val got: JsonObject = try {
                call(case.str("fn"), args, fixtures)
            } catch (e: Exception) {
                failures.add("$name: ${Vectors.exception(e)}")
                continue
            }
            val problems = mutableListOf<String>()
            for ((k, v) in case.getValue("expect").jsonObject) {
                if (k == "dropped_count") {
                    val n = got["dropped"]?.jsonArray?.size ?: 0
                    if (n != v.jsonPrimitive.int) problems.add("dropped $n != $v ${got["dropped"]}")
                } else if (got[k] == null || !Vectors.same(got.getValue(k), v)) {
                    problems.add("$k: got ${got[k]}, expected $v")
                }
            }
            if (problems.isNotEmpty()) failures.add("$name | " + problems.joinToString(" ; "))
        }
        assertTrue(
            "${failures.size} of ${cases.size} applier vectors failed:\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    @Test
    fun vectorCountMatchesThePythonReference() {
        // A guard against the file failing to load silently: the contract has 62 cases today.
        assertEquals(62, Vectors.cases("contracts/applier_vectors.json").size)
    }

    private fun call(fn: String, args: JsonObject, fixtures: JsonObject): JsonObject {
        fun ctx(): JsonObject {
            val c = args.getValue("ctx")
            return if (c is JsonPrimitive) fixtures.getValue(c.content).jsonObject else c.jsonObject
        }
        return when (fn) {
            "apply_reflection" -> result(ReflectionApplier.apply(args.str("raw"), reflectionContext(ctx())))
            "apply_gardening" -> result(GardeningApplier.apply(args.str("raw"), gardeningContext(ctx())))
            "check_text_job" -> {
                val r = TextJobCheck.check(
                    args.str("kind"), args.str("text"), args.strings("inputs"), args.strings("his_messages"),
                )
                obj("ok" to r.ok, "problems" to r.problems, "title" to r.title, "body" to r.body)
            }
            "apply_monthly_review" -> {
                val r = TextJobCheck.monthly(
                    args.str("raw"), args.strings("known_ids"), args.strings("inputs"), args.strings("his_messages"),
                )
                obj("ok" to r.ok, "problems" to r.problems)
            }
            "apply_story" -> {
                val quotes = args.getValue("quotes").jsonArray.associate {
                    val q = it.jsonObject
                    q.getValue("id").jsonPrimitive.content.toLong() to q.str("day")
                }
                val r = TextJobCheck.story(args.str("raw"), quotes, args.strings("inputs"), args.strings("his_messages"))
                obj(
                    "ok" to r.ok,
                    "problems" to r.problems,
                    "short" to r.short,
                    "then_quote" to r.thenQuote,
                    "now_quote" to r.nowQuote,
                    "overcame" to r.overcame.map {
                        mapOf("title" to it.title, "then_quote" to it.thenQuote, "did" to it.did, "now" to it.now)
                    },
                    "dropped" to r.dropped,
                )
            }
            else -> error("the vectors call $fn, which this test does not know")
        }
    }

    private fun notes(o: JsonObject?): Map<Long, NoteFacts> =
        (o ?: JsonObject(emptyMap())).entries.associate { (k, v) ->
            val n = v.jsonObject
            k.toLong() to NoteFacts(
                kind = n.str("kind"),
                confidence = n.str("confidence"),
                status = n.str("status"),
                edited = n.bool("edited"),
                text = n["text"]?.jsonPrimitive?.content ?: "",
                timesSeen = n["times_seen"]?.jsonPrimitive?.int ?: 1,
            )
        }

    private fun profile(o: JsonObject?): Map<String, Boolean> =
        (o ?: JsonObject(emptyMap())).entries.associate { (k, v) -> k to v.jsonObject.bool("confirmed") }

    private fun reflectionContext(c: JsonObject) = ReflectionContext(
        hisMessages = c.strings("his_messages"),
        hisEarlierQuotes = c.strings("his_earlier_quotes"),
        checkinFacts = c["checkin_facts"]?.jsonPrimitive?.content ?: "",
        notes = notes(c["notes"]?.jsonObject),
        deletedNotes = c.strings("deleted_notes"),
        people = c.strings("people"),
        profile = profile(c["profile"]?.jsonObject),
        inFocus = c["in_focus"]?.jsonPrimitive?.content == "true",
    )

    private fun gardeningContext(c: JsonObject) = GardeningContext(
        notes = notes(c["notes"]?.jsonObject),
        people = c.strings("people").map { it.toLong() }.toSet(),
        profile = profile(c["profile"]?.jsonObject),
    )

    private fun obj(vararg pairs: Pair<String, Any?>): JsonObject = json(mapOf(*pairs)).jsonObject

    private fun result(r: ApplyResult): JsonObject =
        obj("ok" to r.ok, "ops" to r.ops.map { op(it) }, "dropped" to r.dropped)

    private fun ev(list: List<Evidence>): List<List<String>> = list.map { listOf(it.kind, it.text) }

    /** Each operation rendered as the list the Python reference returns. */
    private fun op(o: JobOp): JsonElement = json(
        when (o) {
            is JobOp.ConversationSet -> listOf("conversation.set", o.title, o.significance, o.tone)
            is JobOp.ConversationSummary -> listOf("conversation.summary", o.summary)
            is JobOp.ProfileSet -> listOf("profile.set", o.key, o.value, o.verified)
            is JobOp.PersonAdd -> listOf("person.add", o.name, o.relation, o.notes)
            is JobOp.PersonUpdate -> listOf("person.update", o.name, o.relation, o.notes)
            is JobOp.EventAdd ->
                listOf("event.add", o.whenText, o.situation, o.feelingBefore, o.action, o.payoff, o.outcome)
            is JobOp.NoteAdd -> listOf("note.add", o.kind, o.text, o.confidence, ev(o.evidence))
            is JobOp.NoteUpdate -> listOf("note.update", o.id, o.text, o.confidence, o.seenAgain, ev(o.evidence))
            is JobOp.NoteRetire -> listOf("note.retire", o.id)
            is JobOp.QuoteAdd -> listOf("quote.add", o.text)
            is JobOp.DisagreementAdd -> listOf("disagreement.add", o.claim, o.hisPosition)
            is JobOp.ContradictionAdd -> listOf("contradiction.add", o.quoteA, o.quoteB)
            is JobOp.IdeaAdd -> listOf("idea.add", o.tag)
            is JobOp.FocusSummary -> listOf("focus.summary", o.summary)
            is JobOp.NoteMerge -> listOf("note.merge", o.ids, o.text, o.confidence, o.timesSeen)
            is JobOp.NoteRewrite -> listOf("note.rewrite", o.id, o.text)
            is JobOp.PersonMerge -> listOf("person.merge", o.ids, o.name, o.relation, o.notes)
        },
    )
}
