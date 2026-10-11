package com.clarity.life.core

import com.clarity.life.core.Vectors.str
import java.time.LocalDate
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs every case in contracts/plan_date_vectors.json, the same file tools/test_plan_dates.py reads. */
class PlanDatesTest {

    @Test
    fun everyVector() {
        val failures = mutableListOf<String>()
        val cases = Vectors.cases("contracts/plan_date_vectors.json")
        for (c in cases) {
            val args = c.getValue("args").jsonObject
            val today = LocalDate.parse(args.str("today"))
            val got = when (c.str("fn")) {
                "read_day" -> Vectors.json(PlanDates.readDay(args.str("phrase"), today)?.toString())
                "check_card" -> {
                    val card = StrictJson.parse(args.getValue("card").toString()).asObj!!.map
                    val type = card.getValue("type").asStr!!
                    when (val r = PlanDates.checkCard(type, card, today)) {
                        is PlanDates.Checked.Bad -> Vectors.json(mapOf("ok" to false, "why" to r.why))
                        is PlanDates.Checked.Ok -> Vectors.json(
                            when (type) {
                                "plan" -> mapOf(
                                    "ok" to true,
                                    "targets" to r.days.targets.map {
                                        mapOf("start" to it.first.toString(), "end" to it.second?.toString())
                                    },
                                    "backlog" to r.days.deadlines.map { mapOf("deadline" to it.toString()) },
                                    "review" to r.days.review.toString(),
                                )
                                "target_change" -> mapOf("ok" to true, "end" to r.days.end?.toString())
                                else -> mapOf("ok" to true, "deadline" to r.days.deadline?.toString())
                            },
                        )
                    }
                }
                else -> error("unknown fn")
            }
            if (!Vectors.same(got, c.getValue("expect"))) failures.add("${c.str("name")}: got $got")
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
        assertTrue("too few vectors", cases.size >= 40)
    }
}
