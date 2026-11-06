package com.clarity.life.core

import com.clarity.life.core.Vectors.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs every case in contracts/working_vectors.json, the same file tools/test_working.py reads. */
class WorkingRulesTest {

    private fun JsonObject.s(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun JsonObject.i(key: String): Int = (getValue(key) as JsonPrimitive).intOrNull!!
    private fun JsonObject.b(key: String): Boolean = (getValue(key) as JsonPrimitive).booleanOrNull!!

    private fun item(o: JsonObject) = WorkingRules.Item(
        name = o.s("name")!!,
        status = o.s("status")!!,
        confirmed = o.i("confirmed") == 1,
        deadlineState = o.s("deadline_state")!!,
        deadlineDate = o.s("deadline_date"),
        sizeText = o.s("size_text"),
        originQuote = o.s("origin_quote")!!,
        originDay = o.s("origin_day")!!,
        lastMentionedDay = o.s("last_mentioned_day")!!,
    )

    private fun got(fn: String, a: JsonObject): Any? = when (fn) {
        "explicit_date" -> WorkingRules.explicitDate(a.s("quote"), a.s("today")!!)
        "propose" -> WorkingRules.propose(a.i("active_count"), a.s("deadline_quote"), a.s("today")!!).let {
            mapOf(
                "status" to it.status, "confirmed" to it.confirmed, "deadline_state" to it.deadlineState,
                "deadline_date" to it.deadlineDate, "ask_tradeoff" to it.askTradeoff,
            )
        }
        "change_allowed" -> WorkingRules.changeAllowed(
            a.s("change")!!, a.i("evidence_days"), a.b("tapped"), a.b("venting"), a.i("active_count"),
        )
        "is_stale" -> WorkingRules.isStale(a.s("last_mentioned_day")!!, a.s("today")!!, a.b("asked_stale"))
        "context_line" -> WorkingRules.contextLine(item(a.getValue("item").jsonObject), a.s("today")!!)
        "top_line" -> WorkingRules.topLine(a.s("day")!!, a.i("active_items"))
        else -> error("the vectors call $fn, which this test does not know")
    }

    @Test
    fun everyVector() {
        val failures = mutableListOf<String>()
        val cases = Vectors.cases("contracts/working_vectors.json")
        for (c in cases) {
            val result = got(c.str("fn"), c.getValue("args").jsonObject)
            val actual = Vectors.json(result)
            if (!Vectors.same(actual, c.getValue("expect"))) failures.add("${c.str("name")}: got $actual")
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
        assertTrue("too few vectors", cases.size >= 50)
    }
}
