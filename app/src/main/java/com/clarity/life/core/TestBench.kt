package com.clarity.life.core

/**
 * The test bench (tools/bench.py, ported 1:1): parsing prompts/testbench.md and running its automatic checks on
 * a reply. contracts/bench_vectors.json pins how every check is counted, so a reply passes or fails the same
 * way in Python and on the phone (BenchCheckTest runs every vector and parses the real bench).
 *
 * What each check looks at:
 *  - the reply is first split by the real card parser using the scenario's chat type (a focus scenario uses
 *    the free-talk card list);
 *  - text checks look at the PROSE only (cards removed), compared case-insensitively with curly quotes straight;
 *  - no_card fails on any card line at all, even one the parser threw away (the model still tried);
 *  - number_ok uses NumberCheck against the Setup, Prior and User lines of the scenario;
 *  - card ids and review options a card may use are read from the scenario's Setup lines;
 *  - card=TYPE needs a card the parser accepts; no_card=TYPE and max_cards also count card lines it threw away;
 *  - card_field=TYPE.FIELD=JSON checks one field of every accepted card of that type.
 */
object TestBench {

    data class Scenario(
        val name: String,
        val type: String?,
        val flags: List<String> = emptyList(),
        /** BLOCK to text, in order. */
        val setup: List<Pair<String, String>> = emptyList(),
        /** "user" or "coach" to text, in order. */
        val prior: List<Pair<String, String>> = emptyList(),
        val user: List<String> = emptyList(),
        val expect: String = "",
        val checks: List<String> = emptyList(),
    )

    class UnknownCheck(check: String) : IllegalArgumentException("unknown check $check")

    // ICU (Android) needs ) escaped inside a class.
    private val LIST_RE = Regex("^\\s*([-*•]\\s|\\d+[.\\)]\\s|#+\\s|\\*\\*)", RegexOption.MULTILINE)
    private val TYPE_RE = Regex("\"type\"\\s*:\\s*\"([a-z_]+)\"")
    private val REVIEW_RE = Regex("Review options: (.*?)\\.(?:\\s|$)")
    private val OPTION_RE = Regex("(\\d{1,2} [A-Z][a-z]{2}) \\(")

    fun norm(s: String): String =
        s.replace('’', '\'').replace('‘', '\'').replace('“', '"').replace('”', '"').lowercase()

    /** Scenarios from testbench.md: everything after the first "---" line, one per "## " heading. */
    fun parse(text: String): List<Scenario> {
        val cut = text.indexOf("\n---\n")
        if (cut < 0) return emptyList()
        val body = text.substring(cut + 5)
        val sections = Regex("^## ", RegexOption.MULTILINE).split(body).drop(1)
        return sections.map { sec ->
            val lines = sec.split("\n")
            var type: String? = null
            val flags = mutableListOf<String>()
            val setup = mutableListOf<Pair<String, String>>()
            val prior = mutableListOf<Pair<String, String>>()
            val user = mutableListOf<String>()
            var expect = ""
            val checks = mutableListOf<String>()
            for (l in lines.drop(1)) {
                when {
                    l.startsWith("Type:") -> type = l.substring(5).trim()
                    l.startsWith("Flags:") -> flags += l.substring(6).trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                    l.startsWith("Setup:") -> {
                        val rest = l.substring(6).trim()
                        val colon = rest.indexOf(':')
                        if (colon < 0) setup.add(rest.trim() to "") else setup.add(rest.substring(0, colon).trim() to rest.substring(colon + 1).trim())
                    }
                    l.startsWith("Prior-User:") -> prior.add("user" to l.substring(11).trim())
                    l.startsWith("Prior-Coach:") -> prior.add("coach" to l.substring(12).trim())
                    l.startsWith("User:") -> user.add(l.substring(5).trim())
                    l.startsWith("Expect:") -> expect = l.substring(7).trim()
                    l.startsWith("Check:") -> checks.add(l.substring(6).trim())
                }
            }
            Scenario(lines[0].trim(), type, flags, setup, prior, user, expect, checks)
        }
    }

    /** Ids and review options a bench card may use, read from the scenario's own Setup lines. */
    fun knownFromSetup(s: Scenario): KnownIds {
        val text = s.setup.joinToString(" ") { it.second }
        fun ids(p: String) = Regex("\\b$p\\d+\\b").findAll(text).map { it.value }.toSet()
        val options = REVIEW_RE.find(text)?.groupValues?.get(1)?.let { opts ->
            OPTION_RE.findAll(opts).map { it.groupValues[1] }.toSet()
        } ?: emptySet()
        return KnownIds(goals = ids("G"), targets = ids("T"), backlogs = ids("B"), focuses = ids("F"), reviewOptions = options)
    }

    /** Every Setup, Prior and User line: what number_ok lets the reply use. */
    fun scenarioText(s: Scenario): String =
        (s.setup.map { it.second } + s.prior.map { it.second } + s.user).joinToString("\n")

    private fun cardLineTypes(reply: String): List<String> =
        reply.split("\n").mapNotNull { l -> if ("[[suggest" in l) TYPE_RE.find(l)?.groupValues?.get(1) else null }

    private fun words(text: String): Int = text.split(Regex("\\s+")).count { it.isNotEmpty() }

    /** A card field as Python's json.dumps writes it: false, 2, "text", null. */
    private fun json(card: Card, field: String): String = when (val v = card.fields[field]) {
        null, is Jv.Null -> "null"
        is Jv.Str -> StrictJson.write(v)
        is Jv.Bool, is Jv.Num -> card.fieldAsText(field) ?: "null"
        else -> StrictJson.write(v)
    }

    /** True when the reply passes one Check line. [bannedPhrases] come from contracts/prompts.json. */
    fun runCheck(check: String, reply: String, s: Scenario, bannedPhrases: List<String>): Boolean {
        val chatType = if (s.type == "focus") "free" else s.type ?: "free"
        val parsed = CardParser.parseReply(reply, chatType, knownFromSetup(s))
        val prose = parsed.prose
        val tried = cardLineTypes(reply)
        val valid = parsed.cards.map { it.type }
        val np = norm(prose)
        val eq = check.indexOf('=')
        val name = if (eq < 0) check else check.substring(0, eq)
        val arg = if (eq < 0) "" else check.substring(eq + 1)
        return when {
            check == "no_card" -> tried.isEmpty() && parsed.cards.isEmpty()
            name == "card" -> arg in valid
            name == "no_card" -> arg !in valid && arg !in tried
            name == "max_cards" -> tried.size <= arg.toInt()
            name == "max_questions" -> prose.count { it == '?' } <= arg.toInt()
            name == "max_words" -> words(prose) <= arg.toInt()
            name == "min_words" -> words(prose) >= arg.toInt()
            name == "contains" -> norm(arg) in np
            name == "contains_any" -> arg.split("|").any { norm(it) in np }
            name == "not_contains" -> norm(arg) !in np
            check == "no_list" -> !LIST_RE.containsMatchIn(prose)
            check == "no_em_dash" -> '—' !in prose && '–' !in prose
            check == "no_banned" -> bannedPhrases.none { norm(it) in np }
            check == "number_ok" -> TextCheck.numberCheck(prose, listOf(scenarioText(s))).ok
            name == "card_field" -> {
                val e = arg.indexOf('=')
                val path = if (e < 0) arg else arg.substring(0, e)
                val want = if (e < 0) "" else arg.substring(e + 1)
                val dot = path.indexOf('.')
                val type = if (dot < 0) path else path.substring(0, dot)
                val field = if (dot < 0) "" else path.substring(dot + 1)
                val hits = parsed.cards.filter { it.type == type }
                hits.isNotEmpty() && hits.all { json(it, field) == want }
            }
            else -> throw UnknownCheck(check)
        }
    }
}
