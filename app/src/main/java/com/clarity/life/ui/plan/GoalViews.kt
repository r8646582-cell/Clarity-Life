package com.clarity.life.ui.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.clarity.life.core.Planner
import com.clarity.life.ui.theme.BoldCard
import com.clarity.life.ui.theme.Headline
import com.clarity.life.ui.theme.Kicker
import com.clarity.life.ui.theme.OutlineCard
import com.clarity.life.ui.theme.Pill
import com.clarity.life.ui.theme.PrimaryButton
import com.clarity.life.ui.theme.Quiet
import com.clarity.life.ui.theme.SecondaryButton
import com.clarity.life.ui.theme.TextAction
import com.clarity.life.ui.theme.clarity
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
    onFix: ((draft: String) -> Unit)? = null,
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
            onFix = onFix ?: { onPlanWithCoach() },
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
        if (state.goals.isNotEmpty() || state.looseTargets.isNotEmpty()) TimeCard(state, model)
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

@Composable
private fun GoalPage(
    g: PlanViewModel.GoalView,
    state: PlanViewModel.State,
    model: PlanViewModel,
    onBack: (() -> Unit)?,
    onToday: () -> Unit,
    onFix: (draft: String) -> Unit,
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
        if (b != null) FinishCard(b, g, state, model, onFix)
        TodayStepCard(g, state, model, onToday)
        if (b != null) ProgressLine(b, model)
        if (b != null) PaceCard(b, model)
        if (g.steps.isNotEmpty()) StepsCard(g, state, model)
        if (b != null) WeekCard(b)
        TimeCard(state, model)
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
        if (t.row.status != "ended" && !model.timedByUnit(t.row)) MinutesField(t, model)
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

/**
 * His own estimate of the minutes one session takes, so the week can add up his time (Max plan Phase 6). A target
 * counted in minutes or hours needs none.
 */
@Composable
private fun MinutesField(t: PlanViewModel.TargetView, model: PlanViewModel) {
    var text by rememberSaveable(t.row.id) { mutableStateOf(t.row.minutes?.let { model.n(it) } ?: "") }
    val m = number(text)
    val ok = text.isBlank() || (m != null && m >= 1 && m <= 1440)
    Field(
        "Minutes one session takes (your estimate)",
        text,
        { text = it.take(5) },
        number = true,
        hint = if (ok) "Used to add up your time each day. Leave it empty if you do not know." else "From 1 to 1440 minutes",
    )
    val saved = t.row.minutes
    val changed = if (text.isBlank()) saved != null else m != saved
    if (ok && changed) {
        TextButton(onClick = { model.setTargetMinutes(t.row.id, if (text.isBlank()) null else m) }) {
            Text("Save the time", color = clarity.yellow)
        }
    }
}

/**
 * Your week (Max plan Phase 6): the time the targets of every goal take on each of the next 7 days, against the
 * hours he says he has, all counted by [com.clarity.life.core.PlanLoad]. Nothing red: a day over is said in
 * words. Set your hours opens seven fields, Monday to Sunday.
 */
@Composable
private fun TimeCard(state: PlanViewModel.State, model: PlanViewModel) {
    val week = state.week ?: return
    var editing by rememberSaveable { mutableStateOf(false) }
    OutlineCard {
        Kicker("Your time, next 7 days", modifier = Modifier.padding(bottom = 6.dp))
        val hours = state.hours
        val anyTime = week.days.any { it.timed.isNotEmpty() } || week.weekly.any { it.minutes != null }
        if (!anyTime && hours == null) {
            Quiet("Give a target the minutes one session takes (under Edit), and set your hours, to see whether your days fit.")
        } else {
            week.days.forEach { d ->
                val label = "${Planner.DAYS[d.day.dayOfWeek.value - 1]} ${d.day.dayOfMonth}"
                val m = Planner.roundHalfUp(d.minutes)
                val value = when {
                    d.rest -> "Rest day"
                    hours == null -> "${model.n(m)} min"
                    else -> {
                        val have = Planner.roundHalfUp(hours[d.day.dayOfWeek.value - 1])
                        "${model.n(m)} of ${model.n(have)} min" + if (m - have > 1e-9) ", ${model.n(m - have)} over" else ""
                    }
                }
                val over = hours != null && !d.rest && m - Planner.roundHalfUp(hours[d.day.dayOfWeek.value - 1]) > 1e-9
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(label, style = MaterialTheme.typography.bodyMedium, color = clarity.text, modifier = Modifier.weight(1f))
                    Text(value, style = MaterialTheme.typography.bodyMedium, color = if (over) clarity.yellow else clarity.muted)
                }
            }
            val untimed = week.days.flatMap { it.untimed }.distinct()
            if (untimed.isNotEmpty()) {
                Quiet("Not counted (no time given): " + untimed.joinToString(", ") { state.names[it] ?: "a target" } + ".")
            }
            week.weekly.forEach { w ->
                Quiet(
                    "${state.names[w.id] ?: "A target"}: ${w.perWeek} a week, not on fixed days" +
                        (w.minutes?.let { ", ${model.n(Planner.roundHalfUp(it))} min a week." } ?: "."),
                )
            }
        }
        if (!editing) {
            TextButton(onClick = { editing = true }) {
                Text(if (hours == null) "Set your hours" else "Change your hours", color = clarity.yellow)
            }
        } else {
            HoursForm(hours, onDone = { editing = false }, model)
        }
    }
}
