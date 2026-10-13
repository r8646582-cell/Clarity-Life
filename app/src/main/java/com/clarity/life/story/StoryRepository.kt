package com.clarity.life.story

import com.clarity.life.core.PlanStateText
import com.clarity.life.core.PlanStep
import com.clarity.life.core.Planner
import com.clarity.life.core.Story
import com.clarity.life.core.TimeFacts
import com.clarity.life.data.ClarityDatabase
import com.clarity.life.data.entity.FocusRow
import com.clarity.life.planner.PlannerRepository
import com.clarity.life.settings.SettingsRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Your story (spec 2.5): reads the raw rows and hands them to [Story], so every number the page and the story
 * job show is counted in code. Shared by the story page and [com.clarity.life.writing.StoryWriter], so the
 * page and the facts the AI is given always agree.
 */
@Singleton
class StoryRepository @Inject constructor(
    private val db: ClarityDatabase,
    private val settings: SettingsRepository,
    private val planner: PlannerRepository,
) {
    private val dao get() = db.story()

    /** One backlog's chart: its bars and plan line, what is done, and the day it was done if it was. */
    data class GoalChart(
        val backlogId: Long,
        val name: String,
        val unit: String,
        val total: Double,
        val startDone: Double,
        val start: LocalDate,
        val amounts: List<Story.Amount>,
        val steps: List<PlanStep>,
        val finishedOn: LocalDate?,
        val series: Story.Series,
    ) {
        val done: Double get() = startDone + amounts.sumOf { it.amount }

        fun doneWithin(from: LocalDate, to: LocalDate): Double =
            amounts.filter { !it.day.isBefore(from) && !it.day.isAfter(to) }.sumOf { it.amount }
    }

    /** A focus he marked done, with the steps he kept in it. */
    data class DoneFocus(val focus: FocusRow, val day: LocalDate, val stepsKept: List<String>)

    data class Facts(
        val first: LocalDate,
        val today: LocalDate,
        val charts: List<GoalChart>,
        val doneFocuses: List<DoneFocus>,
    )

    data class Counts(val promisesKept: Int, val promisesAnswered: Int, val overcome: Int, val checkinDays: Int)

    suspend fun today(now: Long): LocalDate = planner.today(now)

    private suspend fun dayOf(at: Long): LocalDate =
        Planner.dayKey(Instant.ofEpochMilli(at).toString(), settings.timeZone(), settings.dayBoundaryHour())

    /** The first instant of a day key: the day boundary hour that morning, in his time zone. */
    private suspend fun startOf(day: LocalDate): Long =
        day.atTime(settings.dayBoundaryHour(), 0).atZone(ZoneId.of(settings.timeZone())).toInstant().toEpochMilli()

    /** The day his story began: his first conversation, check-in or backlog. Null before any of them. */
    suspend fun first(): LocalDate? {
        val days = listOfNotNull(
            dao.firstConversationAt()?.let { dayOf(it) },
            dao.firstCheckinDay()?.let(LocalDate::parse),
            db.planner().firstTargetDay()?.let(LocalDate::parse),
        )
        return days.minOrNull()
    }

    suspend fun facts(now: Long): Facts? {
        val first = first() ?: return null
        val today = maxOf(today(now), first)
        val rest = db.planner().restDays().map { LocalDate.parse(it.dayKey) }.toSet()
        val backlogs = dao.storyBacklogs()
        val targets = if (backlogs.isEmpty()) emptyList() else db.planner().targetsOfBacklogs(backlogs.map { it.id })
        val checkins = if (targets.isEmpty()) emptyMap() else db.planner().checkinsOf(targets.map { it.id }).groupBy { it.targetId }
        val charts = backlogs.mapNotNull { b ->
            val linked = targets.filter { it.backlogId == b.id }
            val made = b.startDay?.let(LocalDate::parse) ?: dayOf(b.createdAt)
            // Week one is the plan's first week: the first step's start, when there is one.
            val start = linked.minOfOrNull { LocalDate.parse(it.startDay) } ?: made
            if (start.isAfter(today)) return@mapNotNull null
            val amounts = linked.flatMap { checkins[it.id] ?: emptyList() }
                .filter { !LocalDate.parse(it.dayKey).isBefore(made) }
                .groupBy { it.dayKey }
                .map { (day, rows) -> Story.Amount(LocalDate.parse(day), rows.sumOf { it.amount }) }
                .sortedBy { it.day }
            val steps = linked.map { PlanStep(it.target, it.schedule, LocalDate.parse(it.startDay), it.endDay?.let(LocalDate::parse)) }
            val finished = Story.finishedOn(b.total, b.startDone, amounts)
            val last = finished?.let { minOf(it, today) } ?: today
            GoalChart(
                b.id, b.name, b.unit, b.total, b.startDone, start, amounts, steps, finished,
                Story.series(start, last, steps, amounts, rest),
            )
        }
        val focuses = dao.doneFocuses().map { DoneFocus(it, dayOf(it.updatedAt), dao.focusStepsKeptText(it.id)) }
        return Facts(first, today, charts, focuses)
    }

    /** The counts of a view: promises kept and answered, things overcome, and days with a check-in. */
    suspend fun counts(facts: Facts, from: LocalDate, to: LocalDate): Counts {
        val a = startOf(from)
        val b = startOf(to.plusDays(1))
        return Counts(
            promisesKept = dao.promisesKept(a, b),
            promisesAnswered = dao.promisesAnswered(a, b),
            overcome = facts.doneFocuses.count { !it.day.isBefore(from) && !it.day.isAfter(to) },
            checkinDays = dao.checkinDays(from.toString(), to.toString()),
        )
    }

    /**
     * THE STORY'S FACTS for the story job: the same numbers the page shows, written out, so the AI copies
     * them and NumberCheck can hold every figure it writes to these lines.
     */
    suspend fun factsText(f: Facts): String {
        val n = PlanStateText::g
        val counts = counts(f, f.first, f.today)
        val lines = mutableListOf(
            "The story began ${day(f.first)}. Today is ${day(f.today)}. It is ${Story.length(f.first, f.today)} long.",
        )
        for (c in f.charts) {
            val doneLine = if (c.finishedOn != null) {
                "${n(c.total)} of ${n(c.total)} ${c.unit} done, finished on ${day(c.finishedOn)}."
            } else {
                "${n(c.done)} of ${n(c.total)} ${c.unit} done so far."
            }
            lines.add("${c.name}: $doneLine")
            val full = c.series.bars.filter { it.planned > 0 && (it.end != f.today || c.finishedOn != null) }
            full.minByOrNull { it.done / it.planned }?.let {
                lines.add(
                    "${c.name}, lightest ${c.series.by} against the plan: ${day(it.start)} to ${day(it.end)}, " +
                        "${n(it.done)} done, the plan asked for ${n(it.planned)}.",
                )
            }
            c.series.bars.maxByOrNull { it.done }?.takeIf { it.done > 0 }?.let {
                lines.add("${c.name}, best ${c.series.by}: ${day(it.start)} to ${day(it.end)}, ${n(it.done)} done.")
            }
        }
        lines.add("Promises: ${counts.promisesKept} kept of ${counts.promisesAnswered} answered.")
        lines.add("Days with a check-in answered: ${counts.checkinDays}.")
        if (f.doneFocuses.isEmpty()) {
            lines.add("Focuses done: none yet.")
        } else {
            for (d in f.doneFocuses) {
                val steps = if (d.stepsKept.isEmpty()) "" else ": " + d.stepsKept.joinToString("; ")
                lines.add("Focus done on ${day(d.day)}: ${d.focus.name} (${d.stepsKept.size} steps kept$steps).")
            }
        }
        return lines.joinToString("\n")
    }

    private fun day(d: LocalDate): String = "${TimeFacts.shortDay(d)} ${d.year}"
}
