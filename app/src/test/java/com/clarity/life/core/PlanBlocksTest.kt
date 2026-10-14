package com.clarity.life.core

import com.clarity.life.core.Vectors.dblOrNull
import com.clarity.life.core.Vectors.str
import com.clarity.life.core.Vectors.strOrNull
import java.time.LocalDate
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PLAN_STATE and CHECKIN_FACTS as assembled from rows: the syllabus case must come out as the spec's sample. */
class PlanBlocksTest {

    private val today = LocalDate.parse("2026-10-09")

    /** The syllabus history of contracts/planner_vectors.json: 10 days all done, then 6 done of 10. */
    private val history: List<CheckIn> = Vectors.cases("contracts/planner_vectors.json")
        .first { it.str("name").startsWith("typical pace window") }
        .getValue("args").jsonObject.getValue("checkins").jsonArray.map { it.jsonObject }
        .map {
            CheckIn(LocalDate.parse(it.str("day")), it.str("level"), it.dblOrNull("amount") ?: 0.0, it.strOrNull("reason"))
        }

    private fun syllabus(checkins: List<CheckIn> = history, replan: Boolean = false) = PlanBlocks.Tracker(
        goals = listOf(PlanBlocks.Goal(2, "Clear the chemistry lectures", LocalDate.parse("2026-11-20"))),
        targets = listOf(PlanBlocks.Target(3, "Chemistry lectures", 2, 1, 1.0, 1.0, "Sat-Thu", checkins)),
        backlogs = listOf(
            PlanBlocks.Backlog(1, "Chemistry lectures", 2, 40.0, 0.0, LocalDate.parse("2026-11-20"), today),
        ),
        restDays = emptySet(),
        replanFlag = replan,
    )

    private fun text(b: Block) = b.items.joinToString("\n") { it.text }

    @Test
    fun theSyllabusCaseIsTheSampleInContextMd() {
        val expected = """
            Goal G2 "Clear the chemistry lectures", deadline 20 Nov.
            Backlog B1 "Chemistry lectures": total 40, done 0, remaining 40, scheduled days left 36 (Fridays off), required 1.2 per day.
              Typical pace (last 14 days, 10 answered days): 0.6 per day, reaches 21 of 40, short by 19.
              Best fortnight so far: 1.0 per day, reaches 36 of 40, short by 4.
              Status: does not reach the total, not even at the best-fortnight pace.
              Answer rate 83%. Availability: not set.
            Target T3 "Chemistry lectures" (G2): minimum 1, target 1, Sat-Thu. Last 14 days: done 6 of 10 answered days.
            Review options: 17 Oct (Saturday, after one week), 24 Oct (Saturday, after two weeks).
        """.trimIndent()
        assertEquals(expected, text(PlanBlocks.planState(syllabus(), today)))
        // and every line is in the spec's own sample, word for word
        val spec = Vectors.readText("contracts/context.md")
        for (line in expected.lines()) assertTrue(line, spec.contains(line))
    }

    @Test
    fun knownIdsAreExactlyTheOnesSent() {
        val k = PlanBlocks.known(syllabus(), today)
        assertEquals(setOf("G2"), k.goals)
        assertEquals(setOf("T3"), k.targets)
        assertEquals(setOf("B1"), k.backlogs)
        assertEquals(setOf("17 Oct", "24 Oct"), k.reviewOptions)
    }

    @Test
    fun workEnteredAfterTheBacklogWasMadeCountsAsDone() {
        val sat = LocalDate.parse("2026-10-10")
        val before = text(PlanBlocks.planState(syllabus(), sat)).lines()[1]
        assertTrue(before, before.startsWith("Backlog B1 \"Chemistry lectures\": total 40, done 0, remaining 40, scheduled days left 36"))
        // Saturday answered with 2: done 2, and Saturday no longer counts as a day left
        val line = text(PlanBlocks.planState(syllabus(history + CheckIn(sat, "beyond", 2.0)), sat)).lines()[1]
        assertTrue(line, line.startsWith("Backlog B1 \"Chemistry lectures\": total 40, done 2, remaining 38, scheduled days left 35"))
    }

    @Test
    fun theReplanFlagAndAnEmptyTracker() {
        assertTrue(text(PlanBlocks.planState(syllabus(replan = true), today)).endsWith(
            "Re-plan flag: yes (below pace in two reviews in a row).",
        ))
        val empty = PlanBlocks.Tracker(emptyList(), emptyList(), emptyList(), emptySet())
        assertEquals(
            "No goals, targets or backlogs yet.\nReview options: 16 Oct (Friday, after one week), 23 Oct (Friday, after two weeks).",
            text(PlanBlocks.planState(empty, today)),
        )
        assertTrue(PlanBlocks.checkinFacts(empty, today).items.isEmpty())
    }

    @Test
    fun reviewDaysAreTheDaysOfAnyTarget() {
        fun t(s: String) = PlanBlocks.Target(1, "x", null, null, 0.0, 1.0, s, emptyList())
        assertEquals("daily", PlanBlocks.reviewSchedule(emptyList()))
        assertEquals("daily", PlanBlocks.reviewSchedule(listOf(t("3x/week"))))
        assertEquals("Mon,Sat", PlanBlocks.reviewSchedule(listOf(t("Sat"), t("Mon"), t("2x/week"))))
        assertEquals("daily", PlanBlocks.reviewSchedule(listOf(t("weekdays"), t("Sat,Sun"))))
    }

    @Test
    fun checkinFactsCountOnlyWhatHasThreeItemsBehindIt() {
        val reasons = listOf(
            CheckIn(LocalDate.parse("2026-10-03"), "not", 0.0, "tired"),
            CheckIn(LocalDate.parse("2026-10-04"), "not", 0.0, "tired"),
            CheckIn(LocalDate.parse("2026-10-05"), "minimum", 1.0, "tired"),
            CheckIn(LocalDate.parse("2026-10-06"), "not", 0.0, "no time"),
            CheckIn(LocalDate.parse("2026-10-07"), "target", 1.0),
            CheckIn(LocalDate.parse("2026-10-08"), "target", 1.0),
        )
        val facts = text(PlanBlocks.checkinFacts(syllabus(reasons), today))
        assertEquals(
            """
            T3 "Chemistry lectures", last 14 days: 6 answered of 12 scheduled, done on 3.
            T3 "Chemistry lectures", last 14 days: tired on 3 of 4 days marked Not or Part.
            """.trimIndent(),
            facts,
        )
        // Friday is not a scheduled day for Sat-Thu, so there is no line for today
        assertFalse(facts.contains("Today"))
        val sat = LocalDate.parse("2026-10-10")
        assertTrue(text(PlanBlocks.checkinFacts(syllabus(reasons), sat)).startsWith(
            "Today (Saturday): T3 \"Chemistry lectures\" not answered yet.",
        ))
    }

    @Test
    fun theLongerViewAndAGrowingReasonAreCountedToo() {
        fun c(day: String, level: String, reason: String?) = CheckIn(LocalDate.parse(day), level, 0.0, reason)
        val rows = listOf(
            c("2026-08-04", "not", "family"), c("2026-08-11", "not", "family"),
            c("2026-09-15", "not", "family"), c("2026-09-16", "minimum", "family"), c("2026-09-22", "not", "family"),
            c("2026-10-03", "not", "tired"), c("2026-10-04", "not", "tired"), c("2026-10-05", "minimum", "tired"),
            c("2026-10-06", "not", "no time"),
            CheckIn(LocalDate.parse("2026-10-07"), "target", 1.0), CheckIn(LocalDate.parse("2026-10-08"), "target", 1.0),
        )
        assertEquals(
            """
            T3 "Chemistry lectures", last 14 days: 6 answered of 12 scheduled, done on 3.
            T3 "Chemistry lectures", last 14 days: tired on 3 of 4 days marked Not or Part.
            T3 "Chemistry lectures", last 90 days: family on 5 of 9 days marked Not or Part.
            T3 "Chemistry lectures", last 90 days: tired on 3 of 9 days marked Not or Part.
            T3 "Chemistry lectures", last 90 days: marked Not on 5 Tuesdays.
            T3 "Chemistry lectures": tired on 3 of 4 days marked Not or Part in the last 14 days, on 0 of 3 in the 14 days before.
            """.trimIndent(),
            text(PlanBlocks.checkinFacts(syllabus(rows), today)),
        )
    }

    /** Max plan Phase 3 step 4: the time he answered, only on the day itself, three items behind each number. */
    @Test
    fun theTimeOfDayHeAnsweredIsCountedWhenTwoBandsHoldAnswers() {
        fun c(day: String, level: String, reason: String?, minute: Int?) =
            CheckIn(LocalDate.parse(day), level, if (level == "target") 1.0 else 0.0, reason, minute)
        val late = 23 * 60
        val evening = 19 * 60
        val rows = listOf(
            c("2026-09-15", "not", "family", late), c("2026-10-03", "not", "tired", late),
            c("2026-10-04", "not", "tired", 30), c("2026-10-06", "not", "no time", late),
            c("2026-09-16", "minimum", "family", evening), c("2026-10-07", "target", null, evening),
            c("2026-10-08", "target", null, evening),
            // No time known (answered on a later day, or before schema 6): not counted in any band.
            c("2026-09-22", "not", "family", null),
        )
        val facts = text(PlanBlocks.checkinFacts(syllabus(rows), today))
        val line = "T3 \"Chemistry lectures\", last 90 days: answered late at night (22:00 to 4:00) on 4 days, marked Not or Part on 4 of them."
        assertTrue(facts, facts.contains(line))
        assertTrue("one Not or Part in the evening is too few for a line", !facts.contains("in the evening"))

        // Every timed answer in one band says nothing about the time: no line.
        val oneBand = rows.map { if (it.minute == evening) it.copy(minute = late) else it }
        assertTrue(!text(PlanBlocks.checkinFacts(syllabus(oneBand), today)).contains("answered late at night"))
    }

    @Test
    fun aTargetThatHasNotStartedHasNoTodayLine() {
        val sat = LocalDate.parse("2026-10-10")
        val t = syllabus().let { it.copy(targets = it.targets.map { x -> x.copy(start = LocalDate.parse("2026-10-11")) }) }
        assertFalse(text(PlanBlocks.checkinFacts(t, sat)).contains("Today"))
    }

    @Test
    fun namesStayOnOneLineAndInsideTheirQuotes() {
        assertEquals("a 'b' c", PlanBlocks.clean("a \"b\"\n c"))
    }

    @Test
    fun aPausedTargetsWorkStillCountsTowardItsBacklog() {
        val today = LocalDate.of(2026, 10, 10)
        val created = today.minusDays(10)
        val logged = (1..5).map { CheckIn(created.plusDays(it.toLong()), "done", 2.0) }
        val b = PlanBlocks.Backlog(1, "Pages", null, 300.0, 20.0, today.plusDays(30), created)
        // Live: done = start + the linked target's amounts.
        val live = PlanBlocks.backlogFacts(b, listOf(PlanBlocks.Target(1, "Read", null, 1, 1.0, 2.0, "daily", logged)), today, emptySet())
        assertEquals(30.0, live.done, 0.0)
        // Paused: the target is no longer linked as live, but what it logged is still done.
        val paused = PlanBlocks.backlogFacts(b.copy(doneElsewhere = logged), emptyList(), today, emptySet())
        assertEquals(30.0, paused.done, 0.0)
    }

    @Test
    fun theOwnersRampTellsTheCoachWhereItFinishes() {
        val today = LocalDate.parse("2026-10-13")
        fun step(id: Long, target: Double, start: String, end: String?) = PlanBlocks.Target(
            id, "CAF lectures", 1, 1, 1.0, target, "daily", emptyList(), LocalDate.parse(start), end?.let(LocalDate::parse),
        )
        val t = PlanBlocks.Tracker(
            goals = listOf(PlanBlocks.Goal(1, "Clear the CAF course", LocalDate.parse("2027-02-01"))),
            targets = listOf(step(2, 3.0, "2026-10-21", "2026-10-27"), step(1, 2.0, "2026-10-14", "2026-10-20"), step(3, 4.0, "2026-10-28", null)),
            backlogs = listOf(PlanBlocks.Backlog(1, "CAF lectures", 1, 300.0, 0.0, LocalDate.parse("2027-02-01"), today)),
            restDays = emptySet(),
        )
        val block = text(PlanBlocks.planState(t, today))
        assertTrue(
            block,
            block.contains(
                "  Plan steps for B1 \"CAF lectures\": 2 per day, daily, 14 Oct to 20 Oct; 3 per day, daily, 21 Oct to 27 Oct; " +
                    "4 per day, daily, from 28 Oct. At these steps it finishes on 2 Jan (Saturday), 30 days before the deadline.",
            ),
        )
        // Before it starts there is no answer rate to show, rather than 0%.
        assertTrue(block, block.contains("Answer rate not tracked."))
        assertTrue(block, !block.contains("Answer rate 0%"))
    }

    @Test
    fun aRampKeepsItsPaceWhenItMovesToTheNextStep() {
        // Step 1 (2 a day) ran 14 to 20 Oct and has ended; step 2 (3 a day) started on 21 Oct.
        val start = LocalDate.parse("2026-10-14")
        val today = LocalDate.parse("2026-10-23")
        val step1 = (0..6).map { CheckIn(start.plusDays(it.toLong()), "target", 2.0) }
        val step2 = (7..8).map { CheckIn(start.plusDays(it.toLong()), "target", 3.0) }
        val b = PlanBlocks.Backlog(1, "CAF lectures", null, 300.0, 0.0, LocalDate.parse("2027-02-01"), start.minusDays(1), step1, start)
        val live = PlanBlocks.Target(2, "CAF lectures", null, 1, 1.0, 3.0, "daily", step2, LocalDate.parse("2026-10-21"))
        val f = PlanBlocks.backlogFacts(b, listOf(live), today, emptySet())
        assertEquals(20.0, f.done, 0.0)
        // Nine answered days since the ramp began, not two since the current step began: the pace is there.
        assertEquals(9, f.windowAnswered)
        assertEquals(100.0, f.answerRate!!, 0.0)
        assertEquals(20.0 / 9, f.projection.typicalPerDay!!, 1e-9)
    }

    @Test
    fun theCoachSeesHowFarTheRampHasGot() {
        // Step 1 (2 a day, 14 to 20 Oct) ended; one day was marked Not. Step 2 (3 a day) runs from 21 Oct.
        val start = LocalDate.parse("2026-10-14")
        val today = LocalDate.parse("2026-10-23")
        val step1Rows = (0..6).map { CheckIn(start.plusDays(it.toLong()), if (it == 2) "not" else "target", if (it == 2) 0.0 else 2.0) }
        val step2Rows = (7..8).map { CheckIn(start.plusDays(it.toLong()), "target", 3.0) }
        val step1 = PlanBlocks.Target(1, "CAF lectures", 1, 1, 1.0, 2.0, "daily", step1Rows, start, LocalDate.parse("2026-10-20"))
        val step2 = PlanBlocks.Target(2, "CAF lectures", 1, 1, 1.0, 3.0, "daily", step2Rows, LocalDate.parse("2026-10-21"))
        val t = PlanBlocks.Tracker(
            goals = listOf(PlanBlocks.Goal(1, "Clear the CAF course", LocalDate.parse("2027-02-01"))),
            targets = listOf(step2),
            backlogs = listOf(
                PlanBlocks.Backlog(
                    1, "CAF lectures", 1, 300.0, 0.0, LocalDate.parse("2027-02-01"), start, step1Rows, start,
                    offTargets = listOf(step1),
                ),
            ),
            restDays = emptySet(),
        )
        val block = text(PlanBlocks.planState(t, today))
        assertTrue(
            block,
            block.contains("  So far on the plan for B1 \"CAF lectures\" (14 Oct to 22 Oct): the steps asked for 20, done 18, behind by 2."),
        )
    }

    @Test
    fun theCoachSeesHowAChangeWent() {
        val today = LocalDate.parse("2026-10-18")
        val before = (0..13).map { CheckIn(LocalDate.parse("2026-09-28").plusDays(it.toLong()), "target", 1.0) }
        val after = (0..5).map { CheckIn(LocalDate.parse("2026-10-12").plusDays(it.toLong()), if (it == 5) "not" else "target", if (it == 5) 0.0 else 2.0) }
        val x = PlanBlocks.Target(
            3, "Lectures", null, null, 0.0, 2.0, "daily", before + after, LocalDate.parse("2026-09-28"),
            versions = listOf(
                TargetVersion(LocalDate.parse("2026-09-28"), 0.0, 1.0, "daily", "active"),
                TargetVersion(LocalDate.parse("2026-10-12"), 0.0, 2.0, "daily", "active"),
            ),
        )
        val facts = text(PlanBlocks.checkinFacts(PlanBlocks.Tracker(emptyList(), listOf(x), emptyList(), emptySet()), today))
        assertTrue(
            facts,
            facts.contains(
                "T3 \"Lectures\": changed on 12 Oct (target 1 \u2192 2). In the 14 days before: done on 14 of 14 answered days, " +
                    "1.0 per answered day. Since: done on 5 of 6, 1.7 per answered day.",
            ),
        )
        // With no history recorded (made before schema 8) there is no change to report.
        val old = text(PlanBlocks.checkinFacts(PlanBlocks.Tracker(emptyList(), listOf(x.copy(versions = emptyList())), emptyList(), emptySet()), today))
        assertTrue(old, !old.contains("changed on"))
    }
}
