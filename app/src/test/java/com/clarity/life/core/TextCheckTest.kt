package com.clarity.life.core

import com.clarity.life.core.Vectors.json
import com.clarity.life.core.Vectors.str
import com.clarity.life.core.Vectors.strings
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs contracts/textcheck_vectors.json through the Kotlin [TextCheck]: the same 50 cases
 * tools/test_textcheck.py runs through the Python reference.
 */
class TextCheckTest {

    @Test
    fun everyVectorPasses() {
        val cases = Vectors.cases("contracts/textcheck_vectors.json")
        val failures = mutableListOf<String>()
        for (case in cases) {
            val name = case.str("name")
            val fn = case.str("fn")
            val args = case.getValue("args").jsonObject
            val expected = case.getValue("expect")
            val got = try {
                call(fn, args)
            } catch (e: Exception) {
                json(Vectors.exception(e))
            }
            if (!Vectors.same(got, expected)) {
                failures.add("$fn: $name\n   got      $got\n   expected $expected")
            }
        }
        assertTrue(
            "${failures.size} of ${cases.size} text-check vectors failed:\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    private fun call(fn: String, args: JsonObject): JsonElement = when (fn) {
        "quote_is_his" -> json(TextCheck.quoteIsHis(args.str("quote"), args.strings("user_messages")))

        "verified_quotes" -> json(TextCheck.verifiedQuotes(args.strings("quotes"), args.strings("user_messages")))

        "verify_quotes_in_text" -> result(TextCheck.verifyQuotesInText(args.str("text"), args.strings("user_messages")))

        "number_check" -> result(TextCheck.numberCheck(args.str("text"), args.strings("inputs")))

        else -> error("the vectors call $fn, which this test does not know")
    }

    private fun result(r: TextCheck.CheckResult): JsonElement = json(mapOf("ok" to r.ok, "bad" to r.bad))
}
