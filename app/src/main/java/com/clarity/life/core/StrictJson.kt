package com.clarity.life.core

/**
 * A strict JSON reader for everything the model produces (card lines, reflection and gardening results).
 *
 * Port of `loads_strict` in tools/cards.py. Strict means: duplicate keys are rejected (parsers disagree
 * about which copy wins, so neither does), NaN and Infinity are rejected (they are not JSON), raw control
 * characters inside strings are rejected, and trailing text after the value is rejected. Anything it cannot
 * read with certainty raises [JsonError], and the caller drops the card. It never repairs by guessing.
 *
 * Pure Kotlin: no Android, no third-party parser, so it behaves identically on the JVM and on the phone.
 */
class JsonError(message: String) : Exception(message)

sealed class Jv {
    data class Obj(val map: Map<String, Jv>) : Jv()
    data class Arr(val list: List<Jv>) : Jv()
    data class Str(val value: String) : Jv()
    data class Num(val value: Double, val isInteger: Boolean) : Jv()
    data class Bool(val value: Boolean) : Jv()
    object Null : Jv()

    val asObj: Obj? get() = this as? Obj
    val asStr: String? get() = (this as? Str)?.value
    val asNum: Double? get() = (this as? Num)?.value
    val asBool: Boolean? get() = (this as? Bool)?.value
    val asList: List<Jv>? get() = (this as? Arr)?.list
}

object StrictJson {

    fun parse(text: String): Jv {
        val r = Reader(text)
        r.skipWhitespace()
        val v = r.value()
        r.skipWhitespace()
        if (!r.atEnd()) throw JsonError("trailing text after the JSON value")
        return v
    }

    private class Reader(private val s: String) {
        private var i = 0

        fun atEnd() = i >= s.length

        fun skipWhitespace() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
        }

        private fun fail(what: String): Nothing = throw JsonError("$what at position $i")

        private fun expect(c: Char) {
            if (i >= s.length || s[i] != c) fail("expected '$c'")
            i++
        }

        private fun literal(word: String) {
            if (!s.startsWith(word, i)) fail("not a JSON value")
            i += word.length
        }

        fun value(): Jv {
            if (atEnd()) fail("empty JSON")
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> Jv.Str(string())
                't' -> { literal("true"); Jv.Bool(true) }
                'f' -> { literal("false"); Jv.Bool(false) }
                'n' -> { literal("null"); Jv.Null }
                // NaN, Infinity and -Infinity are the Python/JS extensions this project refuses.
                'N' -> throw JsonError("not a JSON number NaN")
                'I' -> throw JsonError("not a JSON number Infinity")
                else -> number()
            }
        }

        private fun obj(): Jv.Obj {
            expect('{')
            val map = LinkedHashMap<String, Jv>()
            skipWhitespace()
            if (i < s.length && s[i] == '}') { i++; return Jv.Obj(map) }
            while (true) {
                skipWhitespace()
                if (i >= s.length || s[i] != '"') fail("object key must be a string")
                val key = string()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                val v = value()
                if (map.containsKey(key)) throw JsonError("duplicate key")
                map[key] = v
                skipWhitespace()
                if (i >= s.length) fail("unterminated object")
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return Jv.Obj(map) }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun arr(): Jv.Arr {
            expect('[')
            val list = ArrayList<Jv>()
            skipWhitespace()
            if (i < s.length && s[i] == ']') { i++; return Jv.Arr(list) }
            while (true) {
                skipWhitespace()
                list.add(value())
                skipWhitespace()
                if (i >= s.length) fail("unterminated array")
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return Jv.Arr(list) }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun string(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail("unterminated string")
                val c = s[i]
                when {
                    c == '"' -> { i++; return sb.toString() }
                    c == '\\' -> {
                        i++
                        if (i >= s.length) fail("unterminated escape")
                        when (val e = s[i]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 >= s.length) fail("short \\u escape")
                                val hex = s.substring(i + 1, i + 5)
                                val code = hex.toIntOrNull(16) ?: fail("bad \\u escape")
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> fail("bad escape '\\$e'")
                        }
                        i++
                    }
                    // A raw line break or other control character inside a string is not JSON.
                    c.code < 0x20 -> fail("control character in string")
                    else -> { sb.append(c); i++ }
                }
            }
        }

        private fun number(): Jv.Num {
            val start = i
            if (i < s.length && s[i] == '-') i++
            if (i < s.length && s[i] == 'I') throw JsonError("not a JSON number -Infinity")
            if (i >= s.length || !s[i].isDigit()) fail("not a JSON number")
            if (s[i] == '0') i++ else while (i < s.length && s[i].isDigit()) i++
            var isInt = true
            if (i < s.length && s[i] == '.') {
                isInt = false
                i++
                if (i >= s.length || !s[i].isDigit()) fail("digit expected after '.'")
                while (i < s.length && s[i].isDigit()) i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                isInt = false
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                if (i >= s.length || !s[i].isDigit()) fail("digit expected in exponent")
                while (i < s.length && s[i].isDigit()) i++
            }
            val raw = s.substring(start, i)
            val d = raw.toDoubleOrNull() ?: fail("not a JSON number")
            if (!d.isFinite()) throw JsonError("not a JSON number $raw")
            return Jv.Num(d, isInt)
        }
    }

    /** Writes a value back as JSON, for storing a validated card in card.payload_json. */
    fun write(value: Jv): String = when (value) {
        is Jv.Obj -> value.map.entries.joinToString(",", "{", "}") { (k, v) -> "${quote(k)}:${write(v)}" }
        is Jv.Arr -> value.list.joinToString(",", "[", "]") { write(it) }
        is Jv.Str -> quote(value.value)
        is Jv.Num -> if (value.isInteger) value.value.toLong().toString() else value.value.toString()
        is Jv.Bool -> value.value.toString()
        Jv.Null -> "null"
    }

    private fun quote(text: String): String {
        val sb = StringBuilder("\"")
        for (c in text) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c.code < 0x20 -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }
}
