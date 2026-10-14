package com.clarity.life.core

import com.clarity.life.core.Vectors.bool
import com.clarity.life.core.Vectors.boolOr
import com.clarity.life.core.Vectors.dbl
import com.clarity.life.core.Vectors.dblOrNull
import com.clarity.life.core.Vectors.int
import com.clarity.life.core.Vectors.intOr
import com.clarity.life.core.Vectors.json
import com.clarity.life.core.Vectors.jsonNoNulls
import com.clarity.life.core.Vectors.str
import com.clarity.life.core.Vectors.strOrNull
import com.clarity.life.core.Vectors.strings
import java.time.LocalDate
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs contracts/planner_vectors.json through the Kotlin [Planner] and [PlanStateText]: the same file
 * tools/test_planner.py runs through the Python reference. A disagreement is a bug in one of them, found
 * here rather than on the phone.
 */
class PlannerTest {

    @Test
    fun everyVectorPasses() {
        val cases = Vectors.cases("contracts/planner_vectors.json")
        val failures = mutableListOf<String>()
        for (case in cases) {
            val name = case.str("name")
            val fn = case.str("fn")
            val args = case.getValue("args").jsonObject
            val expected = case.getValue("expect")
            val got = try {
                call(fn, args)
            } catch (e: Exception) {
                json(Vectors.exception(e))
            }
            if (!Vectors.same(got, expected)) {
                failures.add("$fn: $name\n   got      $got\n   expected $expected")
            }
        }
        assertTrue(
            "${failures.size} of ${cases.size} planner vectors failed:\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    /** The PLAN_STATE text is code output, so the samples in the prompts must match it word for word. */
    @Test
    fun theGeneratedPlanStateTextMatchesTheSamplesInThePrompts() {
        val history = checkins(
            Vectors.cases("contracts/planner_vectors.json")
                .first { it.str("name").startsWith("typical pace window") }
                .getValue("args").jsonObject.getValue("checkins").jsonArray.map { it.jsonObject },
        )
        val today = LocalDate.parse("2026-10-09")
        val typical = Planner.typicalPace(history, "Sat-Thu", today)
        val best = Planner.bestPace(history, "Sat-Thu", today)
        val samples = listOf(
            Sample(listOf("prompts/examples.md", "contracts/context.md"), "Chemistry lectures",
                "Clear the chemistry lectures", 40.0, 0.0, "2026-11-20"),
            Sample(listOf("prompts/testbench.md"), "Exam lectures", "Clear the exam lectures", 50.0, 6.0, "2026-11-20"),
            Sample(listOf("prompts/testbench.md"), "Exam lectures", "Clear the exam lectures", 48.0, 6.0, "2026-11-27"),
        )
        val failures = mutableListOf<String>()
        for (s in samples) {
            val left = Planner.daysLeft("Sat-Thu", today, LocalDate.parse(s.deadline))
            val projection = Planner.project(s.total - s.done, left, typical.pace, best)
            val lines = PlanStateText.backlogLines(
                "B1", s.name, s.total, s.done, "Sat-Thu", projection, typical.answered, typical.answerRate,
            ).toMutableList()
            lines.add(PlanStateText.goalLine("G2", s.title, "${s.deadline.takeLast(2).toInt()} Nov"))
            lines.add(PlanStateText.targetLine("T3", s.name, "G2", 1.0, 1.0, "Sat-Thu", typical.done, typical.answered))
            lines.add(PlanStateText.reviewOptionsLine(Planner.reviewOptions("Sat-Thu", today)))
            for (file in s.files) {
                val flat = Vectors.readText(file).split(Regex("\\s+")).joinToString(" ")
                for (line in lines) {
                    val needle = line.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
                    if (!flat.contains(needle)) failures.add("$file does not contain: ${line.trim()}")
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private data class Sample(
        val files: List<String>,
        val name: String,
        val title: String,
        val total: Double,
        val done: Double,
        val deadline: String,
    )

    private fun checkins(rows: List<JsonObject>): List<CheckIn> = rows.map {
        CheckIn(
            day = LocalDate.parse(it.str("day")),
            level = it.str("level"),
            amount = it.dblOrNull("amount") ?: 0.0,
            reason = it.strOrNull("reason"),
        )
    }

    private fun checkinJson(c: CheckIn): JsonElement = jsonNoNulls(
        listOf("day" to c.day.toString(), "level" to c.level, "amount" to c.amount, "reason" to c.reason),
    )

    private fun steps(args: JsonObject): List<PlanStep> = args.getValue("steps").jsonArray.map { it.jsonObject }.map {
        PlanStep(it.dbl("target"), it.str("schedule"), LocalDate.parse(it.str("start")), it.strOrNull("end")?.let(LocalDate::parse))
    }

    private fun planFinishJson(p: PlanFinish): JsonElement = jsonNoNulls(
        listOf(
            "remaining" to p.remaining,
            "status" to p.status,
            "reaches_by_deadline" to p.reachesByDeadline,
            "finish" to p.finish?.toString(),
            "days_spare" to p.daysSpare,
        ),
    )

    private fun restDates(args: JsonObject): Set<LocalDate> =
        args.strings("rest_dates").map { LocalDate.parse(it) }.toSet()

    private fun call(fn: String, args: JsonObject): JsonElement = when (fn) {
        "day_key" -> json(
            Planner.dayKey(args.str("utc_iso"), args.str("tz"), args.intOr("boundary_hour", 4)).toString(),
        )

        "level_for" -> json(Planner.levelFor(args.dbl("amount"), args.dbl("minimum"), args.dbl("target")))

        "parse_schedule" -> when (val s = Planner.parseSchedule(args.str("s"))) {
            is Schedule.Days -> json(listOf("days", s.weekdays.sorted()))
            is Schedule.Count -> json(listOf("count", s.perWeek))
        }

        "scheduled_count" -> json(
            Planner.scheduledCount(
                args.str("schedule"),
                LocalDate.parse(args.str("start")),
                LocalDate.parse(args.str("end")),
                restDates(args),
            ),
        )

        "days_left" -> json(
            Planner.daysLeft(
                args.str("schedule"),
                LocalDate.parse(args.str("today")),
                LocalDate.parse(args.str("deadline")),
                args.boolOr("today_answered", false),
                restDates(args),
            ),
        )

        "typical_pace" -> {
            val w = Planner.typicalPace(
                checkins(args.getValue("checkins").jsonArray.map { it.jsonObject }),
                args.str("schedule"),
                LocalDate.parse(args.str("today")),
                restDates(args),
                args.strOrNull("start")?.let(LocalDate::parse),
            )
            json(
                mapOf(
                    "answered" to w.answered, "scheduled" to w.scheduled, "amount" to w.amount,
                    "done" to w.done, "pace" to w.pace, "answer_rate" to w.answerRate,
                ),
            )
        }

        "best_pace" -> json(
            Planner.bestPace(
                checkins(args.getValue("checkins").jsonArray.map { it.jsonObject }),
                args.str("schedule"),
                LocalDate.parse(args.str("today")),
                restDates(args),
                args.strOrNull("start")?.let(LocalDate::parse),
            ),
        )

        "project" -> projectJson(
            Planner.project(
                args.dbl("remaining"), args.int("left"), args.dblOrNull("typical"), args.dblOrNull("best"),
                deadlinePassed = args["deadline_passed"]?.jsonPrimitive?.booleanOrNull,
            ),
        )

        "backlog_schedule" -> json(Planner.backlogSchedule(args.strings("schedules")))

        "merge_by_day" -> json(
            PlanBlocks.mergeByDay(checkins(args.getValue("rows").jsonArray.map { it.jsonObject })).map(::checkinJson),
        )

        "backlog_facts" -> {
            val today = LocalDate.parse(args.str("today"))
            val linked = args.getValue("targets").jsonArray.map { it.jsonObject }.mapIndexed { i, t ->
                PlanBlocks.Target(
                    id = i + 1L, name = "T", goalId = null, backlogId = 1L, minimum = 0.0, target = t.dbl("target"),
                    schedule = t.str("schedule"),
                    checkins = checkins(t["checkins"]?.jsonArray?.map { it.jsonObject } ?: emptyList()),
                    start = t.strOrNull("start")?.let(LocalDate::parse),
                    end = t.strOrNull("end")?.let(LocalDate::parse),
                )
            }
            val b = PlanBlocks.Backlog(
                id = 1L, name = "B", goalId = null, total = args.dbl("total"), startDone = args.dbl("start_done"),
                deadline = LocalDate.parse(args.str("deadline")), createdDay = LocalDate.parse(args.str("created_day")),
                doneElsewhere = checkins(args["done_elsewhere"]?.jsonArray?.map { it.jsonObject } ?: emptyList()),
                firstStart = args.strOrNull("first_start")?.let(LocalDate::parse),
            )
            val f = PlanBlocks.backlogFacts(b, linked, today, restDates(args))
            json(
                mapOf(
                    "done" to f.done, "schedule" to f.schedule, "days_left" to f.projection.daysLeft,
                    "projection" to projectJson(f.projection), "window_answered" to f.windowAnswered,
                    "answer_rate" to f.answerRate, "finish" to f.finish?.let(::planFinishJson),
                ),
            )
        }

        "replan_flag" -> json(
            Planner.replanFlag(
                args.getValue("reviews").jsonArray.map { it.jsonObject }.map { r ->
                    ReviewPace(r.dbl("projected_done"), r.dbl("remaining"), r.int("answered_days"))
                },
            ),
        )

        "review_options" -> json(
            Planner.reviewOptions(args.str("schedule"), LocalDate.parse(args.str("today")), restDates(args))
                .map { mapOf("date" to it.date, "weekday" to it.weekday, "after" to it.after) },
        )

        "weekly_facts" -> {
            val f = Planner.weeklyFacts(
                checkins(args.getValue("checkins").jsonArray.map { it.jsonObject }),
                args.str("schedule"),
                LocalDate.parse(args.str("week_start")),
                restDates(args),
            )
            json(
                mapOf(
                    "scheduled" to f.scheduled, "answered" to f.answered, "done" to f.done, "missed" to f.missed,
                    "part" to f.part, "done_rate" to f.doneRate, "answer_rate" to f.answerRate,
                    "minimum" to f.minimum, "target" to f.target, "top_reasons" to f.topReasons,
                    "missed_by_weekday" to f.missedByWeekday,
                ),
            )
        }

        "reason_lines" -> json(
            Planner.reasonLines(
                args.getValue("reasons").jsonArray.map {
                    it.jsonArray[0].jsonPrimitive.content to it.jsonArray[1].jsonPrimitive.content.toInt()
                },
                args.int("short_days"),
            ),
        )

        "plan_finish" -> planFinishJson(
            Planner.planFinish(
                steps(args),
                args.dbl("remaining"),
                LocalDate.parse(args.str("today")),
                LocalDate.parse(args.str("deadline")),
                args.boolOr("today_answered", false),
                restDates(args),
            ),
        )

        "plan_line" -> {
            val p = args.getValue("p").jsonObject
            json(
                PlanStateText.planLine(
                    args.str("bid"),
                    args.str("name"),
                    steps(args),
                    PlanFinish(
                        remaining = p.dbl("remaining"),
                        status = p.str("status"),
                        reachesByDeadline = p.dblOrNull("reaches_by_deadline"),
                        finish = p.strOrNull("finish")?.let(LocalDate::parse),
                        daysSpare = p.strOrNull("days_spare")?.toInt(),
                    ),
                ),
            )
        }

        "backlog_lines" -> {
            val p = args.getValue("p").jsonObject
            json(
                PlanStateText.backlogLines(
                    bid = args.str("bid"),
                    name = args.str("name"),
                    total = args.dbl("total"),
                    done = args.dbl("done"),
                    schedule = args.str("schedule"),
                    p = Projection(
                        remaining = p.dbl("remaining"),
                        daysLeft = p.int("days_left"),
                        status = p.str("status"),
                        requiredPerDay = p.dblOrNull("required_per_day"),
                        typicalPerDay = p.dblOrNull("typical_per_day"),
                        reachesTypical = p.dblOrNull("reaches_typical"),
                        shortTypical = p.dblOrNull("short_typical"),
                        bestPerDay = p.dblOrNull("best_per_day"),
                        reachesBest = p.dblOrNull("reaches_best"),
                        shortBest = p.dblOrNull("short_best"),
                    ),
                    windowAnswered = args.int("window_answered"),
                    answerRate = args.dblOrNull("answer_rate"),
                    availability = args.strOrNull("availability") ?: "not set",
                ),
            )
        }

        else -> error("the vectors call $fn, which this test does not know")
    }

    private fun projectJson(p: Projection): JsonElement = jsonNoNulls(
        listOf(
            "remaining" to p.remaining,
            "days_left" to p.daysLeft,
            "required_per_day" to p.requiredPerDay,
            "typical_per_day" to p.typicalPerDay,
            "reaches_typical" to p.reachesTypical,
            "short_typical" to p.shortTypical,
            "best_per_day" to p.bestPerDay,
            "reaches_best" to p.reachesBest,
            "short_best" to p.shortBest,
            "status" to p.status,
        ),
    )
}
