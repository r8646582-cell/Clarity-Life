package com.clarity.life.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks every built-in prompt against contracts/prompts.json with the Kotlin [PromptContract] the in-app
 * prompt editor uses, so a prompt that would be refused in the app can never ship inside the APK either.
 * tools/check_prompts.py checks the same file in CI; this is the Kotlin half of that gate.
 */
class PromptContractTest {

    private val contract = PromptContract.parse(Vectors.readText("contracts/prompts.json"))

    private fun promptText(name: String) = Vectors.readText("prompts/$name")

    @Test
    fun everyBuiltInPromptSatisfiesTheContract() {
        val problems = mutableListOf<String>()
        for (name in contract.prompts.keys) {
            contract.validate(name, promptText(name)).forEach { problems.add("$name: $it") }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun part1StaysInsideItsTokenBudgetForEveryChatType() {
        val filled = { name: String ->
            promptText(name)
                .replace("{{USER_NAME}}", "this person")
                .replace("{{ALLOWED_CARDS}}", "plan, target_change, backlog, promise, mode, note")
        }
        val problems = contract.part1Problems(
            persona = filled("persona.md"),
            examples = filled("examples.md"),
            cards = filled("cards.md"),
            modeFiles = listOf("mode_untangle.md", "mode_decision.md", "mode_plan.md", "mode_focus.md")
                .associateWith { filled(it) },
        )
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun anOverrideThatBreaksTheContractIsRefusedWithAReason() {
        // What the prompt editor must do when he deletes a rule or adds a placeholder the app cannot fill.
        val broken = promptText("mode_plan.md").replace("Never calculate", "Work it out") + "\n{{SOMETHING_NEW}}\n"
        val problems = contract.validate("mode_plan.md", broken)
        assertTrue(
            "an override missing a required rule must be refused, got $problems",
            problems.any { it.contains("Never calculate") },
        )
        assertTrue(
            "an override with an unknown placeholder must be refused, got $problems",
            problems.any { it.contains("SOMETHING_NEW") },
        )
    }

    @Test
    fun theContractItselfIsTheOneInTheRepo() {
        // A sanity check that the Kotlin reader sees the same contract the Python checker does.
        assertEquals(20, contract.blockNames.size) // LAST_CHAT joined on 14 Oct 2026, MEMORY_UPDATES on 15 Oct
        assertTrue(contract.prompts.containsKey("persona.md"))
        assertTrue(contract.bannedPhrasesForReplies.isNotEmpty())
        assertTrue(contract.part1MaxEstimatedTokens > 0)
    }
}
