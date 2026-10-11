package com.clarity.life.prompts

import android.content.Context
import com.clarity.life.core.PromptContract
import com.clarity.life.data.dao.PromptOverrideDao
import com.clarity.life.data.entity.PromptOverrideRow
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The coach's mind, read from the APK's assets (the very files in prompts/, copied in at build time) with
 * his own edits layered on top. Prompt text is never written inline in Kotlin (CLAUDE.md, Prompts).
 *
 * An override is only stored after it passes [PromptContract], the same contract tools/check_prompts.py
 * enforces in CI, so a typo cannot silently break the coach. Reset to built-in always works, because the
 * built-in copy is in the APK.
 */
@Singleton
class PromptStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val overrides: PromptOverrideDao,
) {

    val contract: PromptContract by lazy {
        PromptContract.parse(asset("contracts/prompts.json"))
    }

    /** The built-in copy, exactly as it is in prompts/ in the repository. */
    fun builtIn(name: String): String = asset("prompts/$name")

    /**
     * What the app actually sends: his version if he saved one, otherwise the built-in. His version is checked
     * again against this app's contract each time: after an update that adds a rule or a placeholder (or a
     * restore of an old override) it would quietly leave the new data out, so the built-in is used instead.
     */
    suspend fun text(name: String): String {
        val mine = overrides.get(name)?.text ?: return builtIn(name)
        return if (contract.validate(name, mine).isEmpty()) mine else builtIn(name)
    }

    suspend fun isOverridden(name: String): Boolean = overrides.get(name) != null

    /**
     * Saves an edited prompt, or refuses it with the reasons in plain language. Nothing is stored unless
     * the contract is satisfied.
     */
    suspend fun saveOverride(name: String, text: String, now: Long): List<String> {
        val problems = contract.validate(name, text)
        if (problems.isNotEmpty()) return problems
        // The fixed start of every conversation must stay inside its budget with this change in it (CI checks
        // the built-in files the same way).
        if (name in PART1) {
            val texts = (PART1 - name).associateWith { this.text(it) } + (name to text)
            val budget = contract.part1Problems(
                texts.getValue(PERSONA), texts.getValue(EXAMPLES), texts.getValue(CARDS), MODE_FILES.associateWith { texts.getValue(it) },
            )
            if (budget.isNotEmpty()) return budget
        }
        overrides.put(PromptOverrideRow(name, text, now))
        return emptyList()
    }

    suspend fun resetToBuiltIn(name: String) = overrides.reset(name)

    /** Fills the placeholders the app is responsible for. Any placeholder left over is a bug, not content. */
    fun fill(text: String, values: Map<String, String>): String {
        var out = text
        for ((key, value) in values) out = out.replace("{{$key}}", value)
        return out
    }

    private fun asset(path: String): String =
        context.assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) }

    companion object {
        const val PERSONA = "persona.md"
        const val EXAMPLES = "examples.md"
        const val CARDS = "cards.md"
        const val REFLECTION = "reflection.md"
        const val TESTBENCH = "testbench.md"

        private val MODE_FILES = listOf("mode_untangle.md", "mode_decision.md", "mode_plan.md", "mode_focus.md")
        private val PART1 = setOf(PERSONA, EXAMPLES, CARDS) + MODE_FILES

        /** The chat-type file for each chat type. Free talk adds nothing (contracts/context.md, Part 1). */
        fun modeFile(chatType: String): String? = when (chatType) {
            "untangle" -> "mode_untangle.md"
            "decide" -> "mode_decision.md"
            "plan" -> "mode_plan.md"
            "focus" -> "mode_focus.md"
            else -> null
        }
    }
}
