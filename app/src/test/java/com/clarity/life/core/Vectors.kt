package com.clarity.life.core

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Shared plumbing for the contract tests. Each test reads the very same file in contracts/ that the Python
 * reference tests read (CLAUDE.md, The contracts), so the app and the spec cannot drift apart.
 *
 * The comparison follows tools/test_planner.py: numbers match within 1e-9, an exception is rendered as
 * "EXC ClassName: message", and a result object must have exactly the keys the vector expects.
 */
object Vectors {

    private val repoRoot: File by lazy {
        val fromProperty = System.getProperty("clarity.repoRoot")
        if (fromProperty != null) return@lazy File(fromProperty)
        // Fall back to walking up from the working directory, so the tests also run from an IDE.
        var dir: File? = File(".").absoluteFile
        while (dir != null && !File(dir, "contracts/card_vectors.json").exists()) dir = dir.parentFile
        requireNotNull(dir) { "cannot find the repo root; set -Dclarity.repoRoot" }
    }

    fun repoFile(relative: String): File = File(repoRoot, relative).also {
        require(it.exists()) { "missing ${it.path}" }
    }

    fun readText(relative: String): String = repoFile(relative).readText()

    fun load(relative: String): JsonObject =
        Json.parseToJsonElement(readText(relative)).jsonObject

    fun cases(relative: String, key: String = "cases"): List<JsonObject> =
        load(relative).getValue(key).jsonArray.map { it.jsonObject }

    /** How the Python harness renders a raised error, so a vector can expect a rejection. */
    fun exception(e: Throwable): String = "EXC ${e::class.simpleName}: ${e.message}"

    fun same(actual: JsonElement, expected: JsonElement): Boolean {
        if (actual is JsonObject && expected is JsonObject) {
            return actual.keys == expected.keys && actual.all { (k, v) -> same(v, expected.getValue(k)) }
        }
        if (actual is JsonArray && expected is JsonArray) {
            return actual.size == expected.size && actual.indices.all { same(actual[it], expected[it]) }
        }
        if (actual is JsonPrimitive && expected is JsonPrimitive) {
            val a = actual.numberOrNull()
            val b = expected.numberOrNull()
            if (a != null && b != null) return kotlin.math.abs(a - b) < 1e-9
            return actual == expected
        }
        return actual == expected
    }

    private fun JsonPrimitive.numberOrNull(): Double? {
        if (this is JsonNull || isString) return null
        if (booleanOrNull != null) return null
        return content.toDoubleOrNull()
    }

    // ---------- small helpers for reading a vector's args ----------

    fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content
    fun JsonObject.strOrNull(key: String): String? =
        this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content

    fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int
    fun JsonObject.intOr(key: String, fallback: Int): Int = this[key]?.jsonPrimitive?.int ?: fallback
    fun JsonObject.dbl(key: String): Double = getValue(key).jsonPrimitive.double
    fun JsonObject.dblOrNull(key: String): Double? =
        this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.double

    fun JsonObject.bool(key: String): Boolean = getValue(key).jsonPrimitive.boolean
    fun JsonObject.boolOr(key: String, fallback: Boolean): Boolean =
        this[key]?.jsonPrimitive?.booleanOrNull ?: fallback

    fun JsonObject.strings(key: String): List<String> =
        (this[key]?.takeIf { it !is JsonNull }?.jsonArray ?: JsonArray(emptyList())).map { it.jsonPrimitive.content }

    // ---------- building JSON out of the Kotlin results ----------

    fun json(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Double -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to json(v) })
        is Iterable<*> -> JsonArray(value.map { json(it) })
        is Pair<*, *> -> JsonArray(listOf(json(value.first), json(value.second)))
        else -> error("cannot turn $value into JSON")
    }

    /** An object with the null-valued keys left out, the way the Python references omit absent dict keys. */
    fun jsonNoNulls(pairs: List<Pair<String, Any?>>): JsonElement =
        JsonObject(pairs.filter { it.second != null }.associate { (k, v) -> k to json(v) })
}
