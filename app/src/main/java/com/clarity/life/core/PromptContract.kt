package com.clarity.life.core

/**
 * The prompt contract: contracts/prompts.json, read by the app itself.
 *
 * Two users: the in-app prompt editor validates any override against this before it can be saved (so a typo
 * can never silently break the coach), and PromptContractTest checks every built-in prompt against the very
 * same file that tools/check_prompts.py uses.
 *
 * This is the Kotlin side of checks 1 to 5b of tools/check_prompts.py: exact placeholders, required rules
 * present, forbidden old syntax and em dashes absent, size budgets, and the Part 1 token budget.
 */
data class PromptSpec(
    val placeholders: Set<String>,
    val mustContain: List<String>,
    val maxWords: Int,
)

class PromptContract(
    val blockNames: List<String>,
    val forbiddenEverywhere: List<String>,
    val prompts: Map<String, PromptSpec>,
    val bannedPhrasesForReplies: List<String>,
    val part1MaxEstimatedTokens: Int,
) {

    /** Every problem with [text] as the prompt named [name], in plain language. Empty means it is fine. */
    fun validate(name: String, text: String): List<String> {
        val spec = prompts[name] ?: return listOf("$name is not a prompt this app uses")
        val problems = mutableListOf<String>()

        val found = PLACEHOLDER_RE.findAll(text).map { it.groupValues[1] }.toSet()
        val missing = (spec.placeholders - found).sorted()
        val unexpected = (found - spec.placeholders).sorted()
        if (missing.isNotEmpty()) {
            problems.add("missing the placeholder(s) the app fills: " + missing.joinToString(", ") { "{{$it}}" })
        }
        if (unexpected.isNotEmpty()) {
            problems.add("has placeholder(s) the app does not fill: " + unexpected.joinToString(", ") { "{{$it}}" })
        }
        for (phrase in spec.mustContain) {
            if (!text.lowercase().contains(phrase.lowercase())) {
                problems.add("missing required text: \"$phrase\"")
            }
        }
        for (pattern in forbiddenEverywhere) {
            if (Regex(pattern).containsMatchIn(text)) {
                problems.add("contains forbidden text: \"$pattern\"")
            }
        }
        val words = WORD_RE.findAll(text).count()
        if (words > spec.maxWords) {
            problems.add("$words words is over the budget of ${spec.maxWords}")
        }
        return problems
    }

    /**
     * The fixed Part 1 prefix (persona, examples, cards and the chat-type file, with placeholders filled)
     * must stay inside its token budget for every chat type, or the cached prefix grows unnoticed.
     */
    fun part1Problems(persona: String, examples: String, cards: String, modeFiles: Map<String, String>): List<String> {
        val problems = mutableListOf<String>()
        val heads = listOf(persona, examples, cards)
        for ((modeName, modeText) in modeFiles + mapOf("free talk" to "")) {
            val part1 = (heads + modeText).filter { it.isNotEmpty() }.joinToString("\n\n")
            val estimated = ContextBuilder.estimateTokens(part1)
            if (estimated > part1MaxEstimatedTokens) {
                problems.add("$modeName: about $estimated tokens is over the budget of $part1MaxEstimatedTokens")
            }
        }
        return problems
    }

    companion object {
        private val PLACEHOLDER_RE = Regex("""\{\{([A-Z_]+)\}\}""")
        private val WORD_RE = Regex("""\S+""")

        /** Reads contracts/prompts.json (bundled in the APK as assets/contracts/prompts.json). */
        fun parse(json: String): PromptContract {
            val root = StrictJson.parse(json).asObj?.map ?: throw ContextError("prompts.json is not an object")
            val promptsObj = root["prompts"]?.asObj?.map ?: throw ContextError("prompts.json has no prompts")
            val prompts = promptsObj.mapValues { (_, v) ->
                val spec = v.asObj?.map ?: throw ContextError("a prompt spec is not an object")
                PromptSpec(
                    placeholders = (spec["placeholders"]?.asList ?: emptyList()).mapNotNull { it.asStr }.toSet(),
                    mustContain = (spec["must_contain"]?.asList ?: emptyList()).mapNotNull { it.asStr },
                    maxWords = spec["max_words"]?.asNum?.toInt() ?: Int.MAX_VALUE,
                )
            }
            return PromptContract(
                blockNames = (root["block_names"]?.asList ?: emptyList()).mapNotNull { it.asStr },
                forbiddenEverywhere = (root["forbidden_everywhere"]?.asList ?: emptyList()).mapNotNull { it.asStr },
                prompts = prompts,
                bannedPhrasesForReplies = (root["banned_phrases_for_replies"]?.asList ?: emptyList())
                    .mapNotNull { it.asStr },
                part1MaxEstimatedTokens = root["part1_budget"]?.asObj?.map
                    ?.get("max_estimated_tokens")?.asNum?.toInt() ?: Int.MAX_VALUE,
            )
        }
    }
}
