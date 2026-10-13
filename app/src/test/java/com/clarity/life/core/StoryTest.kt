package com.clarity.life.core

import com.clarity.life.core.Vectors.dbl
import com.clarity.life.core.Vectors.str
import com.clarity.life.core.Vectors.strOrNull
import java.time.LocalDate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs every case in contracts/story_vectors.json, the same file tools/test_story.py reads. */
class StoryTest {

    private fun day(o: JsonObject, key: String) = LocalDate.parse(o.str(key))

    private fun amounts(args: JsonObject) = args.getValue("checkins").jsonArray.map {
        val c = it.jsonObject
        Story.Amount(day(c, "day"), c.dbl("amount"))
    }

    private fun steps(args: JsonObject) = args.getValue("steps").jsonArray.map {
        val s = it.jsonObject
        PlanStep(s.dbl("target"), s.str("schedule"), day(s, "start"), s.strOrNull("end")?.let(LocalDate::parse))
    }

    @Test
    fun everyVector() {
        val failures = mutableListOf<String>()
        val cases = Vectors.cases("contracts/story_vectors.json")
        for (c in cases) {
            val a = c.getValue("args").jsonObject
            val got = try {
                when (c.str("fn")) {
                    "series" -> {
                        val rest = a.getValue("rest_dates").jsonArray.map { LocalDate.parse(it.jsonPrimitive.content) }.toSet()
                        val s = Story.series(day(a, "first"), day(a, "last"), steps(a), amounts(a), rest)
                        Vectors.json(
                            mapOf(
                                "by" to s.by,
                                "bars" to s.bars.map {
                                    mapOf(
                                        "start" to it.start.toString(),
                                        "end" to it.end.toString(),
                                        "done" to it.done,
                                        "planned" to it.planned,
                                    )
                                },
                            ),
                        )
                    }
                    "finished_on" -> Vectors.json(Story.finishedOn(a.dbl("total"), a.dbl("start_done"), amounts(a))?.toString())
                    "length" -> Vectors.json(Story.length(day(a, "first"), day(a, "today")))
                    "headline" -> Vectors.json(Story.headline(day(a, "first"), day(a, "today")))
                    "view_range" -> {
                        val (from, to) = Story.viewRange(Story.view(a.str("view")), day(a, "first"), day(a, "today"))
                        Vectors.json(mapOf("from" to from.toString(), "to" to to.toString()))
                    }
                    else -> error("unknown fn")
                }
            } catch (e: PlannerError) {
                null
            }
            val wantError = c["error"] != null
            val ok = if (wantError) got == null else got != null && Vectors.same(got, c.getValue("expect"))
            if (!ok) failures.add("${c.str("name")}: got $got")
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
        assertTrue("too few vectors", cases.size >= 30)
    }
}
