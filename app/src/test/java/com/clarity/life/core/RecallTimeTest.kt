package com.clarity.life.core

import com.clarity.life.core.Vectors.str
import java.time.LocalDate
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs every case in contracts/recall_time_vectors.json, the same file tools/test_recall_time.py reads. */
class RecallTimeTest {

    @Test
    fun everyVector() {
        val failures = mutableListOf<String>()
        val cases = Vectors.cases("contracts/recall_time_vectors.json")
        for (c in cases) {
            val args = c.getValue("args").jsonObject
            val p = RecallTime.parse(args.str("text"), LocalDate.parse(args.str("today")))
            val got = Vectors.json(
                mapOf(
                    "start" to p.start?.toString(),
                    "end" to p.end?.toString(),
                    "promises" to p.promises,
                    "outcome" to p.outcome,
                    "rest" to p.rest,
                ),
            )
            if (!Vectors.same(got, c.getValue("expect"))) failures.add("${c.str("name")}: got $got")
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
        assertTrue("too few vectors", cases.size >= 60)
    }
}
