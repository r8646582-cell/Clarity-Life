package com.clarity.life.ui.plan

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clarity.life.core.PlanBlocks
import com.clarity.life.core.PlanDates
import com.clarity.life.core.PlanLoad
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
import com.clarity.life.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Plan, Today, Goals and Backlogs (docs/mockups/Today.dc.html and Goal.dc.html). This class only reads and
 * works out the labels in code ([Planner], [PlanBlocks], [PlanStateText]); every button is one command on
 * [PlannerRepository], so every change is his tap.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlanViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val planner: PlannerRepository,
    private val settings: SettingsRepository,
    private val requests: com.clarity.life.chat.TalkRequests,
) : ViewModel() {

    data class TodayRow(
        val target: TargetRow,
        val answer: CheckinRow?,
        /** The backlog this target counts toward, so an answer can show what it means for the plan. */
        val backlog: BacklogView? = null,
        /** Which step of its ramp this is (1-based) and how many steps there are; 1 of 1 for a plain target. */
        val step: Int = 1,
        val steps: Int = 1,
    ) {
        val quantity: Boolean get() = target.unit.isNotBlank()
    }

    /** One day in the last-seven-days strip: its level when answered, null for no answer, "off" when not scheduled. */
    data class DayMark(val day: LocalDate, val level: String?)

    data class BacklogView(
        val row: BacklogRow,
        val facts: PlanBlocks.BacklogFacts,
        val linked: List<TargetRow>,
        /** The last seven days since tracking began, oldest first, for the week strip. */
        val recent: List<DayMark> = emptyList(),
    ) {
        val remaining: Double get() = row.total - facts.done
    }

    /** A step of a goal's plan: a target in start order, and whether it is done, now, or still to come. */
    data class StepView(val target: TargetRow, val start: LocalDate, val end: LocalDate?, val phase: String)

    data class TargetView(val row: TargetRow, val done: Int, val answered: Int, val reasons: List<String>)

    data class GoalView(
        val row: GoalRow,
        val status: String,
        val plan: PlanRow?,
        val backlogs: List<BacklogView>,
        val targets: List<TargetView>,
        /** Its targets as plan steps, ended ones included, in start order. */
        val steps: List<StepView> = emptyList(),
        /** The first day any of its targets starts; null when it has none. */
        val firstStart: LocalDate? = null,
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
        /** Max plan Phase 6: his minutes for planning per weekday (Monday first), or null when not set. */
        val hours: List<Double>? = null,
        /** The time the live targets of every goal take on each of the next 7 days, counted by [PlanLoad]. */
        val week: PlanLoad.Load? = null,
        /** Target names by id, for the targets the week lists without a time. */
        val names: Map<Long, String> = emptyMap(),
    ) {
        val answeredToday: Int get() = todayRows.count { it.answer != null }
    }

    /**
     * What Talk home and the Plan tab's dot need, and nothing more: the plan's own rows and today's answers, never
     * the check-in history. The home used to wait for the whole tracker (every check-in ever, every pace and
     * backlog worked out) before it appeared, and that grew with every year of use (15 Oct 2026). Worked out by
     * the same functions as [State], so the two always agree.
     */
    data class Home(
        val ready: Boolean = false,
        val today: LocalDate = LocalDate.of(2000, 1, 1),
        /** "Day N of your plan", or null when no active goal has started. */
        val planDay: Long? = null,
        /** An active goal, or a step scheduled today: Talk home offers "Look at my plan". */
        val hasPlan: Boolean = false,
        /** A step scheduled today with no answer yet, and today is not a rest day: the Plan tab's dot. */
        val todayUnanswered: Boolean = false,
    )

    /** His time zone and day boundary, live, so a change in Settings moves this screen's day at once. */
    private val zone: StateFlow<Pair<String, Int>> = settings.watchDay()
        .map { it.timeZone to it.boundaryHour }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsRepository.defaultDay().let { it.timeZone to it.boundaryHour })

    private data class DaySettings(val tz: String, val boundary: Int, val hours: List<Double>?)

    /** The day settings and his hours together (a combine takes at most five flows). */
    private val dayAndHours = combine(zone, settings.watchPlanHours()) { (tz, boundary), hours -> DaySettings(tz, boundary, hours) }
    private val quietLine = MutableStateFlow<String?>(null)

    /** The day changes at the day boundary, so the lists are worked out again every minute. */
    private val minute = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(60_000)
        }
    }

    internal data class Rows(
        val goals: List<GoalRow>,
        val targets: List<TargetRow>,
        val backlogs: List<BacklogRow>,
        val plans: List<PlanRow>,
        val rest: Set<LocalDate>,
    )

    /** Shared, so the home, the check-ins and the full state read the plan's rows once, not once each. */
    private val rows = combine(planner.goals, planner.targets, planner.backlogs, planner.plans, planner.restDays) { g, t, b, p, r ->
        Rows(g, t, b, p, r.map { LocalDate.parse(it.dayKey) }.toSet())
    }.shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    /**
     * Only the check-ins this screen shows something for (see [targetsToRead]), not every one since 2000: a
     * finished goal's years of answers are no longer read each time Plan opens.
     */
    private val checkins = rows.map { targetsToRead(it.targets, it.backlogs) }
        .distinctUntilChanged()
        .flatMapLatest { ids -> if (ids.isEmpty()) flowOf(emptyList()) else planner.watchCheckinsOf(ids) }

    /** His day now, moved by the minute tick and by a change of zone or day boundary. */
    private val todayKey = combine(zone, minute) { (tz, boundary), now ->
        Planner.dayKey(Instant.ofEpochMilli(now).toString(), tz, boundary)
    }.distinctUntilChanged()

    val home: StateFlow<Home> = combine(
        rows,
        todayKey.flatMapLatest { day -> planner.checkinsFrom(day).map { day to it } },
    ) { r, (today, answers) -> homeOf(r, today, answers) }
        .flowOn(kotlinx.coroutines.Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Home())

    val state: StateFlow<State> = combine(
        rows,
        checkins,
        dayAndHours,
        minute,
        quietLine,
    ) { r, checkins, d, now, line ->
        build(r, checkins, d.tz, d.boundary, now, line, d.hours)
    }.flowOn(kotlinx.coroutines.Dispatchers.Default) // the whole history is read; never on the main thread
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), State())


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
        "no_days_left" -> "No scheduled days left before the deadline."
        "no_data" -> "Not enough data yet: fewer than ${Planner.MIN_ANSWERED} answered days in the last ${Planner.WINDOW} days."
        else -> {
            val key = if (p.status != "short" || p.bestPerDay != null) p.status else "short_no_best"
            PlanStateText.STATUS_TEXT.getValue(key).removePrefix("Status: ").replaceFirstChar { it.uppercase() }
        }
    }

    /** "Wednesday 14 October": the top line of Today. */
    fun longDay(d: LocalDate): String = "${DAY_NAMES[d.dayOfWeek.value - 1]} ${d.dayOfMonth} ${MONTH_NAMES[d.monthValue - 1]}"

    /** The day the next not-yet-started step begins, when nothing is scheduled today because the plan starts later. */
    fun nextStart(state: State): LocalDate? =
        state.goals.flatMap { it.steps }.filter { it.start.isAfter(state.today) }.minOfOrNull { it.start }

    /** Which day of the plan today is (1 on its first day), from the earliest start of any goal's targets. */
    fun planDay(state: State): Long? = state.goals.mapNotNull { it.firstStart }.minOrNull()
        ?.takeIf { !it.isAfter(state.today) }
        ?.let { java.time.temporal.ChronoUnit.DAYS.between(it, state.today) + 1 }

    /** What today's answer was, in plain words: "Target met: 2 lectures", "The minimum, and it counts". */
    fun answeredLine(row: TodayRow): String {
        val a = row.answer ?: return ""
        val unit = if (row.target.unit.isBlank()) "" else " ${row.target.unit}"
        val amount = if (row.quantity) ": ${n(a.amount)}$unit" else ""
        return when (a.level) {
            "beyond" -> "Beyond the target$amount"
            "target" -> "Target met$amount"
            "minimum" -> "The minimum, and it counts$amount"
            "not" -> "Not today. That is information, not a failure."
            "rest" -> "Rest day"
            else -> ""
        }
    }

    /**
     * What an answer means for the plan, from the app's own numbers: "298 left. Your plan still finishes Sat 2 Jan."
     * Null when the target counts toward no backlog.
     */
    fun planLine(row: TodayRow): String? {
        val b = row.backlog ?: return null
        val left = "${n(b.remaining)} left."
        val f = b.facts.finish ?: return left
        val finishDay = f.finish
        return when {
            b.facts.projection.status == "done" -> "All ${n(b.row.total)} done."
            f.status == "finishes" && finishDay != null -> "$left Your plan still finishes ${showDay(finishDay)}."
            f.status == "late" || f.status == "never" ->
                "$left At your steps it reaches ${n(f.reachesByDeadline ?: 0.0)} of ${n(f.remaining)} by ${day(b.row.deadline)}."
            else -> left
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

    /** His estimate of one session's minutes for a target; null clears it. */
    fun setTargetMinutes(id: Long, minutes: Double?) = act { planner.setTargetMinutes(id, minutes) }

    /** His minutes for planning per weekday, Monday first; null clears them. */
    fun setHours(hours: List<Double>?) = act { settings.setPlanHours(hours) }

    /** Whether a target's unit already says how long it takes (minutes or hours). */
    fun timedByUnit(t: TargetRow): Boolean = PlanLoad.sessionMinutes(t.unit, 1.0) != null

    fun addBacklog(goalId: Long?, name: String, total: Double, unit: String, startDone: Double, deadline: LocalDate) = act {
        planner.addBacklog(goalId, name, total, unit, startDone, deadline, System.currentTimeMillis())
    }

    fun setBacklogStatus(id: Long, status: String) = act { planner.setBacklogStatus(id, status) }

    /**
     * Plan with coach: Talk opens a new Plan and track conversation, which sees the whole tracker. [draft] is a
     * message typed for him into the box (the goal page's calm "Add the ramp"); he sends it himself, or not.
     */
    fun planWithCoach(draft: String? = null) = requests.startConversation("plan", draft = draft)

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
        /** The whole Plan screen, from its rows and check-ins. Pure, so tests can run it (PlanReadsTest). */
        internal fun build(
            r: Rows,
            checkins: List<CheckinRow>,
            tz: String,
            boundary: Int,
            now: Long,
            line: String?,
            hours: List<Double>? = null,
        ): State {
            val today = Planner.dayKey(Instant.ofEpochMilli(now).toString(), tz, boundary)
            val byTarget = checkins.groupBy { it.targetId }
            val live = liveTargets(r.targets, today)
            fun history(t: TargetRow) = (byTarget[t.id] ?: emptyList()).map { PlannerRepository.model(it) }

            // Paused targets stay on their goal (with Resume); only live ones are on Today and count for pace.
            val shown = live + r.targets.filter { it.status == "paused" }

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
                    // The day stored when it was made, as PLAN_STATE reads it, so a later time zone or day
                    // boundary change never makes this screen and the coach disagree about what is done.
                    b.startDay?.let(LocalDate::parse) ?: Planner.dayKey(Instant.ofEpochMilli(b.createdAt).toString(), tz, boundary),
                    r.targets.filter { it.backlogId == b.id && it !in live }.flatMap(::history),
                    r.targets.filter { it.backlogId == b.id }.minOfOrNull { LocalDate.parse(it.startDay) },
                )
                val facts = PlanBlocks.backlogFacts(
                    model,
                    linked.map { PlanBlocks.Target(it.id, it.name, it.goalId, it.backlogId, it.minimum, it.target, it.schedule, history(it), LocalDate.parse(it.startDay), it.endDay?.let(LocalDate::parse)) },
                    today,
                    r.rest,
                )
                // The week strip: the last seven days since tracking began, answers of every step merged per day.
                val all = PlanBlocks.mergeByDay(r.targets.filter { it.backlogId == b.id }.flatMap(::history))
                val from = maxOf(today.minusDays(6), model.firstStart ?: today)
                val recent = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }.map { day ->
                    val row = all.firstOrNull { it.day == day }
                    when {
                        row != null -> DayMark(day, row.level)
                        day in r.rest -> DayMark(day, "rest")
                        linked.none { scheduledOn(it.schedule, day) } -> DayMark(day, "off")
                        else -> DayMark(day, null)
                    }
                }.toList()
                return BacklogView(b, facts, linked, recent)
            }

            fun steps(targets: List<TargetRow>): List<StepView> = targets.sortedBy { it.startDay }.map { t ->
                val start = LocalDate.parse(t.startDay)
                val end = t.endDay?.let(LocalDate::parse)
                val phase = when {
                    t.status == "ended" || (end != null && end.isBefore(today)) -> "done"
                    start.isAfter(today) -> "next"
                    else -> "now"
                }
                StepView(t, start, end, phase)
            }

            val activeBacklogs = r.backlogs.filter { it.status == "active" }
            val activeGoals = r.goals.filter { it.status == "active" }
            val goalIds = activeGoals.map { it.id }.toSet()
            val goals = activeGoals.map { g ->
                val bs = activeBacklogs.filter { it.goalId == g.id }.map(::backlogView)
                val ts = shown.filter { it.goalId == g.id }.map(::targetView)
                val mine = r.targets.filter { it.goalId == g.id && it.status != "paused" }
                GoalView(
                    row = g,
                    status = goalStatus(bs, ts),
                    plan = r.plans.firstOrNull { it.goalId == g.id && it.status == "active" },
                    backlogs = bs,
                    targets = ts,
                    steps = steps(mine),
                    firstStart = mine.minOfOrNull { LocalDate.parse(it.startDay) },
                )
            }
            val looseBacklogs = activeBacklogs.filter { it.goalId == null || it.goalId !in goalIds }.map(::backlogView)
            val backlogById = (goals.flatMap { it.backlogs } + looseBacklogs).associateBy { it.row.id }
            val todayRows = dueToday(live, today).map { t ->
                // A ramp's steps share a backlog and a name: say which step today is.
                val ramp = r.targets.filter { it.backlogId != null && it.backlogId == t.backlogId && it.name == t.name }.sortedBy { it.startDay }
                TodayRow(
                    target = t,
                    answer = byTarget[t.id]?.firstOrNull { it.dayKey == today.toString() },
                    backlog = t.backlogId?.let { backlogById[it] },
                    step = ramp.indexOfFirst { it.id == t.id }.coerceAtLeast(0) + 1,
                    steps = ramp.size.coerceAtLeast(1),
                )
            }
            return State(
                ready = true,
                today = today,
                todayLabel = "${DAY_NAMES[today.dayOfWeek.value - 1]} ${TimeFacts.shortDay(today).substringAfter(' ')}",
                restToday = today in r.rest,
                todayRows = todayRows,
                goals = goals,
                looseBacklogs = looseBacklogs,
                looseTargets = shown.filter { it.goalId == null || it.goalId !in goalIds }.map(::targetView),
                oldGoals = r.goals.filter { it.status != "active" },
                quietLine = line,
                hours = hours,
                // A live target's row holds the values in force from today on, so it is all the week needs.
                week = PlanLoad.dayLoad(
                    live.map {
                        PlanLoad.Item(
                            it.id, it.schedule, it.target, LocalDate.parse(it.startDay), it.endDay?.let(LocalDate::parse),
                            emptyList(), it.unit, it.minutes,
                        )
                    },
                    today,
                    r.rest,
                ),
                names = live.associate { it.id to it.name },
            )
        }

        /** Talk home's light view from the plan's rows and [answers] (any check-ins that include today's). */
        internal fun homeOf(r: Rows, today: LocalDate, answers: List<CheckinRow>): Home {
            val due = dueToday(liveTargets(r.targets, today), today)
            val answered = answers.filter { it.dayKey == today.toString() }.map { it.targetId }.toSet()
            return Home(
                ready = true,
                today = today,
                planDay = planDayOf(r.goals, r.targets, today),
                hasPlan = due.isNotEmpty() || r.goals.any { it.status == "active" },
                todayUnanswered = today !in r.rest && due.any { it.id !in answered },
            )
        }

        /** Targets on Today and counted for pace: active, and not past their end day. */
        internal fun liveTargets(targets: List<TargetRow>, today: LocalDate): List<TargetRow> = targets.filter { t ->
            t.status == "active" && (t.endDay == null || !LocalDate.parse(t.endDay).isBefore(today))
        }

        /** The live targets that ask for an answer today: started, and scheduled on today's weekday. */
        internal fun dueToday(live: List<TargetRow>, today: LocalDate): List<TargetRow> = live.filter { t ->
            !LocalDate.parse(t.startDay).isAfter(today) && scheduledOn(t.schedule, today)
        }

        /**
         * "Day N of your plan": counted from the first start of any not-paused target of an active goal, as
         * [planDay] counts it from the goals' firstStart; null before that day.
         */
        internal fun planDayOf(goals: List<GoalRow>, targets: List<TargetRow>, today: LocalDate): Long? =
            goals.filter { it.status == "active" }
                .mapNotNull { g ->
                    targets.filter { it.goalId == g.id && it.status != "paused" }.minOfOrNull { LocalDate.parse(it.startDay) }
                }
                .minOrNull()
                ?.takeIf { !it.isAfter(today) }
                ?.let { java.time.temporal.ChronoUnit.DAYS.between(it, today) + 1 }

        /**
         * The targets whose check-ins [State] uses: every live or paused target (Today, pace, reasons) and every
         * target of an active backlog, ended ones too (what is done, the week strip, how the plan went). A
         * target outside both (a finished goal's, an ended loose one's) shows nothing that its check-ins change,
         * so they are not read. A wider set than strictly needed (an active target past its end day) is harmless.
         */
        internal fun targetsToRead(targets: List<TargetRow>, backlogs: List<BacklogRow>): List<Long> {
            val activeBacklogs = backlogs.filter { it.status == "active" }.map { it.id }.toSet()
            return targets.filter { t ->
                t.status == "active" || t.status == "paused" || (t.backlogId != null && t.backlogId in activeBacklogs)
            }.map { it.id }.sorted()
        }

        internal fun scheduledOn(schedule: String, day: LocalDate): Boolean = try {
            when (val s = Planner.parseSchedule(schedule)) {
                is Schedule.Days -> (day.dayOfWeek.value - 1) in s.weekdays
                is Schedule.Count -> true
            }
        } catch (e: Exception) {
            false
        }

        private val DAY_NAMES = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
        private val MONTH_NAMES = listOf(
            "January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December",
        )

        /** On pace, behind, or no data yet: read from the backlogs' Status lines, never judged by the model. */
        fun goalStatus(backlogs: List<BacklogView>, targets: List<TargetView>): String {
            val statuses = backlogs.map { it.facts.projection.status }
            return when {
                statuses.any { it == "short" || it == "needs_best" || it == "past_deadline" || it == "no_days_left" } -> "behind"
                statuses.isNotEmpty() && statuses.all { it == "ok" || it == "done" } -> "on pace"
                statuses.isEmpty() && targets.any { it.answered >= Planner.MIN_ANSWERED } -> "tracking"
                else -> "no data yet"
            }
        }
    }
}
