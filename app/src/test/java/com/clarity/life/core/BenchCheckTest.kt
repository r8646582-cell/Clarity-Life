package com.clarity.life.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs every case of contracts/bench_vectors.json through [TestBench], exactly as tools/test_bench.py runs
 * them through tools/bench.py, and checks that every scenario in prompts/testbench.md parses and uses only
 * checks the bench knows.
 */
class BenchCheckTest {

    private val banned = PromptContract.parse(Vectors.readText("contracts/prompts.json")).bannedPhrasesForReplies

    private fun scenario(j: Jv): TestBench.Scenario {
        val m = j.asObj!!.map
        fun strs(k: String) = m[k]?.asList.orEmpty().map { it.asStr!! }
        fun pairs(k: String) = m[k]?.asList.orEmpty().map { p -> p.asList!!.let { it[0].asStr!! to it[1].asStr!! } }
        return TestBench.Scenario(
            name = m["name"]!!.asStr!!,
            type = m["type"]?.asStr,
            flags = strs("flags"),
            setup = pairs("setup"),
            prior = pairs("prior"),
            user = strs("user"),
            expect = m["expect"]?.asStr.orEmpty(),
            checks = strs("checks"),
        )
    }

    @Test
    fun everyVectorCountsTheSameAsThePythonBench() {
        val root = StrictJson.parse(Vectors.readText("contracts/bench_vectors.json")).asObj!!.map
        val scenarios = root["scenarios"]!!.asObj!!.map.mapValues { scenario(it.value) }
        val fails = mutableListOf<String>()
        var n = 0
        for (c in root["cases"]!!.asList!!) {
            val m = c.asObj!!.map
            val s = scenarios.getValue(m["scenario"]!!.asStr!!)
            val got = try {
                TestBench.runCheck(m["check"]!!.asStr!!, m["reply"]!!.asStr!!, s, banned).toString()
            } catch (e: Exception) {
                "EXC ${e.message}"
            }
            val want = m["expect"]!!.asBool!!.toString()
            if (got != want) fails.add("${m["name"]!!.asStr}: got $got, expect $want")
            n++
        }
        assertTrue(fails.joinToString("\n"), fails.isEmpty())
        assertEquals(25, n)
    }

    @Test
    fun theRealBenchParsesAndEveryCheckIsKnown() {
        val bench = TestBench.parse(Vectors.readText("prompts/testbench.md"))
        assertTrue(bench.size >= 60)
        val bad = mutableListOf<String>()
        for (s in bench) {
            for (check in s.checks) {
                try {
                    TestBench.runCheck(check, "Okay.", s, banned)
                } catch (e: TestBench.UnknownCheck) {
                    bad.add("${s.name}: ${e.message}")
                }
            }
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun theScenariosForTheCoachsNewBehaviourAreThere() {
        // The planner and its syllabus scenario were removed on 13 Nov 2026; these three guard the new voice.
        val names = TestBench.parse(Vectors.readText("prompts/testbench.md")).map { it.name }
        assertTrue(names.contains("Understand first, then a small test"))
        assertTrue(names.contains("Only wondering, no task wanted"))
        assertTrue(names.contains("Specific to them, not generic"))
    }

    @Test
    fun theScenariosFromThePhoneTestOf13NovAreThere() {
        // Real replies on his phone showed invented times, a missing explanation, a catch-style callback and a note
        // with advice in it; each problem has a scenario so the bench catches it on the real model next time.
        val names = TestBench.parse(Vectors.readText("prompts/testbench.md")).map { it.name }
        assertTrue(names.contains("Teach first, then one small test"))
        assertTrue(names.contains("No invented time, no invented experience"))
        assertTrue(names.contains("Practical question, answer first"))
        assertTrue(names.contains("Swing between extremes"))
        assertTrue(names.contains("Told only that it is wrong"))
        assertTrue(names.contains("A note is the plain fact"))
    }
}
