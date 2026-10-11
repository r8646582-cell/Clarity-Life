package com.clarity.life.data

import com.clarity.life.core.Vectors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The state machines in the app must be the ones in contracts/states.json, and an illegal move must throw.
 * tools/check_schema.py checks that same file against db/schema.sql, so all three agree.
 */
class StateMachineTest {

    private val contract by lazy {
        Json.parseToJsonElement(Vectors.readText("contracts/states.json")).jsonObject
            .getValue("machines").jsonObject
    }

    @Test
    fun theMachinesAreExactlyTheOnesInTheContract() {
        assertEquals(
            "the set of state machines differs from contracts/states.json",
            contract.keys.sorted(),
            StateMachines.ALL.map { it.name }.sorted(),
        )
        val problems = mutableListOf<String>()
        for ((name, element) in contract) {
            val spec = element.jsonObject
            val machine = StateMachines.byName(name)
            fun strings(key: String) = spec.getValue(key).jsonArray.map { it.jsonPrimitive.content }
            if (machine.table != spec.getValue("table").jsonPrimitive.content) problems.add("$name: table")
            if (machine.column != spec.getValue("column").jsonPrimitive.content) problems.add("$name: column")
            if (machine.states != strings("states")) {
                problems.add("$name: states ${machine.states}, contract ${strings("states")}")
            }
            if (machine.start != strings("start")) {
                problems.add("$name: start ${machine.start}, contract ${strings("start")}")
            }
            val transitions = spec.getValue("transitions").jsonObject
                .mapValues { (_, v) -> v.jsonArray.map { it.jsonPrimitive.content } }
            if (machine.transitions != transitions) {
                problems.add("$name: transitions ${machine.transitions}, contract $transitions")
            }
            val repair = spec.getValue("repair_on_start").jsonObject
                .mapValues { (_, v) -> v.jsonPrimitive.content }
            if (machine.repairOnStart != repair) {
                problems.add("$name: repair ${machine.repairOnStart}, contract $repair")
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun everyAllowedMoveIsAllowedAndEveryOtherMoveThrows() {
        for (machine in StateMachines.ALL) {
            for (from in machine.states) {
                for (to in machine.states) {
                    val allowed = machine.allows(from, to)
                    try {
                        StateMachines.move(machine, from, to)
                        if (!allowed) fail("${machine.name}: $from to $to should have been refused")
                    } catch (e: StateMachines.IllegalTransition) {
                        if (allowed) fail("${machine.name}: $from to $to should have been allowed (${e.message})")
                    }
                }
            }
        }
    }

    @Test
    fun aMadeUpStateIsRefused() {
        try {
            StateMachines.move(StateMachines.MESSAGE, "complete", "sending")
            fail("a state the schema does not have must be refused")
        } catch (e: StateMachines.IllegalTransition) {
            assertTrue(e.message!!.contains("sending"))
        }
    }

    @Test
    fun hisDeletionsAreFinal() {
        // Rule 5: a note he deleted is never recreated, so there is no move out of deleted_by_user.
        assertEquals(emptyList<String>(), StateMachines.NOTE.transitions.getValue("deleted_by_user"))
    }

    @Test
    fun theRepairSweepOnlyMakesMovesTheContractAllows() {
        for (machine in StateMachines.ALL) {
            for ((from, to) in machine.repairOnStart) {
                assertTrue(
                    "${machine.name}: the repair $from to $to is not an allowed transition",
                    machine.allows(from, to),
                )
            }
        }
    }

    @Test
    fun aNewRowCanOnlyStartInAStartState() {
        StateMachines.requireStart(StateMachines.MESSAGE, "streaming")
        StateMachines.requireStart(StateMachines.CARD, "offered")
        try {
            StateMachines.requireStart(StateMachines.CARD, "saved")
            fail("a card cannot be born already saved: that would be a hidden action")
        } catch (e: StateMachines.IllegalTransition) {
            assertTrue(e.message!!.contains("saved"))
        }
    }
}
