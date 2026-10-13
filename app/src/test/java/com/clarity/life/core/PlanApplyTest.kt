package com.clarity.life.core

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** What a tap on a plan, target_change or backlog card writes, worked out before he taps. */
class PlanApplyTest {

    private val today = LocalDate.parse("2026-10-09")
    private val known = KnownIds(
        goals = setOf("G2"), targets = setOf("T3"), backlogs = setOf("B1"), reviewOptions = setOf("17 Oct", "24 Oct"),
    )
    private val t3 = PlanApply.OldTarget(3, 2, "Chemistry lectures", "lectures", 1.0, 1.0, "Sat-Thu", null)
    private val b1 = PlanApply.OldBacklog(
        1, 2, "Chemistry lectures", "lectures", 40.0, 0.0, LocalDate.parse("2026-11-20"),
    )

    private fun card(json: String) = CardParser.validate(StrictJson.parse(json), known)

    private fun days(c: Card) = (PlanDates.checkCard(c, today) as PlanDates.Checked.Ok).days

    private val syllabus = """{"type":"plan","title":"Clear the chemistry lectures","goal":"G2","rationale":"Raise the pace","targets":[{"name":"Chemistry lectures","unit":"lectures","min":1,"target":2,"schedule":"Sat-Thu","start":"tomorrow","end":"20 Nov"}],"backlog":[{"name":"Chemistry lectures","total":40,"unit":"lectures","deadline":"20 Nov"}],"review_date":"17 Oct"}"""

    @Test
    fun theSyllabusPlanReusesTheTargetAndTheBacklog() {
        val c = card(syllabus)
        val s = PlanApply.plan(c, days(c), setOf(2), listOf(t3), listOf(b1))
        assertEquals(2L, s.goalId)
        assertEquals(listOf<PlanApply.BacklogStep>(PlanApply.BacklogStep.Keep(b1)), s.backlogs)
        val t = s.targets.single() as PlanApply.TargetStep.Change
        assertEquals(t3, t.old)
        assertNull(t.minimum)
        assertEquals(2.0, t.target!!, 0.0)
        assertEquals(LocalDate.parse("2026-11-20"), t.end)
        assertEquals(0, t.backlog)
        assertEquals(
            listOf(
                "Uses B1 Chemistry lectures",
                "T3 Chemistry lectures: target 1 → 2, ends Fri 20 Nov",
                "Review on Sat 17 Oct",
            ),
            s.lines(),
        )
    }

    @Test
    fun aRampUpdatesTheTargetAndAddsItsLaterStepsOneAfterAnother() {
        val c = card(
            """{"type":"plan","title":"Clear the chemistry lectures","goal":"G2","rationale":"Their ramp","targets":[{"name":"Chemistry lectures","unit":"lectures","min":1,"target":2,"schedule":"Sat-Thu","start":"tomorrow","weeks":1},{"name":"Chemistry lectures","unit":"lectures","min":1,"target":3,"schedule":"Sat-Thu","weeks":1},{"name":"Chemistry lectures","unit":"lectures","min":1,"target":4,"schedule":"Sat-Thu"}],"backlog":[{"name":"Chemistry lectures","total":40,"unit":"lectures","deadline":"20 Nov"}],"review_date":"17 Oct"}""",
        )
        val s = PlanApply.plan(c, days(c), setOf(2), listOf(t3), listOf(b1))
        assertEquals(3, s.targets.size)
        val first = s.targets[0] as PlanApply.TargetStep.Change
        assertEquals(t3, first.old)
        assertEquals(LocalDate.parse("2026-10-16"), first.end) // Sat 10 Oct + one week - 1 day
        val second = s.targets[1] as PlanApply.TargetStep.New
        assertEquals(LocalDate.parse("2026-10-17"), second.start)
        assertEquals(LocalDate.parse("2026-10-23"), second.end)
        assertEquals(3.0, second.target, 0.0)
        assertEquals(0, second.backlog)
        val third = s.targets[2] as PlanApply.TargetStep.New
        assertEquals(LocalDate.parse("2026-10-24"), third.start)
        assertNull(third.end)
        assertEquals(0, third.backlog)
    }

    @Test
    fun aNewGoalGetsNewRowsLinkedByName() {
        val c = card(syllabus.replace("\"goal\":\"G2\",", ""))
        val s = PlanApply.plan(c, days(c), setOf(2), listOf(t3), listOf(b1))
        assertNull(s.goalId)
        // Rows of another goal are never reused by a plan for a new goal.
        assertTrue(s.backlogs.single() is PlanApply.BacklogStep.New)
        val t = s.targets.single() as PlanApply.TargetStep.New
        assertEquals(0, t.backlog)
        assertEquals(LocalDate.parse("2026-10-10"), t.start)
        assertEquals(
            listOf(
                "New goal: Clear the chemistry lectures",
                "New backlog: Chemistry lectures, 40 lectures by Fri 20 Nov",
                "New target: Chemistry lectures, minimum 1, target 2 lectures, Sat-Thu, from Sat 10 Oct to Fri 20 Nov",
                "Review on Sat 17 Oct",
            ),
            s.lines(),
        )
    }

    @Test
    fun oneBacklogAndOneTargetWithTheSameUnitAreLinkedEvenWithDifferentNames() {
        val c = card(
            """{"type":"plan","title":"Exam","rationale":"r","targets":[{"name":"Lectures each day","unit":"lectures","target":2,"schedule":"daily"}],"backlog":[{"name":"Syllabus","total":40,"unit":"Lectures","deadline":"20 Nov"}],"review_date":"17 Oct"}""",
        )
        val t = PlanApply.plan(c, days(c), emptySet(), emptyList(), emptyList()).targets.single() as PlanApply.TargetStep.New
        assertEquals(0, t.backlog)
        val other = card(
            """{"type":"plan","title":"Exam","rationale":"r","targets":[{"name":"Walk","unit":"km","target":2,"schedule":"daily"}],"backlog":[{"name":"Syllabus","total":40,"unit":"lectures","deadline":"20 Nov"}],"review_date":"17 Oct"}""",
        )
        val u = PlanApply.plan(other, days(other), emptySet(), emptyList(), emptyList()).targets.single() as PlanApply.TargetStep.New
        assertNull(u.backlog)
    }

    @Test
    fun aGoalThatIsGoneRefusesTheCard() {
        val c = card(syllabus)
        try {
            PlanApply.plan(c, days(c), emptySet(), listOf(t3), listOf(b1))
            fail()
        } catch (e: PlanApply.Refused) {
            assertEquals("goal gone", e.why)
        }
    }

    @Test
    fun aTargetChangeIsCheckedAgainstTheStoredValues() {
        fun change(json: String): PlanApply.TargetStep.Change {
            val c = card(json)
            return PlanApply.targetChange(c, (PlanDates.checkCard(c, today) as PlanDates.Checked.Ok).days, listOf(t3))
        }
        assertEquals(
            "T3 Chemistry lectures: target 1 → 2",
            PlanApply.changeLine(change("""{"type":"target_change","target":"T3","target_value":2,"reason":"r"}""")),
        )
        for ((json, why) in listOf(
            """{"type":"target_change","target":"T3","min":2,"reason":"r"}""" to "min above target",
            """{"type":"target_change","target":"T3","target_value":1,"reason":"r"}""" to "changes nothing",
        )) {
            try {
                change(json)
                fail(json)
            } catch (e: PlanApply.Refused) {
                assertEquals(why, e.why)
            }
        }
    }

    @Test
    fun aBacklogCardMakesOrChangesOne() {
        fun step(json: String): PlanApply.BacklogStep {
            val c = card(json)
            return PlanApply.backlog(c, (PlanDates.checkCard(c, today) as PlanDates.Checked.Ok).days, listOf(b1.copy(startDone = 6.0)))
        }
        assertEquals(
            PlanApply.BacklogStep.New("Past papers", 12.0, "papers", LocalDate.parse("2026-11-30")),
            step("""{"type":"backlog","name":"Past papers","total":12,"unit":"papers","deadline":"30 Nov","reason":"r"}"""),
        )
        val ch = step("""{"type":"backlog","backlog":"B1","deadline":"27 Nov","reason":"r"}""") as PlanApply.BacklogStep.Change
        assertNull(ch.total)
        assertEquals(LocalDate.parse("2026-11-27"), ch.deadline)
        try {
            step("""{"type":"backlog","backlog":"B1","total":5,"reason":"r"}""")
            fail()
        } catch (e: PlanApply.Refused) {
            assertEquals("total below done", e.why)
        }
    }
}
