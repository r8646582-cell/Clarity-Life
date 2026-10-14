package com.clarity.life.planner

import androidx.room.withTransaction
import com.clarity.life.core.CheckIn
import com.clarity.life.core.PlanApply
import com.clarity.life.core.PlanBlocks
import com.clarity.life.core.Planner
import com.clarity.life.core.PromiseText
import com.clarity.life.data.ClarityDatabase
import com.clarity.life.data.Values
import com.clarity.life.data.entity.BacklogRow
import com.clarity.life.data.entity.CheckinRow
import com.clarity.life.data.entity.GoalRevisionRow
import com.clarity.life.data.entity.GoalRow
import com.clarity.life.data.entity.PlanRow
import com.clarity.life.data.entity.RestDayRow
import com.clarity.life.data.entity.TargetRow
import com.clarity.life.settings.SettingsRepository
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one writer for the planner (spec section 18, point 2): goals, plans, targets, backlogs, rest days and
 * check-ins. Every command is one transaction and runs only because he tapped something, on a Plan screen or
 * on a card the coach offered (through [com.clarity.life.chat.CardSaver]). Background jobs never call these.
 *
 * Nothing is ever silently changed or deleted: a goal is archived (with a revision row for every change of its
 * wording or date), a target is paused or ended, and a check-in's history stays.
 */
@Singleton
class PlannerRepository @Inject constructor(
    private val db: ClarityDatabase,
    private val settings: SettingsRepository,
) {
    private val dao get() = db.planner()

    val goals get() = dao.watchGoals()
    val targets get() = dao.watchTargets()
    val backlogs get() = dao.watchBacklogs()
    val plans get() = dao.watchPlans()
    val restDays get() = dao.watchRestDays()

    fun checkinsFrom(day: LocalDate) = dao.watchCheckinsFrom(day.toString())

    /** His day key now: the day boundary (midnight by default, a setting) applied in his time zone. */
    suspend fun today(now: Long): LocalDate = Planner.dayKey(
        Instant.ofEpochMilli(now).toString(),
        settings.timeZone(),
        settings.dayBoundaryHour(),
    )

    class Refused(why: String) : IllegalArgumentException(why)

    // ---------- goals ----------

    suspend fun createGoal(
        title: String,
        area: String,
        horizon: String,
        deadline: LocalDate?,
        now: Long,
    ): Long = db.withTransaction {
        val t = title.trim()
        if (t.isEmpty()) throw Refused("a goal needs words")
        Values.requireValid("goal.area", area)
        val id = dao.insertGoal(
            GoalRow(title = t, area = area, horizon = horizon, deadline = deadline?.toString(), createdAt = now),
        )
        dao.insertRevision(GoalRevisionRow(goalId = id, text = revisionText(t, deadline), reason = "created", createdAt = now))
        id
    }

    /** A goal is never silently changed: each new wording or date is kept as a revision, with his reason. */
    suspend fun reviseGoal(id: Long, title: String, deadline: LocalDate?, reason: String, now: Long) =
        db.withTransaction {
            val g = dao.goal(id) ?: throw Refused("goal gone")
            val t = title.trim()
            if (t.isEmpty()) throw Refused("a goal needs words")
            if (t == g.title && deadline?.toString() == g.deadline) return@withTransaction
            dao.updateGoal(g.copy(title = t, deadline = deadline?.toString()))
            dao.insertRevision(GoalRevisionRow(goalId = id, text = revisionText(t, deadline), reason = reason.trim(), createdAt = now))
        }

    /** Done, archived, or back to active. Its targets and backlogs keep their own status and history. */
    suspend fun setGoalStatus(id: Long, status: String) = db.withTransaction {
        Values.requireValid("goal.status", status)
        val g = dao.goal(id) ?: throw Refused("goal gone")
        dao.updateGoal(g.copy(status = status))
    }

    private fun revisionText(title: String, deadline: LocalDate?) =
        title + (deadline?.let { " (deadline $it)" } ?: "")

    // ---------- targets and backlogs ----------

    suspend fun addTarget(
        goalId: Long?,
        backlogId: Long?,
        name: String,
        unit: String,
        minimum: Double,
        target: Double,
        schedule: String,
        start: LocalDate,
        end: LocalDate?,
    ): Long = db.withTransaction { insertTarget(goalId, null, backlogId, name, unit, minimum, target, schedule, start, end) }

    private suspend fun insertTarget(
        goalId: Long?,
        planId: Long?,
        backlogId: Long?,
        name: String,
        unit: String,
        minimum: Double,
        target: Double,
        schedule: String,
        start: LocalDate,
        end: LocalDate?,
    ): Long {
        val n = name.trim()
        if (n.isEmpty()) throw Refused("a target needs a name")
        Values.requireTargetLevels(minimum, target)
        if (!Planner.isSchedule(schedule)) throw Refused("bad schedule")
        if (end != null && end.isBefore(start)) throw Refused("end before start")
        return dao.insertTarget(
            TargetRow(
                goalId = goalId, planId = planId, backlogId = backlogId, name = n, unit = unit.trim(),
                minimum = minimum, target = target, schedule = schedule.trim(), startDay = start.toString(),
                endDay = end?.toString(),
            ),
        )
    }

    /** Pause, resume or end a target. Ending sets its last day to today; its check-ins stay forever. */
    suspend fun setTargetStatus(id: Long, status: String, today: LocalDate) = db.withTransaction {
        Values.requireValid("target.status", status)
        val t = dao.target(id) ?: throw Refused("target gone")
        val end = if (status == "ended" && (t.endDay == null || LocalDate.parse(t.endDay).isAfter(today))) {
            today.toString()
        } else {
            t.endDay
        }
        dao.updateTarget(t.copy(status = status, endDay = end))
    }

    suspend fun addBacklog(
        goalId: Long?,
        name: String,
        total: Double,
        unit: String,
        startDone: Double,
        deadline: LocalDate,
        now: Long,
    ): Long = db.withTransaction { insertBacklog(goalId, name, total, unit, startDone, deadline, now) }

    private suspend fun insertBacklog(
        goalId: Long?,
        name: String,
        total: Double,
        unit: String,
        startDone: Double,
        deadline: LocalDate,
        now: Long,
    ): Long {
        val n = name.trim()
        if (n.isEmpty() || unit.isBlank()) throw Refused("a backlog needs a name and a unit")
        Values.requireBacklogAmounts(total, startDone)
        return dao.insertBacklog(
            BacklogRow(
                goalId = goalId, name = n, total = total, unit = unit.trim(), startDone = startDone,
                deadline = deadline.toString(), createdAt = now, startDay = today(now).toString(),
            ),
        )
    }

    suspend fun setBacklogStatus(id: Long, status: String) = db.withTransaction {
        Values.requireValid("backlog.status", status)
        val b = dao.backlog(id) ?: throw Refused("backlog gone")
        dao.updateBacklog(b.copy(status = status))
    }

    // ---------- rest days and check-ins ----------

    /** A planned day off for every target: neither done nor missed, and not counted in days left. */
    suspend fun setRestDay(day: LocalDate, rest: Boolean, now: Long) = db.withTransaction {
        dao.deleteRestDay(day.toString())
        if (rest) dao.insertRestDay(RestDayRow(dayKey = day.toString(), createdAt = now))
    }

    /**
     * CheckIn: his answer for one target on one day (today, or a day he missed answering). One row per target
     * per day; answering again replaces it. A reason only goes with Not or Part and is one of the chips.
     */
    suspend fun checkIn(
        targetId: Long,
        day: LocalDate,
        today: LocalDate,
        level: String,
        amount: Double,
        reason: String?,
        note: String?,
        now: Long,
    ) = db.withTransaction {
        Values.requireValid("checkin.level", level)
        Values.requireCheckinAmount(amount)
        if (day.isAfter(today)) throw Refused("a day that has not come yet")
        if (reason != null && (level !in Planner.SHORT_LEVELS || reason !in PromiseText.REASONS)) {
            throw Refused("a reason only goes with Not or Part")
        }
        dao.target(targetId) ?: throw Refused("target gone")
        // Schema 6: his local time and day of answering, stored now so a later change of time zone never moves them.
        val tz = settings.timeZone()
        val at = com.clarity.life.core.TimeFacts.local(Instant.ofEpochMilli(now), tz)
        val enteredDay = Planner.dayKey(Instant.ofEpochMilli(now).toString(), tz, settings.dayBoundaryHour()).toString()
        val row = CheckinRow(
            targetId = targetId,
            dayKey = day.toString(),
            level = level,
            amount = if (level == "rest" || level == "not") 0.0 else amount,
            reason = reason,
            note = note?.trim()?.takeIf { it.isNotEmpty() },
            enteredAt = now,
            enteredMinute = at.hour * 60 + at.minute,
            enteredDay = enteredDay,
        )
        val old = dao.checkin(targetId, day.toString())
        if (old == null) dao.insertCheckin(row) else dao.updateCheckin(row.copy(id = old.id))
    }

    /** Undo an answer: the day goes back to "no answer", which is never a miss. */
    suspend fun clearCheckIn(targetId: Long, day: LocalDate) = db.withTransaction {
        dao.deleteCheckin(targetId, day.toString())
    }

    // ---------- what the coach and the cards read ----------

    /** Active targets, minus those whose last day has passed. */
    suspend fun liveTargets(today: LocalDate): List<TargetRow> =
        dao.activeTargets().filter { t -> t.endDay == null || !LocalDate.parse(t.endDay).isBefore(today) }

    /** The active tracker as PLAN_STATE and CHECKIN_FACTS read it on [today]. */
    suspend fun tracker(today: LocalDate): PlanBlocks.Tracker {
        val targets = liveTargets(today)
        val checkins = if (targets.isEmpty()) emptyList() else dao.checkinsOf(targets.map { it.id })
        val byTarget = checkins.groupBy { it.targetId }
        val tz = settings.timeZone()
        val boundary = settings.dayBoundaryHour()
        val backlogs = dao.activeBacklogs().map { b ->
            if (b.startDay != null) {
                b
            } else {
                // Made before schema 3: its day is worked out once, now, and stored, so it never moves again.
                val day = Planner.dayKey(Instant.ofEpochMilli(b.createdAt).toString(), tz, boundary).toString()
                dao.fillBacklogStartDay(b.id, day)
                b.copy(startDay = day)
            }
        }
        val liveIds = targets.map { it.id }.toSet()
        // A paused or ended target's check-ins still count toward its backlog's done, never toward the pace.
        val offTargets = if (backlogs.isEmpty()) emptyList() else dao.targetsOfBacklogs(backlogs.map { it.id }).filter { it.id !in liveIds }
        val offCheckins = if (offTargets.isEmpty()) emptyMap() else dao.checkinsOf(offTargets.map { it.id }).groupBy { it.targetId }
        return PlanBlocks.Tracker(
            goals = dao.activeGoals().map { PlanBlocks.Goal(it.id, it.title, it.deadline?.let(LocalDate::parse)) },
            targets = targets.map { t ->
                PlanBlocks.Target(
                    t.id, t.name, t.goalId, t.backlogId, t.minimum, t.target, t.schedule,
                    (byTarget[t.id] ?: emptyList()).map { model(it) },
                    LocalDate.parse(t.startDay),
                    t.endDay?.let(LocalDate::parse),
                )
            },
            backlogs = backlogs.map { b ->
                PlanBlocks.Backlog(
                    b.id, b.name, b.goalId, b.total, b.startDone, LocalDate.parse(b.deadline),
                    LocalDate.parse(b.startDay!!),
                    offTargets.filter { it.backlogId == b.id }.flatMap { t -> (offCheckins[t.id] ?: emptyList()).map { model(it) } },
                    (targets + offTargets).filter { it.backlogId == b.id }.minOfOrNull { LocalDate.parse(it.startDay) },
                )
            },
            restDays = dao.restDays().map { LocalDate.parse(it.dayKey) }.toSet(),
            availability = db.memory().profileEntry(AVAILABILITY)?.value?.takeIf { it.isNotBlank() } ?: "not set",
            replanFlag = dao.latestWeeklyReplanFlag() == 1,
        )
    }

    suspend fun oldTargets(): List<PlanApply.OldTarget> = dao.activeTargets().map { model(it) }

    suspend fun oldBacklogs(): List<PlanApply.OldBacklog> = dao.activeBacklogs().map { model(it) }

    suspend fun activeGoalIds(): Set<Long> = dao.activeGoals().map { it.id }.toSet()

    // ---------- applying a card he tapped ----------

    /**
     * Save plan: the steps the card showed, in one transaction (the caller's). A new plan version replaces the
     * goal's active one; existing rows are only changed where the card showed a change. Returns the plan id.
     */
    suspend fun applyPlan(
        steps: PlanApply.Steps,
        sourceConversationId: Long?,
        now: Long,
    ): Long {
        val review = steps.review ?: throw Refused("no review day")
        val goalId = steps.goalId ?: run {
            val title = steps.newGoalTitle ?: throw Refused("no goal")
            val id = dao.insertGoal(GoalRow(title = title.trim(), createdAt = now))
            dao.insertRevision(GoalRevisionRow(goalId = id, text = title.trim(), reason = "created from a plan", createdAt = now))
            id
        }
        dao.replaceActivePlans(goalId)
        val planId = dao.insertPlan(
            PlanRow(
                goalId = goalId,
                version = dao.lastPlanVersion(goalId) + 1,
                rationale = steps.rationale,
                reviewDay = review.toString(),
                sourceConversationId = sourceConversationId,
                createdAt = now,
            ),
        )
        val backlogIds = steps.backlogs.map { b -> applyBacklog(b, goalId, now) }
        for (t in steps.targets) when (t) {
            is PlanApply.TargetStep.New -> insertTarget(
                goalId, planId, t.backlog?.let { backlogIds[it] }, t.name, t.unit, t.minimum, t.target,
                t.schedule, t.start, t.end,
            )
            is PlanApply.TargetStep.Change -> applyTargetChange(t, planId, t.backlog?.let { backlogIds[it] })
        }
        return planId
    }

    /** A target change he tapped. Only the shown fields change; the levels are checked together. */
    suspend fun applyTargetChange(step: PlanApply.TargetStep.Change, planId: Long? = null, backlogId: Long? = null): Long {
        val t = dao.target(step.old.id) ?: throw Refused("target gone")
        if (t.status == "ended") throw Refused("target ended")
        val changed = t.copy(
            minimum = step.minimum ?: t.minimum,
            target = step.target ?: t.target,
            schedule = step.schedule ?: t.schedule,
            endDay = step.end?.toString() ?: t.endDay,
            planId = planId ?: t.planId,
            backlogId = backlogId ?: t.backlogId,
        )
        Values.requireTargetLevels(changed.minimum, changed.target)
        if (!Planner.isSchedule(changed.schedule)) throw Refused("bad schedule")
        if (changed.endDay != null && changed.endDay < changed.startDay) throw Refused("end before start")
        dao.updateTarget(changed)
        return t.id
    }

    /** A backlog he tapped: new (under [goalId]), changed, or kept as it is. Returns its id. */
    suspend fun applyBacklog(step: PlanApply.BacklogStep, goalId: Long?, now: Long): Long = when (step) {
        is PlanApply.BacklogStep.New -> insertBacklog(goalId, step.name, step.total, step.unit, 0.0, step.deadline, now)
        is PlanApply.BacklogStep.Keep -> step.old.id
        is PlanApply.BacklogStep.Change -> {
            val b = dao.backlog(step.old.id) ?: throw Refused("backlog gone")
            val changed = b.copy(
                total = step.total ?: b.total,
                deadline = step.deadline?.toString() ?: b.deadline,
            )
            Values.requireBacklogAmounts(changed.total, changed.startDone)
            dao.updateBacklog(changed)
            b.id
        }
    }

    companion object {
        /** The profile entry that holds his usual hours per weekday (spec section 6.1). */
        const val AVAILABILITY = "availability"

        fun model(c: CheckinRow) = CheckIn(
            LocalDate.parse(c.dayKey), c.level, c.amount, c.reason,
            minute = c.enteredMinute?.takeIf { c.enteredDay == c.dayKey },
        )

        fun model(t: TargetRow) = PlanApply.OldTarget(
            t.id, t.goalId, t.name, t.unit, t.minimum, t.target, t.schedule, t.endDay?.let(LocalDate::parse),
        )

        fun model(b: BacklogRow) = PlanApply.OldBacklog(
            b.id, b.goalId, b.name, b.unit, b.total, b.startDone, LocalDate.parse(b.deadline),
        )
    }
}
