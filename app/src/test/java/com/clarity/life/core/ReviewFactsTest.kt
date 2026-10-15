package com.clarity.life.core

import com.clarity.life.core.Vectors.dblOrNull
import com.clarity.life.core.Vectors.str
import com.clarity.life.core.Vectors.strOrNull
import com.clarity.life.core.Vectors.strings
import java.time.LocalDate
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The facts under a weekly or monthly review: counted in code, the same way as the weekly vectors. */
class ReviewFactsTest {

    private fun checkins(rows: kotlinx.serialization.json.JsonArray) = rows.map { it.jsonObject }.map {
        CheckIn(LocalDate.parse(it.str("day")), it.str("level"), it.dblOrNull("amount") ?: 0.0, it.strOrNull("reason"))
    }

    @Test
    fun aWeekCountsExactlyLikeTheWeeklyVectors() {
        val cases = Vectors.cases("contracts/planner_vectors.json").filter { it.str("fn") == "weekly_facts" }
        assertTrue(cases.isNotEmpty())
        for (c in cases) {
            val a = c.getValue("args").jsonObject
            val ch = checkins(a.getValue("checkins").jsonArray)
            val start = LocalDate.parse(a.str("week_start"))
            val rest = a.strings("rest_dates").map { LocalDate.parse(it) }.toSet()
            assertEquals(
                c.str("name"),
                Planner.weeklyFacts(ch, a.str("schedule"), start, rest),
                ReviewFacts.period(ch, a.str("schedule"), start, start.plusDays(6), rest),
            )
        }
    }

    @Test
    fun aWeekCountsLikeWeeklyFactsForEverySchedule() {
        for (schedule in listOf("Sat-Thu", "daily", "weekdays", "Mon,Wed,Fri", "3x/week")) {
            for (rest in listOf(emptySet(), setOf(LocalDate.parse("2026-10-06")))) {
                assertEquals(
                    "$schedule $rest",
                    Planner.weeklyFacts(week, schedule, mon, rest),
                    ReviewFacts.period(week, schedule, mon, sun, rest),
                )
            }
        }
    }

    private val mon = LocalDate.parse("2026-10-05")
    private val sun = LocalDate.parse("2026-10-11")

    private fun lectures(checkins: List<CheckIn>) =
        PlanBlocks.Target(3, "Chemistry lectures", 2, 1, 1.0, 2.0, "Sat-Thu", checkins)

    private val week = listOf(
        CheckIn(LocalDate.parse("2026-10-05"), "target", 2.0),
        CheckIn(LocalDate.parse("2026-10-06"), "not", 0.0, "tired"),
        CheckIn(LocalDate.parse("2026-10-07"), "minimum", 1.0, "tired"),
        CheckIn(LocalDate.parse("2026-10-08"), "not", 0.0, "tired"),
        // Friday is off for Sat-Thu, so this answer is not counted
        CheckIn(LocalDate.parse("2026-10-09"), "target", 2.0),
        CheckIn(LocalDate.parse("2026-10-10"), "beyond", 3.0),
    )

    @Test
    fun aWeekReadsAsWritten() {
        val f = ReviewFacts.build("week", mon, sun, listOf(lectures(week)), emptyList(), emptySet())
        assertEquals(
            listOf(
                "Week of Mon 5 Oct to Sun 11 Oct.",
                "T3 \"Chemistry lectures\": answered 5 of 6 scheduled days, done on 3 (1 of them at the minimum), Not on 2.",
                "  Done rate 60%. Answer rate 83%.",
                "  tired on 3 of 3 days marked Not or Part.",
            ),
            f.lines,
        )
        assertEquals(5, f.answered)
        assertFalse(f.replan)
        assertEquals(f, ReviewFacts.fromJson(ReviewFacts.toJson(f)))
    }

    @Test
    fun anEmptyWeekSaysThereIsTooLittleData() {
        val f = ReviewFacts.build("week", mon, sun, listOf(lectures(emptyList())), emptyList(), setOf(LocalDate.parse("2026-10-06")))
        assertEquals(
            listOf(
                "Week of Mon 5 Oct to Sun 11 Oct.",
                "T3 \"Chemistry lectures\": no answers on its 5 scheduled days.",
                "Planned rest days: 1.",
                "No check-ins were answered in this period, so there is too little data for a note.",
            ),
            f.lines,
        )
        assertEquals(0, f.answered)
    }

    @Test
    fun theReplanFlagNeedsTwoShortReviewsInARow() {
        // 10 answered days at 0.6 a day against 40 lectures in 36 days: well below 85% of what remains
        val history = (0 until 12).map { i ->
            val d = LocalDate.parse("2026-09-27").plusDays(i.toLong())
            CheckIn(d, if (i % 2 == 0) "target" else "not", if (i % 2 == 0) 1.0 else 0.0)
        }.filter { it.day.dayOfWeek != java.time.DayOfWeek.FRIDAY }
        val backlog = PlanBlocks.Backlog(1, "Chemistry lectures", 2, 40.0, 0.0, LocalDate.parse("2026-11-20"), LocalDate.parse("2026-10-12"))
        val t = lectures(history)
        val first = ReviewFacts.build("week", mon, sun, listOf(t), listOf(backlog), emptySet())
        assertFalse(first.replan)
        val p = first.backlogs.single()
        assertTrue(p.answered >= Planner.MIN_ANSWERED)
        assertTrue(p.projected!! < Planner.REPLAN_RATIO * p.remaining)
        val second = ReviewFacts.build("week", mon, sun, listOf(t), listOf(backlog), emptySet(), previous = first.backlogs)
        assertTrue(second.replan)
        assertEquals("Re-plan flag: yes (below pace in two reviews in a row).", second.lines.last())
        // never for a month
        assertFalse(ReviewFacts.build("month", mon, sun, listOf(t), listOf(backlog), emptySet(), previous = first.backlogs).replan)
    }

    @Test
    fun aMonthHasItsNameAndWeekdayLines() {
        val sundays = (0 until 4).map { CheckIn(LocalDate.parse("2026-10-04").plusDays(7L * it), "not", 0.0) }
        val t = PlanBlocks.Target(5, "Walk", null, null, 0.0, 1.0, "daily", sundays)
        val f = ReviewFacts.build("month", LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"), listOf(t), emptyList(), emptySet())
        assertEquals("October 2026, Thu 1 Oct to Sat 31 Oct.", f.lines[0])
        assertTrue(f.lines.toString(), f.lines.contains("  Not on 4 Sundays."))
    }

    /** Max plan Phase 6: the week before, the 28-day trend, and plan versus actual under the values of each day. */
    @Test
    fun aWeekComparesWithTheWeekBeforeAndWithThePlan() {
        val start = LocalDate.parse("2026-09-14")
        // Four weeks before this one, every day answered: done on 3, 4, 5 and 6 days of 7; this week on all 7.
        val rows = (0 until 35).map { i ->
            val day = start.plusDays(i.toLong())
            val week = i / 7
            val done = (i % 7) < listOf(3, 4, 5, 6, 7)[week]
            CheckIn(day, if (done) "target" else "not", if (done) 2.0 else 0.0)
        }
        // The target was raised from 1 to 2 a day on 12 Oct, the Monday of this week.
        val t = PlanBlocks.Target(
            3, "Lectures", 2, 1, 0.0, 2.0, "daily", rows, start,
            versions = listOf(
                TargetVersion(start, 0.0, 1.0, "daily", "active"),
                TargetVersion(LocalDate.parse("2026-10-12"), 0.0, 2.0, "daily", "active"),
            ),
        )
        val backlog = PlanBlocks.Backlog(1, "Lectures", 2, 200.0, 0.0, LocalDate.parse("2026-12-31"), start)
        val f = ReviewFacts.build("week", LocalDate.parse("2026-10-12"), LocalDate.parse("2026-10-18"), listOf(t), listOf(backlog), emptySet())
        assertTrue(f.text, f.lines.contains("  The week before: done on 6 of 7 answered days."))
        assertTrue(f.text, f.lines.contains("  Done rate by week, last 4 weeks (oldest first): 57%, 71%, 86%, 100%."))
        assertTrue(f.text, f.lines.contains("  B1 \"Lectures\": the plan asked for 14 in this period, done 14, exactly on it."))
        // The same week read against the old value would have said ahead: the change counts from its day only.
        val old = ReviewFacts.build("week", LocalDate.parse("2026-10-05"), LocalDate.parse("2026-10-11"), listOf(t), listOf(backlog), emptySet())
        assertTrue(old.text, old.lines.contains("  B1 \"Lectures\": the plan asked for 7 in this period, done 12, ahead by 5."))
        assertEquals(f, ReviewFacts.fromJson(ReviewFacts.toJson(f)))
    }
}
