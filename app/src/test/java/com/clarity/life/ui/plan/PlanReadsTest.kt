package com.clarity.life.ui.plan

import com.clarity.life.data.entity.BacklogRow
import com.clarity.life.data.entity.CheckinRow
import com.clarity.life.data.entity.GoalRow
import com.clarity.life.data.entity.TargetRow
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plan reads only the check-ins of its current targets ([PlanViewModel.targetsToRead]), and Talk home and the
 * Plan tab's dot read no history at all ([PlanViewModel.homeOf]) (15 Oct 2026, the home was slow to appear).
 * Both must show exactly what the old way showed: the screen built from every check-in ever.
 */
class PlanReadsTest {

    private val today = LocalDate.of(2026, 10, 15)
    private val now = today.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val schedules = listOf("daily", "weekdays", "Mon,Wed,Fri", "Sat-Thu", "3x/week")

    private fun randomPlan(rnd: Random): Pair<PlanViewModel.Rows, List<CheckinRow>> {
        val goals = (1L..rnd.nextLong(0, 5)).map { id ->
            GoalRow(id = id, title = "Goal $id", status = listOf("active", "active", "done", "archived").random(rnd), createdAt = 0)
        }
        val backlogs = (1L..rnd.nextLong(0, 5)).map { id ->
            BacklogRow(
                id = id,
                goalId = if (goals.isEmpty() || rnd.nextInt(4) == 0) null else goals.random(rnd).id,
                name = "Backlog $id",
                total = rnd.nextInt(20, 200).toDouble(),
                unit = "pages",
                startDone = rnd.nextInt(0, 10).toDouble(),
                deadline = today.plusDays(rnd.nextLong(-20, 120)).toString(),
                status = listOf("active", "active", "done", "archived").random(rnd),
                createdAt = 0,
                startDay = today.minusDays(rnd.nextLong(0, 90)).toString(),
            )
        }
        val targets = (1L..rnd.nextLong(0, 9)).map { id ->
            val start = today.minusDays(rnd.nextLong(-5, 80))
            TargetRow(
                id = id,
                goalId = if (goals.isEmpty() || rnd.nextInt(4) == 0) null else goals.random(rnd).id,
                backlogId = if (backlogs.isEmpty() || rnd.nextInt(3) == 0) null else backlogs.random(rnd).id,
                name = "Target $id",
                unit = if (rnd.nextBoolean()) "pages" else "",
                minimum = 1.0,
                target = rnd.nextInt(2, 6).toDouble(),
                schedule = schedules.random(rnd),
                startDay = start.toString(),
                endDay = if (rnd.nextInt(3) == 0) start.plusDays(rnd.nextLong(0, 60)).toString() else null,
                status = listOf("active", "active", "paused", "ended").random(rnd),
            )
        }
        val checkins = targets.flatMap { t ->
            (0L..70L).mapNotNull { back ->
                val day = today.minusDays(back)
                if (rnd.nextInt(3) == 0) return@mapNotNull null
                val level = listOf("rest", "not", "minimum", "target", "beyond").random(rnd)
                CheckinRow(
                    targetId = t.id,
                    dayKey = day.toString(),
                    level = level,
                    amount = if (level == "rest" || level == "not") 0.0 else rnd.nextInt(1, 8).toDouble(),
                    reason = if (level == "not") listOf("tired", "busy").random(rnd) else null,
                    enteredAt = 0,
                )
            }
        }.sortedBy { it.dayKey }
        val rest = (0L..70L).filter { rnd.nextInt(15) == 0 }.map { today.minusDays(it) }.toSet()
        return PlanViewModel.Rows(goals, targets, backlogs, emptyList(), rest) to checkins
    }

    @Test
    fun planShowsTheSameFromOnlyItsCurrentTargetsCheckins() {
        val rnd = Random(20261015)
        var skipped = 0
        repeat(400) {
            val (rows, all) = randomPlan(rnd)
            val read = PlanViewModel.targetsToRead(rows.targets, rows.backlogs).toSet()
            val some = all.filter { it.targetId in read }
            skipped += all.size - some.size
            val before = PlanViewModel.build(rows, all, "UTC", 0, now, null)
            val after = PlanViewModel.build(rows, some, "UTC", 0, now, null)
            assertEquals(before, after)
        }
        // The cases really leave check-ins out, or this would prove nothing.
        assertTrue("skipped $skipped", skipped > 1_000)
    }

    @Test
    fun theHomeAndTheTabDotMatchTheFullPlan() {
        val rnd = Random(15102026)
        repeat(400) {
            val (rows, all) = randomPlan(rnd)
            val full = PlanViewModel.build(rows, all, "UTC", 0, now, null)
            // The home reads only today's answers (check-ins from today on).
            val home = PlanViewModel.homeOf(rows, today, all.filter { it.dayKey >= today.toString() })
            assertEquals(full.today, home.today)
            assertEquals(
                full.goals.mapNotNull { it.firstStart }.minOrNull()?.takeIf { !it.isAfter(full.today) }
                    ?.let { java.time.temporal.ChronoUnit.DAYS.between(it, full.today) + 1 },
                home.planDay,
            )
            assertEquals(full.todayRows.isNotEmpty() || full.goals.isNotEmpty(), home.hasPlan)
            assertEquals(Waiting.today(full), Waiting.today(home))
        }
    }

    @Test
    fun aFinishedGoalsTargetIsNotRead() {
        val ended = TargetRow(id = 1, name = "Old", target = 1.0, schedule = "daily", startDay = "2016-01-01", status = "ended")
        val inBacklog = ended.copy(id = 2, backlogId = 5)
        val paused = ended.copy(id = 3, status = "paused")
        val backlog = BacklogRow(id = 5, name = "Book", total = 10.0, unit = "pages", deadline = "2027-01-01", createdAt = 0)
        assertEquals(listOf(2L, 3L), PlanViewModel.targetsToRead(listOf(ended, inBacklog, paused), listOf(backlog)))
        assertEquals(listOf(3L), PlanViewModel.targetsToRead(listOf(ended, inBacklog, paused), listOf(backlog.copy(status = "done"))))
    }
}
