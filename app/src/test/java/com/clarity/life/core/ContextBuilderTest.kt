package com.clarity.life.core

import com.clarity.life.core.Vectors.int
import com.clarity.life.core.Vectors.intOr
import com.clarity.life.core.Vectors.json
import com.clarity.life.core.Vectors.str
import com.clarity.life.core.Vectors.strOrNull
import com.clarity.life.core.Vectors.strings
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs contracts/context_vectors.json through the Kotlin [ContextBuilder]: the same cases
 * tools/test_context.py runs through the Python reference. Covers ordering, escaping, trimming and the
 * history window.
 */
class ContextBuilderTest {

    @Test
    fun everyVectorPasses() {
        val cases = Vectors.cases("contracts/context_vectors.json")
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
            "${failures.size} of ${cases.size} context vectors failed:\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    private fun blocks(args: JsonObject, key: String): List<Block> =
        args.getValue(key).jsonArray.map { it.jsonObject }.map { b ->
            Block(
                name = b.str("name"),
                items = b.getValue("items").jsonArray.map { it.jsonObject }
                    .map { BlockItem(it.str("text"), it.int("drop")) },
            )
        }

    private fun blocksJson(list: List<Block>): JsonElement = json(
        list.map { b ->
            mapOf(
                "name" to b.name,
                "items" to b.items.map { mapOf("text" to it.text, "drop" to it.drop) },
            )
        },
    )

    private fun call(fn: String, args: JsonObject): JsonElement = when (fn) {
        "escape" -> json(ContextBuilder.escape(args.str("text")))

        "estimate_tokens" -> json(ContextBuilder.estimateTokens(args.str("text")))

        "trim_blocks" -> {
            val r = ContextBuilder.trimBlocks(blocks(args, "blocks"), args.strings("order"), args.int("budget"))
            json(listOf(blocksJson(r.blocks), json(r.dropped), json(r.overBudget)))
        }

        "build_prefix" -> {
            val p = ContextBuilder.buildPrefix(
                args.str("persona"),
                args.str("examples"),
                args.str("cards"),
                args.strOrNull("mode"),
                blocks(args, "part2_blocks"),
            )
            json(mapOf("system" to p.system, "dropped" to p.dropped, "over_budget" to p.overBudget))
        }

        "build_user_turn" -> {
            val t = ContextBuilder.buildUserTurn(blocks(args, "part3_blocks"), args.str("text"))
            json(mapOf("content" to t.content, "dropped" to t.dropped, "over_budget" to t.overBudget))
        }

        "next_history_start" -> json(
            ContextBuilder.nextHistoryStart(
                args.getValue("token_counts").jsonArray.map { it.jsonPrimitive.content.toInt() },
                args.int("start"),
                args.intOr("budget", ContextBuilder.HISTORY_BUDGET),
                args.intOr("min_keep", ContextBuilder.HISTORY_MIN_KEEP),
            ),
        )

        "history_message" -> {
            val m = ContextBuilder.historyMessage(args.str("role"), args.str("text"), args.strOrNull("stamp"))
            json(mapOf("role" to m.role, "content" to m.content))
        }

        else -> error("the vectors call $fn, which this test does not know")
    }
}
