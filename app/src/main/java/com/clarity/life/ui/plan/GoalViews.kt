package com.clarity.life.ui.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.clarity.life.core.Planner
import com.clarity.life.ui.theme.clarity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import com.clarity.life.ui.theme.BigFact
import com.clarity.life.ui.theme.BoldCard
import com.clarity.life.ui.theme.Headline
import com.clarity.life.ui.theme.Kicker
import com.clarity.life.ui.theme.OutlineCard
import com.clarity.life.ui.theme.Pill
import com.clarity.life.ui.theme.PrimaryButton
import com.clarity.life.ui.theme.Quiet
import com.clarity.life.ui.theme.SecondaryButton
import com.clarity.life.ui.theme.TextAction
import java.time.LocalDate

/**
 * Plan, Goals: the redesigned goal page (the owner's approved mockup of 13 Oct 2026). One clear thing at the top
 * (it starts tomorrow, it is on track, it is behind), then where the plan's own steps finish it (counted by
 * [com.clarity.life.core.Planner.planFinish]), today's step, the steps, the last seven days and the progress.
 * Everything that changes the plan by hand sits behind Edit. With one goal, Goals opens it directly.
 */
@Composable
fun GoalsSection(
    state: PlanViewModel.State,
    model: PlanViewModel,
    onToday: () -> Unit = {},
    onPlanWithCoach: () -> Unit,
) {
    var openGoal by rememberSaveable { mutableStateOf<Long?>(null) }
    val only = state.goals.singleOrNull()?.takeIf { state.looseBacklogs.isEmpty() && state.looseTargets.isEmpty() }
    val goal = state.goals.firstOrNull { it.row.id == openGoal } ?: only
    if (goal != null) {
        if (only == null) androidx.activity.compose.BackHandler { openGoal = null }
        GoalPage(
            goal,
            state,
            model,
            onBack = if (only == null) ({ openGoal = null }) else null,
            onToday = onToday,
            onPlanWithCoach = onPlanWithCoach,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 12.dp)) {
        state.quietLine?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = clarity.danger) }
        if (state.goals.isEmpty() && state.looseBacklogs.isEmpty() && state.looseTargets.isEmpty()) {
            Headline("No goals yet.")
            Quiet("Talk one through with the coach and save the plan it offers, or write one yourself.")
        }
        state.goals.forEach { g ->
            val (pill, warn) = statusPill(g, state, model)
            BoldCard(gap = 6, onClick = { openGoal = g.row.id }) {
                Pill(pill, warn)
                Text(g.row.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = clarity.text)
                subtitle(g, model)?.let { Quiet(it) }
            }
        }
        if (state.looseBacklogs.isNotEmpty() || state.looseTargets.isNotEmpty()) {
            Heading("Not under a goal")
            state.looseBacklogs.forEach { ManageBacklog(it, model) }
            state.looseTargets.forEach { ManageTarget(it, model, state) }
        }
        PrimaryButton("Plan with coach", onClick = onPlanWithCoach)
        AddGoalForm(state, model)
        if (state.oldGoals.isNotEmpty()) {
            Heading("Done and archived")
            state.oldGoals.forEach { g ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(g.title, style = MaterialTheme.typography.bodyMedium, color = clarity.text)
                        Muted(if (g.status == "done") "Done" else "Archived")
                    }
                    TextButton(onClick = { model.setGoalStatus(g.id, "active") }) { Text("Make active", color = clarity.yellow) }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** The pill at the top of a goal: when it starts, whether its plan is on track, or which day it is. */
private fun statusPill(g: PlanViewModel.GoalView, state: PlanViewModel.State, model: PlanViewModel): Pair<String, Boolean> {
    val today = state.today
    val first = g.firstStart
    if (first != null && first.isAfter(today)) {
        val day = model.showDay(first)
        return (if (first == today.plusDays(1)) "Starts tomorrow, $day" else "Starts $day") to false
    }
    val finish = g.backlogs.firstNotNullOfOrNull { it.facts.finish }
    return when {
        g.backlogs.isNotEmpty() && g.backlogs.all { it.facts.projection.status == "done" } -> "Done" to false
        finish?.status == "finishes" -> "On track" to false
        finish?.status == "late" || finish?.status == "never" -> "Behind the plan" to true
        g.status == "behind" -> "Behind" to true
        first != null -> "Day ${java.time.temporal.ChronoUnit.DAYS.between(first, today) + 1}" to false
        else -> g.status.replaceFirstChar { it.uppercase() } to false
    }
}

private fun subtitle(g: PlanViewModel.GoalView, model: PlanViewModel): String? {
    val b = g.backlogs.firstOrNull()
    val deadline = g.row.deadline
    return when {
        b != null -> "${model.n(b.row.total)} ${b.row.unit} by ${model.day(b.row.deadline)}"
        deadline != null -> "By ${model.day(deadline)}"
        else -> null
    }
}

@Composable
private fun GoalPage(
    g: PlanViewModel.GoalView,
    state: PlanViewModel.State,
    model: PlanViewModel,
    onBack: (() -> Unit)?,
    onToday: () -> Unit,
    onPlanWithCoach: () -> Unit,
) {
    var editing by rememberSaveable(g.row.id) { mutableStateOf(false) }
    val b = g.backlogs.firstOrNull()
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) TextAction("‹ Goals", onClick = onBack)
            Spacer(Modifier.weight(1f))
            TextAction(if (editing) "Done" else "Edit", color = clarity.muted) { editing = !editing }
        }
        state.quietLine?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = clarity.danger) }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val (pill, warn) = statusPill(g, state, model)
            Pill(pill, warn)
            Headline(g.row.title)
            subtitle(g, model)?.let { Quiet(it) }
        }
        if (b != null) FinishCard(b, g, state, model)
        TodayStepCard(g, state, model, onToday)
        if (g.steps.isNotEmpty()) StepsCard(g, state, model)
        if (b != null) WeekCard(b)
        if (b != null) ProgressLine(b, model)
        g.plan?.let { p ->
            OutlineCard {
                val review = LocalDate.parse(p.reviewDay)
                val label = when {
                    review.isBefore(state.today) -> "Review was due ${model.day(p.reviewDay)}"
                    review == state.today -> "Review today"
                    else -> (if (p.version == 1) "First review, " else "Next review, ") + model.day(p.reviewDay)
                }
                Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = clarity.text)
                Quiet(p.rationale)
            }
        }
        PrimaryButton("Plan with coach", onClick = onPlanWithCoach)
        if (editing) {
            Kicker("Change by hand", clarity.muted)
            g.targets.forEach { ManageTarget(it, model, state) }
            g.backlogs.forEach { ManageBacklog(it, model) }
            AddTargetForm(g.row.id, g.backlogs, state, model)
            AddBacklogForm(g.row.id, state, model)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Mark goal done", Modifier.weight(1f)) {
                    model.setGoalStatus(g.row.id, "done")
                    onBack?.invoke()
                }
                SecondaryButton("Archive goal", Modifier.weight(1f)) {
                    model.setGoalStatus(g.row.id, "archived")
                    onBack?.invoke()
                }
            }
            Quiet("Its targets and check-ins stay as they are, and can be ended one by one.")
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Where the plan's own steps finish it; without steps, the pace the calendar needs. All counted by the app. */
@Composable
private fun FinishCard(b: PlanViewModel.BacklogView, g: PlanViewModel.GoalView, state: PlanViewModel.State, model: PlanViewModel) {
    val f = b.facts.finish
    val p = b.facts.projection
    val first = g.firstStart
    val started = first != null && !first.isAfter(state.today)
    BoldCard(gap = 6) {
        val finishDay = f?.finish
        val spare = f?.daysSpare
        val reaches = f?.reachesByDeadline ?: 0.0
        val required = p.requiredPerDay
        when {
            p.status == "done" -> {
                Quiet("Finished")
                BigFact("${model.n(b.row.total)} of ${model.n(b.row.total)}")
                Text("Every one of them is done.", color = clarity.text, style = MaterialTheme.typography.bodyLarge)
            }
            f?.status == "finishes" && finishDay != null && spare != null -> {
                Quiet(if (started && b.facts.done > 0) "Your plan still finishes" else "Your plan finishes")
                BigFact(model.showDay(finishDay))
                Text(
                    when (spare) {
                        0 -> "On your deadline day"
                        1 -> "1 day before your deadline"
                        else -> "$spare days before your deadline"
                    },
                    color = clarity.text,
                    style = MaterialTheme.typography.bodyLarge,
                )
                val steps = b.facts.steps.size
                val flat = required?.takeIf { steps > 1 }
                Quiet(
                    "Counted by the app from your ${if (steps == 1) "plan" else "$steps steps"}." +
                        (flat?.let { " A flat pace would need ${model.perDayUp(it)} a day; your steps start lower and end higher." } ?: ""),
                    Modifier.padding(top = 6.dp),
                )
            }
            f != null && (f.status == "late" || f.status == "never") -> {
                Quiet("At your plan's steps, by ${model.day(b.row.deadline)}")
                BigFact("${model.n(reaches)} of ${model.n(f.remaining)}", clarity.danger)
                Text(
                    "Short by ${model.n(f.remaining - reaches)}. A step or the date needs to change.",
                    color = clarity.text,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Quiet("Counted by the app. Plan with coach to change it together.", Modifier.padding(top = 6.dp))
            }
            required != null -> {
                Quiet("You need")
                BigFact(model.perDayUp(required))
                Text("a day, ${p.daysLeft} scheduled days left", color = clarity.text, style = MaterialTheme.typography.bodyLarge)
                Quiet("Counted by the app, nothing estimated.", Modifier.padding(top = 6.dp))
            }
            else -> Quiet(model.statusText(p))
        }
    }
}

/** Today's step, with Check in; once answered, what was answered. Only on a day it is scheduled. */
@Composable
private fun TodayStepCard(g: PlanViewModel.GoalView, state: PlanViewModel.State, model: PlanViewModel, onToday: () -> Unit) {
    if (state.restToday) return
    val row = state.todayRows.firstOrNull { it.target.goalId == g.row.id } ?: return
    val t = row.target
    BoldCard(gap = 8) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Today",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = clarity.text,
                modifier = Modifier.weight(1f),
            )
            if (row.steps > 1) Quiet("Step ${row.step} of ${row.steps}")
        }
        BigFact(model.n(t.target) + if (t.unit.isBlank()) "" else " ${t.unit}")
        if (t.minimum > 0 && t.minimum < t.target) Quiet("${model.n(t.minimum)} still counts on a bad day")
        if (row.answer == null) {
            PrimaryButton("Check in", Modifier.fillMaxWidth().padding(top = 6.dp), onClick = onToday)
        } else {
            Text(model.answeredLine(row), color = clarity.yellow, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** The plan's steps as a timeline: done (grey), now (yellow), next (hollow). */
@Composable
private fun StepsCard(g: PlanViewModel.GoalView, state: PlanViewModel.State, model: PlanViewModel) {
    BoldCard(gap = 16) {
        Text("The plan", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = clarity.text)
        g.steps.forEach { st ->
            val t = st.target
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    Modifier
                        .padding(top = 5.dp)
                        .size(14.dp)
                        .then(
                            when (st.phase) {
                                "now" -> Modifier.background(clarity.yellow, CircleShape)
                                "done" -> Modifier.background(clarity.controlOutline, CircleShape)
                                else -> Modifier.border(2.dp, clarity.controlOutline, CircleShape)
                            },
                        ),
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val unit = if (t.unit.isBlank()) "" else " ${t.unit}"
                    val per = if (t.schedule == "daily") " a day" else ", ${t.schedule}"
                    Text(
                        "${model.n(t.target)}$unit$per",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (st.phase == "done") clarity.muted else clarity.text,
                    )
                    val end = st.end
                    val dates = if (end != null) "${model.showDay(st.start)} to ${model.showDay(end)}" else "From ${model.showDay(st.start)}"
                    val tag = when {
                        st.phase == "done" -> " · done"
                        st.phase == "now" && !st.start.isAfter(state.today) -> " · now"
                        else -> ""
                    }
                    Quiet(dates + tag)
                }
            }
        }
        val current = g.steps.firstOrNull { it.phase == "now" } ?: g.steps.firstOrNull { it.phase == "next" }
        current?.target?.takeIf { it.minimum > 0 && it.minimum < it.target }?.let {
            HorizontalDivider(color = clarity.hairline)
            Quiet("On a bad day, ${model.n(it.minimum)} still counts. Missed days are not failures; they show what to change.")
        }
    }
}

/** The last seven days since tracking began: full days yellow, minimum days lighter, a miss outlined. */
@Composable
private fun WeekCard(b: PlanViewModel.BacklogView) {
    val days = b.recent.filter { it.level != "off" }
    if (days.size < 2) return // a strip of one day says nothing yet
    BoldCard(gap = 12) {
        Text("Last ${days.size} days", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = clarity.text)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            b.recent.forEach { m ->
                Column(
                    Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val shape = RoundedCornerShape(8.dp)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(36.dp)
                            .then(
                                when (m.level) {
                                    "target", "beyond" -> Modifier.background(clarity.yellow, shape)
                                    "minimum" -> Modifier.background(clarity.yellow.copy(alpha = 0.45f), shape)
                                    "rest" -> Modifier.background(clarity.raised, shape)
                                    "not" -> Modifier.border(2.dp, clarity.danger, shape)
                                    "off" -> Modifier
                                    else -> Modifier.border(1.dp, clarity.hairline, shape)
                                },
                            ),
                    )
                    Text(m.day.dayOfWeek.name.take(1), style = MaterialTheme.typography.labelSmall, color = clarity.muted)
                }
            }
        }
        val answered = days.count { it.level != null && it.level != "rest" }
        val scheduled = days.count { it.level != "rest" }
        Quiet("$answered of $scheduled days answered. Full days yellow, minimum days lighter, an empty box is no answer.")
    }
}

@Composable
private fun ProgressLine(b: PlanViewModel.BacklogView, model: PlanViewModel) {
    val p = b.facts.projection
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row {
            Quiet("${model.n(b.facts.done)} of ${model.n(b.row.total)} done", Modifier.weight(1f))
            Quiet(
                p.typicalPerDay?.let { "Your pace ${model.perDay(it)} a day" }
                    ?: "Your pace shows after ${Planner.MIN_ANSWERED} answered days",
            )
        }
        val share = if (b.row.total > 0) (b.facts.done / b.row.total).toFloat().coerceIn(0f, 1f) else 0f
        Box(Modifier.fillMaxWidth().height(8.dp).background(clarity.surface, RoundedCornerShape(999.dp))) {
            if (share > 0f) Box(Modifier.fillMaxWidth(share).height(8.dp).background(clarity.yellow, RoundedCornerShape(999.dp)))
        }
    }
}

/** Behind Edit: a backlog with its hand controls. */
@Composable
private fun ManageBacklog(b: PlanViewModel.BacklogView, model: PlanViewModel) {
    OutlineCard {
        Text(b.row.name, style = MaterialTheme.typography.titleSmall, color = clarity.text)
        Quiet("${model.n(b.remaining)} of ${model.n(b.row.total)} ${b.row.unit} left · deadline ${model.day(b.row.deadline)}")
        if (b.linked.isEmpty()) Quiet("No target counts toward it yet. Add one below.")
        Row {
            TextButton(onClick = { model.setBacklogStatus(b.row.id, "done") }) { Text("Mark done", color = clarity.yellow) }
            TextButton(onClick = { model.setBacklogStatus(b.row.id, "archived") }) { Text("Archive", color = clarity.muted) }
        }
    }
}

/** Behind Edit: a target with its hand controls. */
@Composable
private fun ManageTarget(t: PlanViewModel.TargetView, model: PlanViewModel, state: PlanViewModel.State) {
    OutlineCard {
        Text(t.row.name, style = MaterialTheme.typography.titleSmall, color = clarity.text)
        Quiet(
            "${model.levelsLine(t.row)}, ${t.row.schedule}, from ${model.day(t.row.startDay)}" +
                (t.row.endDay?.let { " to ${model.day(it)}" } ?: ""),
        )
        t.reasons.forEach { Quiet("Not or Part: $it.") }
        Row {
            if (t.row.status == "active") {
                TextButton(onClick = { model.setTargetStatus(t.row.id, "paused", state.today) }) { Text("Pause", color = clarity.muted) }
            } else {
                TextButton(onClick = { model.setTargetStatus(t.row.id, "active", state.today) }) { Text("Resume", color = clarity.yellow) }
            }
            TextButton(onClick = { model.setTargetStatus(t.row.id, "ended", state.today) }) { Text("End it", color = clarity.muted) }
        }
    }
}

@Composable
internal fun PlanButton(label: String, filled: Boolean, modifier: Modifier = Modifier.fillMaxWidth(), onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = if (filled) clarity.ground else clarity.text,
        textAlign = TextAlign.Center,
        modifier = modifier
            .heightIn(min = 44.dp)
            .then(if (filled) Modifier.background(clarity.yellow, shape) else Modifier.border(1.dp, clarity.controlOutline, shape))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    )
}

// ---------- forms ----------

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, number: Boolean = false, hint: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = clarity.muted)
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            colors = planFieldColors(),
            shape = RoundedCornerShape(12.dp),
            singleLine = true,
            keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
        )
        hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = clarity.muted) }
    }
}

/** What a typed date reads as, shown under the field before he saves (the same code reads card dates). */
private fun dayHint(model: PlanViewModel, state: PlanViewModel.State, text: String, empty: String): String =
    if (text.isBlank()) empty else model.readDay(text, state.today)?.let { "Reads as ${model.showDay(it)}" }
        ?: "Cannot read that date. Try 20 Nov, Sat or 2026-11-20 (not in the past)."

private fun number(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

private val AREAS = listOf("studies", "health", "habits", "mind", "money", "relationships", "meaning", "rest_joy", "general")

@Composable
private fun AddGoalForm(state: PlanViewModel.State, model: PlanViewModel) {
    var open by rememberSaveable { mutableStateOf(false) }
    if (!open) {
        PlanButton("Write a goal", filled = false) { open = true }
        return
    }
    var title by rememberSaveable { mutableStateOf("") }
    var deadline by rememberSaveable { mutableStateOf("") }
    var area by rememberSaveable { mutableStateOf("general") }
    FormBox("New goal") {
        Field("In your words", title, { title = it.take(200) })
        Field("Deadline, if there is one", deadline, { deadline = it.take(30) }, hint = dayHint(model, state, deadline, "No deadline"))
        Text("Area", style = MaterialTheme.typography.labelMedium, color = clarity.muted)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AREAS.forEach { a ->
                val on = a == area
                Text(
                    a.replace("_", " and "),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (on) clarity.ground else clarity.text,
                    modifier = Modifier
                        .heightIn(min = 44.dp)
                        .then(if (on) Modifier.background(clarity.yellow, RoundedCornerShape(20.dp)) else Modifier.border(1.dp, clarity.controlOutline, RoundedCornerShape(20.dp)))
                        .clickable { area = a }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                )
            }
        }
        val day = model.readDay(deadline, state.today)
        val ok = title.isNotBlank() && (deadline.isBlank() || day != null)
        FormButtons(ok, onCancel = { open = false }) {
            model.addGoal(title, area, day)
            open = false
            title = ""
            deadline = ""
        }
    }
}

@Composable
private fun AddTargetForm(goalId: Long, backlogs: List<PlanViewModel.BacklogView>, state: PlanViewModel.State, model: PlanViewModel) {
    var open by rememberSaveable(goalId) { mutableStateOf(false) }
    if (!open) {
        PlanButton("Add a target", filled = false) { open = true }
        return
    }
    var name by rememberSaveable { mutableStateOf("") }
    var unit by rememberSaveable { mutableStateOf("") }
    var min by rememberSaveable { mutableStateOf("") }
    var target by rememberSaveable { mutableStateOf("1") }
    var schedule by rememberSaveable { mutableStateOf("daily") }
    var end by rememberSaveable { mutableStateOf("") }
    var backlog by rememberSaveable { mutableStateOf<Long?>(backlogs.singleOrNull()?.row?.id) }
    FormBox("New target") {
        Field("Name", name, { name = it.take(60) })
        Field("Unit, if you count it (lectures, minutes)", unit, { unit = it.take(20) })
        Field("Target on a normal day", target, { target = it.take(8) }, number = true)
        Field("Minimum on a bad day (optional)", min, { min = it.take(8) }, number = true)
        Field(
            "Schedule",
            schedule,
            { schedule = it.take(30) },
            hint = if (Planner.isSchedule(schedule)) "Reads as $schedule" else "Use daily, weekdays, Mon,Wed,Fri, Sat-Thu or 3x/week",
        )
        Field("Last day (optional)", end, { end = it.take(30) }, hint = dayHint(model, state, end, "No end"))
        if (backlogs.isNotEmpty()) {
            Text("Counts toward", style = MaterialTheme.typography.labelMedium, color = clarity.muted)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (listOf<PlanViewModel.BacklogView?>(null) + backlogs).forEach { b ->
                    val on = b?.row?.id == backlog
                    Text(
                        b?.row?.name ?: "Nothing",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (on) clarity.ground else clarity.text,
                        modifier = Modifier
                            .heightIn(min = 44.dp)
                            .then(if (on) Modifier.background(clarity.yellow, RoundedCornerShape(20.dp)) else Modifier.border(1.dp, clarity.controlOutline, RoundedCornerShape(20.dp)))
                            .clickable { backlog = b?.row?.id }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                    )
                }
            }
        }
        val t = number(target)
        val m = if (min.isBlank()) 0.0 else number(min)
        val endDay = model.readDay(end, state.today)
        val ok = name.isNotBlank() && t != null && t > 0 && m != null && m >= 0 && m <= t &&
            Planner.isSchedule(schedule) && (end.isBlank() || endDay != null)
        FormButtons(ok, onCancel = { open = false }) {
            model.addTarget(goalId, backlog, name, unit, m!!, t!!, schedule.trim(), state.today, endDay)
            open = false
        }
    }
}

@Composable
private fun AddBacklogForm(goalId: Long, state: PlanViewModel.State, model: PlanViewModel) {
    var open by rememberSaveable(goalId) { mutableStateOf(false) }
    if (!open) {
        PlanButton("Add a backlog (an amount to clear by a date)", filled = false) { open = true }
        return
    }
    var name by rememberSaveable { mutableStateOf("") }
    var total by rememberSaveable { mutableStateOf("") }
    var unit by rememberSaveable { mutableStateOf("") }
    var done by rememberSaveable { mutableStateOf("") }
    var deadline by rememberSaveable { mutableStateOf("") }
    FormBox("New backlog") {
        Field("Name", name, { name = it.take(60) })
        Field("Total", total, { total = it.take(8) }, number = true)
        Field("Unit (lectures, chapters, papers)", unit, { unit = it.take(20) })
        Field("Already done before today (optional)", done, { done = it.take(8) }, number = true)
        Field("Deadline", deadline, { deadline = it.take(30) }, hint = dayHint(model, state, deadline, "A backlog needs a deadline"))
        val tot = number(total)
        val d = if (done.isBlank()) 0.0 else number(done)
        val day = model.readDay(deadline, state.today)
        val ok = name.isNotBlank() && unit.isNotBlank() && tot != null && tot > 0 && d != null && d >= 0 && d <= tot && day != null
        FormButtons(ok, onCancel = { open = false }) {
            model.addBacklog(goalId, name, tot!!, unit, d!!, day!!)
            open = false
        }
    }
}

@Composable
private fun FormBox(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(clarity.raised, RoundedCornerShape(16.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = clarity.text)
        content()
    }
}

@Composable
private fun FormButtons(ok: Boolean, onCancel: () -> Unit, onSave: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PlanButton("Cancel", filled = false, modifier = Modifier.weight(1f), onClick = onCancel)
        PlanButton(if (ok) "Save" else "Fill in the fields", filled = ok, modifier = Modifier.weight(1f)) { if (ok) onSave() }
    }
}
