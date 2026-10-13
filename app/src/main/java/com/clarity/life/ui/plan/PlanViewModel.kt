package com.clarity.life.ui.plan

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clarity.life.core.PlanBlocks
import com.clarity.life.core.PlanDates
import com.clarity.life.core.PlanStateText
import com.clarity.life.core.Planner
import com.clarity.life.core.PromiseText
import com.clarity.life.core.Schedule
import com.clarity.life.core.TimeFacts
import com.clarity.life.data.entity.BacklogRow
import com.clarity.life.data.entity.CheckinRow
import com.clarity.life.data.entity.GoalRow
import com.clarity.life.data.entity.PlanRow
import com.clarity.life.data.entity.TargetRow
import com.clarity.life.planner.PlannerRepository
import com.clarity.life.settings.DefaultSettings
import com.clarity.life.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Plan, Today, Goals and Backlogs (docs/mockups/Today.dc.html and Goal.dc.html). This class only reads and
 * works out the labels in code ([Planner], [PlanBlocks], [PlanStateText]); every button is one command on
 * [PlannerRepository], so every change is his tap.
 */
@HiltViewModel
class PlanViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val planner: PlannerRepository,
    private val settings: SettingsRepository,
    private val requests: com.clarity.life.chat.TalkRequests,
) : ViewModel() {

    data class TodayRow(val target: TargetRow, val answer: CheckinRow?) {
        val quantity: Boolean get() = target.unit.isNotBlank()
    }

    data class BacklogView(
        val row: BacklogRow,
        val facts: PlanBlocks.BacklogFacts,
        val linked: List<TargetRow>,
    ) {
        val remaining: Double get() = row.total - facts.done
    }

    data class TargetView(val row: TargetRow, val done: Int, val answered: Int, val reasons: List<String>)

    data class GoalView(
        val row: GoalRow,
        val status: String,
        val plan: PlanRow?,
        val backlogs: List<BacklogView>,
        val targets: List<TargetView>,
    )

    data class State(
        val ready: Boolean = false,
        val today: LocalDate = LocalDate.of(2000, 1, 1),
        val todayLabel: String = "",
        val restToday: Boolean = false,
        val todayRows: List<TodayRow> = emptyList(),
        val goals: List<GoalView> = emptyList(),
        /** Backlogs and targets that belong to no active goal. */
        val looseBacklogs: List<BacklogView> = emptyList(),
        val looseTargets: List<TargetView> = emptyList(),
        val oldGoals: List<GoalRow> = emptyList(),
        val quietLine: String? = null,
    ) {
        val answeredToday: Int get() = todayRows.count { it.answer != null }
    }

    private val zone = MutableStateFlow(java.util.TimeZone.getDefault().id to DefaultSettings.DAY_BOUNDARY_HOUR)
    private val quietLine = MutableStateFlow<String?>(null)

    /** The day changes at the day boundary, so the lists are worked out again every minute. */
    private val minute = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(60_000)
        }
    }

    private data class Rows(
        val goals: List<GoalRow>,
        val targets: List<TargetRow>,
        val backlogs: List<BacklogRow>,
        val plans: List<PlanRow>,
        val rest: Set<LocalDate>,
    )

    private val rows = combine(planner.goals, planner.targets, planner.backlogs, planner.plans, planner.restDays) { g, t, b, p, r ->
        Rows(g, t, b, p, r.map { LocalDate.parse(it.dayKey) }.toSet())
    }

    val state: StateFlow<State> = combine(
        rows,
        planner.checkinsFrom(LocalDate.of(2000, 1, 1)),
        zone,
        minute,
        quietLine,
    ) { r, checkins, (tz, boundary), now, line ->
        build(r, checkins, tz, boundary, now, line)
    }.flowOn(kotlinx.coroutines.Dispatchers.Default) // the whole history is read; never on the main thread
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), State())

    init {
        viewModelScope.launch { zone.value = settings.timeZone() to settings.dayBoundaryHour() }
    }

    private fun build(r: Rows, checkins: List<CheckinRow>, tz: String, boundary: Int, now: Long, line: String?): State {
        val today = Planner.dayKey(Instant.ofEpochMilli(now).toString(), tz, boundary)
        val byTarget = checkins.groupBy { it.targetId }
        val live = r.targets.filter { t ->
            t.status == "active" && (t.endDay == null || !LocalDate.parse(t.endDay).isBefore(today))
        }
        fun history(t: TargetRow) = (byTarget[t.id] ?: emptyList()).map { PlannerRepository.model(it) }

        // Paused targets stay on their goal (with Resume); only live ones are on Today and count for pace.
        val shown = live + r.targets.filter { it.status == "paused" }
        val todayRows = live.filter { t ->
            !LocalDate.parse(t.startDay).isAfter(today) && scheduledOn(t.schedule, today)
        }.map { t -> TodayRow(t, byTarget[t.id]?.firstOrNull { it.dayKey == today.toString() }) }

        fun targetView(t: TargetRow): TargetView {
            val h = history(t)
            val w = Planner.typicalPace(h, t.schedule, today, r.rest, LocalDate.parse(t.startDay))
            val first = today.minusDays(Planner.WINDOW.toLong())
            val short = h.filter { !it.day.isBefore(first) && it.day.isBefore(today) && it.level in Planner.SHORT_LEVELS }
            val reasons = short.mapNotNull { it.reason }.groupingBy { it }.eachCount()
                .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .take(3).map { it.key to it.value }
            return TargetView(t, w.done, w.answered, Planner.reasonLines(reasons, short.size))
        }

        fun backlogView(b: BacklogRow): BacklogView {
            val linked = live.filter { it.backlogId == b.id }
            val model = PlanBlocks.Backlog(
                b.id, b.name, b.goalId, b.total, b.startDone, LocalDate.parse(b.deadline),
                Planner.dayKey(Instant.ofEpochMilli(b.createdAt).toString(), tz, boundary),
                r.targets.filter { it.backlogId == b.id && it !in live }.flatMap(::history),
                r.targets.filter { it.backlogId == b.id }.minOfOrNull { LocalDate.parse(it.startDay) },
            )
            val facts = PlanBlocks.backlogFacts(
                model,
                linked.map { PlanBlocks.Target(it.id, it.name, it.goalId, it.backlogId, it.minimum, it.target, it.schedule, history(it), LocalDate.parse(it.startDay)) },
                today,
                r.rest,
            )
            return BacklogView(b, facts, linked)
        }

        val activeBacklogs = r.backlogs.filter { it.status == "active" }
        val activeGoals = r.goals.filter { it.status == "active" }
        val goalIds = activeGoals.map { it.id }.toSet()
        val goals = activeGoals.map { g ->
            val bs = activeBacklogs.filter { it.goalId == g.id }.map(::backlogView)
            val ts = shown.filter { it.goalId == g.id }.map(::targetView)
            GoalView(
                row = g,
                status = goalStatus(bs, ts),
                plan = r.plans.firstOrNull { it.goalId == g.id && it.status == "active" },
                backlogs = bs,
                targets = ts,
            )
        }
        return State(
            ready = true,
            today = today,
            todayLabel = "${DAY_NAMES[today.dayOfWeek.value - 1]} ${TimeFacts.shortDay(today).substringAfter(' ')}",
            restToday = today in r.rest,
            todayRows = todayRows,
            goals = goals,
            looseBacklogs = activeBacklogs.filter { it.goalId == null || it.goalId !in goalIds }.map(::backlogView),
            looseTargets = shown.filter { it.goalId == null || it.goalId !in goalIds }.map(::targetView),
            oldGoals = r.goals.filter { it.status != "active" },
            quietLine = line,
        )
    }

    private fun scheduledOn(schedule: String, day: LocalDate): Boolean = try {
        when (val s = Planner.parseSchedule(schedule)) {
            is Schedule.Days -> (day.dayOfWeek.value - 1) in s.weekdays
            is Schedule.Count -> true
        }
    } catch (e: Exception) {
        false
    }

    // ---------- labels, all from code ----------

    val reasons: List<String> get() = PromiseText.REASONS

    fun n(x: Double): String = PlanStateText.g(x)

    fun perDayUp(x: Double): String = PlanStateText.numUp(x)

    fun perDay(x: Double): String = PlanStateText.num(x)

    fun day(d: String): String = TimeFacts.shortDay(LocalDate.parse(d))

    fun levelsLine(t: TargetRow): String {
        val u = if (t.unit.isBlank()) "" else " ${t.unit}"
        val target = "target ${n(t.target)}$u"
        return if (t.minimum > 0 && t.minimum < t.target) "min ${n(t.minimum)} · $target" else target
    }

    fun statusText(p: com.clarity.life.core.Projection): String = when (p.status) {
        "done" -> "Complete."
        "past_deadline" -> "The deadline has passed."
        "no_data" -> "Not enough data yet: fewer than ${Planner.MIN_ANSWERED} answered days in the last ${Planner.WINDOW} days."
        else -> {
            val key = if (p.status != "short" || p.bestPerDay != null) p.status else "short_no_best"
            PlanStateText.STATUS_TEXT.getValue(key).removePrefix("Status: ").replaceFirstChar { it.uppercase() }
        }
    }

    /** A date he typed, read by the same code as a card's dates; null when it cannot be read. */
    fun readDay(text: String, today: LocalDate): LocalDate? = if (text.isBlank()) null else PlanDates.readDay(text, today)

    fun showDay(d: LocalDate): String = TimeFacts.shortDay(d)

    // ---------- commands, each his tap ----------

    /** Did it, Part or Not. For an amount target the amount decides the level; Rest is never derived. */
    fun answer(row: TodayRow, level: String, today: LocalDate) = act {
        val t = row.target
        val amount = when (level) {
            "target" -> t.target
            "minimum" -> t.minimum
            else -> 0.0
        }
        planner.checkIn(t.id, today, today, level, amount, null, null, System.currentTimeMillis())
    }

    /** The amount stepper: the level follows from the amount, in code. */
    fun setAmount(row: TodayRow, amount: Double, today: LocalDate) = act {
        val t = row.target
        val a = amount.coerceAtLeast(0.0)
        // a minus on a day with no answer yet must not turn "no answer" into "Not"
        if (row.answer == null && a == 0.0) return@act
        val level = Planner.levelFor(a, t.minimum, t.target)
        val reason = row.answer?.reason?.takeIf { level in Planner.SHORT_LEVELS }
        planner.checkIn(t.id, today, today, level, a, reason, row.answer?.note, System.currentTimeMillis())
    }

    fun reason(row: TodayRow, reason: String?, today: LocalDate) = act {
        val a = row.answer ?: return@act
        planner.checkIn(row.target.id, today, today, a.level, a.amount, reason, a.note, System.currentTimeMillis())
    }

    fun note(row: TodayRow, note: String, today: LocalDate) = act {
        val a = row.answer ?: return@act
        planner.checkIn(row.target.id, today, today, a.level, a.amount, a.reason, note, System.currentTimeMillis())
    }

    fun clear(row: TodayRow, today: LocalDate) = act { planner.clearCheckIn(row.target.id, today) }

    fun setRest(today: LocalDate, rest: Boolean) = act { planner.setRestDay(today, rest, System.currentTimeMillis()) }

    fun addGoal(title: String, area: String, deadline: LocalDate?) = act {
        planner.createGoal(title, area, "custom", deadline, System.currentTimeMillis())
    }

    fun setGoalStatus(id: Long, status: String) = act { planner.setGoalStatus(id, status) }

    fun addTarget(
        goalId: Long?,
        backlogId: Long?,
        name: String,
        unit: String,
        minimum: Double,
        target: Double,
        schedule: String,
        today: LocalDate,
        end: LocalDate?,
    ) = act { planner.addTarget(goalId, backlogId, name, unit, minimum, target, schedule, today, end) }

    fun setTargetStatus(id: Long, status: String, today: LocalDate) = act { planner.setTargetStatus(id, status, today) }

    fun addBacklog(goalId: Long?, name: String, total: Double, unit: String, startDone: Double, deadline: LocalDate) = act {
        planner.addBacklog(goalId, name, total, unit, startDone, deadline, System.currentTimeMillis())
    }

    fun setBacklogStatus(id: Long, status: String) = act { planner.setBacklogStatus(id, status) }

    /** Plan with coach: Talk opens a new Plan and track conversation, which sees the whole tracker. */
    fun planWithCoach() = requests.startConversation("plan")

    fun say(line: String?) {
        quietLine.value = line
    }

    private fun act(block: suspend () -> Unit) {
        quietLine.value = null
        viewModelScope.launch {
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                quietLine.value = "That was not saved: ${e.message}."
            } catch (e: Throwable) {
                com.clarity.life.CrashLog.record(context, e)
                quietLine.value = "That did not save (${e.javaClass.simpleName}). Details are in Settings, at the bottom."
            }
        }
    }

    companion object {
        private val DAY_NAMES = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

        /** On pace, behind, or no data yet: read from the backlogs' Status lines, never judged by the model. */
        fun goalStatus(backlogs: List<BacklogView>, targets: List<TargetView>): String {
            val statuses = backlogs.map { it.facts.projection.status }
            return when {
                statuses.any { it == "short" || it == "needs_best" || it == "past_deadline" } -> "behind"
                statuses.isNotEmpty() && statuses.all { it == "ok" || it == "done" } -> "on pace"
                statuses.isEmpty() && targets.any { it.answered >= Planner.MIN_ANSWERED } -> "tracking"
                else -> "no data yet"
            }
        }
    }
}
