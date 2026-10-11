package com.clarity.life.core

import java.time.LocalDate

/**
 * What tapping a plan, target_change or backlog card would write, worked out before he taps. The card shows
 * [Steps.lines]; the save runs the very same [Steps], so what he saw is exactly what is written.
 *
 * Rules (each pinned by PlanApplyTest):
 * - A plan for an existing goal reuses that goal's active target or backlog with the same name (ignoring case)
 *   instead of making a duplicate: a target keeps its history (and so its pace), and only the fields that differ
 *   are changed. Nothing he already has is ended or removed by a plan; a target left out of it stays as it is.
 * - A target is linked to a backlog of the same plan with the same name; failing that, when the plan has one
 *   backlog and one target with the same unit, they are linked.
 * - A minimum above the target is refused, also when only one of the two changes (checked against the stored
 *   value), as is a card that would change nothing.
 *
 * Pure Kotlin. Ids here are database ids.
 */
object PlanApply {

    data class OldTarget(
        val id: Long,
        val goalId: Long?,
        val name: String,
        val unit: String,
        val minimum: Double,
        val target: Double,
        val schedule: String,
        val endDay: LocalDate?,
    )

    data class OldBacklog(
        val id: Long,
        val goalId: Long?,
        val name: String,
        val unit: String,
        val total: Double,
        val startDone: Double,
        val deadline: LocalDate,
    )

    sealed interface BacklogStep {
        val name: String
        data class New(override val name: String, val total: Double, val unit: String, val deadline: LocalDate) : BacklogStep
        data class Change(
            val old: OldBacklog,
            val total: Double?,
            val deadline: LocalDate?,
        ) : BacklogStep {
            override val name get() = old.name
        }
        data class Keep(val old: OldBacklog) : BacklogStep {
            override val name get() = old.name
        }
    }

    sealed interface TargetStep {
        data class New(
            val name: String,
            val unit: String,
            val minimum: Double,
            val target: Double,
            val schedule: String,
            val start: LocalDate,
            val end: LocalDate?,
            /** Index into [Steps.backlogs], or null. */
            val backlog: Int?,
        ) : TargetStep
        data class Change(
            val old: OldTarget,
            val minimum: Double? = null,
            val target: Double? = null,
            val schedule: String? = null,
            val end: LocalDate? = null,
            /** Index into [Steps.backlogs] to link to, or null to leave the link as it is. */
            val backlog: Int? = null,
        ) : TargetStep {
            val changesSomething get() = minimum != null || target != null || schedule != null || end != null
        }
    }

    data class Steps(
        /** Null: a new goal with [newGoalTitle]. */
        val goalId: Long?,
        val newGoalTitle: String?,
        val rationale: String,
        val review: LocalDate?,
        val backlogs: List<BacklogStep>,
        val targets: List<TargetStep>,
    ) {
        /** What the card shows before he taps, in his words, with every date and number it will save. */
        fun lines(): List<String> = buildList {
            if (goalId == null && newGoalTitle != null) add("New goal: $newGoalTitle")
            for (b in backlogs) when (b) {
                is BacklogStep.New -> add("New backlog: ${b.name}, ${n(b.total)} ${b.unit} by ${day(b.deadline)}")
                is BacklogStep.Change -> add(
                    "B${b.old.id} ${b.old.name}: " + listOfNotNull(
                        b.total?.let { "total ${n(b.old.total)} → ${n(it)}" },
                        b.deadline?.let { "deadline ${day(b.old.deadline)} → ${day(it)}" },
                    ).joinToString(", "),
                )
                is BacklogStep.Keep -> add("Uses B${b.old.id} ${b.old.name}")
            }
            for (t in targets) when (t) {
                is TargetStep.New -> add(
                    "New target: ${t.name}, ${levels(t.minimum, t.target, t.unit)}, ${t.schedule}, from ${day(t.start)}" +
                        (t.end?.let { " to ${day(it)}" } ?: ""),
                )
                is TargetStep.Change -> add(changeLine(t))
            }
            review?.let { add("Review on ${day(it)}") }
        }
    }

    class Refused(val why: String) : Exception(why)

    fun n(x: Double): String = PlanStateText.g(x)

    fun day(d: LocalDate): String = TimeFacts.shortDay(d)

    private fun levels(min: Double, target: Double, unit: String): String {
        val u = if (unit.isBlank()) "" else " $unit"
        return if (min > 0 && min < target) "minimum ${n(min)}, target ${n(target)}$u" else "target ${n(target)}$u"
    }

    fun changeLine(t: TargetStep.Change): String = "T${t.old.id} ${t.old.name}: " + listOfNotNull(
        t.minimum?.let { "minimum ${n(t.old.minimum)} → ${n(it)}" },
        t.target?.let { "target ${n(t.old.target)} → ${n(it)}" },
        t.schedule?.let { "${t.old.schedule} → $it" },
        t.end?.let { "ends ${day(it)}" },
    ).ifEmpty { listOf("kept as it is") }.joinToString(", ")

    private fun same(a: String, b: String) = a.trim().equals(b.trim(), ignoreCase = true)

    private fun id(s: String?): Long? = s?.drop(1)?.toLongOrNull()

    /**
     * A plan card. [goals] are his active goal ids, [targets] and [backlogs] the active rows. Throws [Refused]
     * when the card names something that is no longer there or asks for an impossible change.
     */
    fun plan(
        card: Card,
        days: PlanDates.CardDays,
        goals: Set<Long>,
        targets: List<OldTarget>,
        backlogs: List<OldBacklog>,
    ): Steps {
        val goalId = id(card.str("goal"))
        if (goalId != null && goalId !in goals) throw Refused("goal gone")
        val mine = { owner: Long? -> goalId != null && owner == goalId }

        val bItems = card.list("backlog").orEmpty().map { it.asObj!!.map }
        val bSteps = bItems.mapIndexed { i, b ->
            val name = b.getValue("name").asStr!!
            val total = b.getValue("total").asNum!!
            val deadline = days.deadlines[i]
            val old = backlogs.firstOrNull { mine(it.goalId) && same(it.name, name) }
            when {
                old == null -> BacklogStep.New(name, total, b.getValue("unit").asStr!!, deadline)
                old.total == total && old.deadline == deadline -> BacklogStep.Keep(old)
                else -> {
                    if (total < old.startDone) throw Refused("total below done")
                    BacklogStep.Change(old, total.takeIf { it != old.total }, deadline.takeIf { it != old.deadline })
                }
            }
        }

        val tItems = card.list("targets").orEmpty().map { it.asObj!!.map }
        val tSteps = tItems.mapIndexed { i, t ->
            val name = t.getValue("name").asStr!!
            val unit = t["unit"]?.asStr.orEmpty()
            val target = t.getValue("target").asNum!!
            val min = t["min"]?.asNum ?: 0.0
            val schedule = t.getValue("schedule").asStr!!
            val (start, end) = days.targets[i]
            var link = bSteps.indexOfFirst { same(it.name, name) }.takeIf { it >= 0 }
            if (link == null && bSteps.size == 1 && tItems.size == 1) {
                val bUnit = when (val b = bSteps[0]) {
                    is BacklogStep.New -> b.unit
                    is BacklogStep.Change -> b.old.unit
                    is BacklogStep.Keep -> b.old.unit
                }
                if (same(bUnit, unit)) link = 0
            }
            val old = targets.firstOrNull { mine(it.goalId) && same(it.name, name) }
            if (old == null) {
                TargetStep.New(name, unit, min, target, schedule, start, end, link)
            } else {
                TargetStep.Change(
                    old = old,
                    minimum = min.takeIf { it != old.minimum },
                    target = target.takeIf { it != old.target },
                    schedule = schedule.takeIf { it != old.schedule },
                    end = end?.takeIf { it != old.endDay },
                    backlog = link,
                )
            }
        }
        return Steps(
            goalId = goalId,
            newGoalTitle = if (goalId == null) card.str("title") else null,
            rationale = card.str("rationale")!!,
            review = days.review,
            backlogs = bSteps,
            targets = tSteps,
        )
    }

    /** A target_change card against the stored target. */
    fun targetChange(card: Card, days: PlanDates.CardDays, targets: List<OldTarget>): TargetStep.Change {
        val old = targets.firstOrNull { it.id == id(card.str("target")) } ?: throw Refused("target gone")
        val step = TargetStep.Change(
            old = old,
            minimum = card.num("min")?.takeIf { it != old.minimum },
            target = card.num("target_value")?.takeIf { it != old.target },
            schedule = card.str("schedule")?.takeIf { it != old.schedule },
            end = days.end?.takeIf { it != old.endDay },
        )
        if ((step.minimum ?: old.minimum) > (step.target ?: old.target)) throw Refused("min above target")
        if (!step.changesSomething) throw Refused("changes nothing")
        return step
    }

    /** A backlog card: a new backlog (to [goalId], when the conversation is about one), or a change to one. */
    fun backlog(card: Card, days: PlanDates.CardDays, backlogs: List<OldBacklog>): BacklogStep {
        val bid = id(card.str("backlog"))
        if (bid == null) {
            return BacklogStep.New(card.str("name")!!, card.num("total")!!, card.str("unit")!!, days.deadline!!)
        }
        val old = backlogs.firstOrNull { it.id == bid } ?: throw Refused("backlog gone")
        val total = card.num("total")?.takeIf { it != old.total }
        val deadline = days.deadline?.takeIf { it != old.deadline }
        if (total != null && total < old.startDone) throw Refused("total below done")
        if (total == null && deadline == null) throw Refused("changes nothing")
        return BacklogStep.Change(old, total, deadline)
    }
}
