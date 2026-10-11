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

/**
 * Plan, Goals (docs/mockups/Goal.dc.html): every goal with its status (on pace, behind, no data yet), its
 * backlogs and targets, and a goal page with the pace in numbers the app computed. A goal is archived or marked
 * done by his tap, never silently changed. "Plan with coach" opens a Plan and track chat.
 */
@Composable
fun GoalsSection(state: PlanViewModel.State, model: PlanViewModel, onPlanWithCoach: () -> Unit) {
    var openGoal by rememberSaveable { mutableStateOf<Long?>(null) }
    val goal = state.goals.firstOrNull { it.row.id == openGoal }
    if (goal != null) {
        androidx.activity.compose.BackHandler { openGoal = null }
        GoalPage(goal, state, model, onBack = { openGoal = null }, onPlanWithCoach = onPlanWithCoach)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        state.quietLine?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = clarity.danger) }
        if (state.goals.isEmpty() && state.looseBacklogs.isEmpty() && state.looseTargets.isEmpty()) {
            Muted("No goals yet. Write one below, or talk it through with the coach and save the plan it offers.")
        }
        state.goals.forEach { g ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(clarity.surface, RoundedCornerShape(16.dp))
                    .clickable { openGoal = g.row.id }
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(g.row.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = clarity.text, modifier = Modifier.weight(1f))
                    StatusChip(g.status)
                }
                g.row.deadline?.let { Muted("Deadline ${model.day(it)}") }
                g.backlogs.forEach { b -> Muted("${model.n(b.remaining)} of ${model.n(b.row.total)} ${b.row.unit} left") }
                g.targets.forEach { t -> Muted("${t.row.name}: ${model.levelsLine(t.row)}, ${t.row.schedule}") }
            }
        }
        if (state.looseBacklogs.isNotEmpty() || state.looseTargets.isNotEmpty()) {
            Heading("Not under a goal")
            state.looseBacklogs.forEach { BacklogBlock(it, model, state) }
            state.looseTargets.forEach { TargetBlock(it, model, state) }
        }
        PlanButton("Plan with coach", filled = true, onClick = onPlanWithCoach)
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
        Text(
            "Goals change only when you tap. Every change of a goal's words or date is kept.",
            style = MaterialTheme.typography.bodySmall,
            color = clarity.muted,
            modifier = Modifier.padding(top = 12.dp, bottom = 24.dp),
        )
    }
}

@Composable
private fun GoalPage(
    g: PlanViewModel.GoalView,
    state: PlanViewModel.State,
    model: PlanViewModel,
    onBack: () -> Unit,
    onPlanWithCoach: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text("‹ All goals", color = clarity.yellow) }
        state.quietLine?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = clarity.danger) }
        StatusChip(g.status)
        Text(g.row.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = clarity.text)
        g.row.deadline?.let { Muted("Deadline ${model.day(it)}") }
        g.backlogs.forEach { BacklogBlock(it, model, state) }
        g.targets.forEach { TargetBlock(it, model, state) }
        g.plan?.let { p ->
            Column(Modifier.fillMaxWidth().border(1.dp, clarity.hairline, RoundedCornerShape(14.dp)).padding(12.dp)) {
                Text("Plan", style = MaterialTheme.typography.labelLarge, color = clarity.muted)
                Text("v${p.version} · review ${model.day(p.reviewDay)}", style = MaterialTheme.typography.bodyMedium, color = clarity.text)
                Muted(p.rationale)
            }
        }
        PlanButton("Plan with coach", filled = true, onClick = onPlanWithCoach)
        AddTargetForm(g.row.id, g.backlogs, state, model)
        AddBacklogForm(g.row.id, state, model)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlanButton("Mark done", filled = false, modifier = Modifier.weight(1f)) {
                model.setGoalStatus(g.row.id, "done")
                onBack()
            }
            PlanButton("Archive", filled = false, modifier = Modifier.weight(1f)) {
                model.setGoalStatus(g.row.id, "archived")
                onBack()
            }
        }
        Muted("Its targets and check-ins stay as they are, and can be ended one by one.")
        Text("", modifier = Modifier.padding(bottom = 24.dp))
    }
}

/** A backlog with the pace in the app's own numbers: need versus usual, where each pace lands, the Status. */
@Composable
private fun BacklogBlock(b: PlanViewModel.BacklogView, model: PlanViewModel, state: PlanViewModel.State) {
    val p = b.facts.projection
    Column(
        Modifier.fillMaxWidth().background(clarity.surface, RoundedCornerShape(16.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(b.row.name, style = MaterialTheme.typography.titleMedium, color = clarity.text)
        Muted("${model.n(b.remaining)} of ${model.n(b.row.total)} ${b.row.unit} left · deadline ${model.day(b.row.deadline)}")
        if (p.requiredPerDay != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                BigNumber("You need", model.perDayUp(p.requiredPerDay), "a day, ${p.daysLeft} scheduled days left", Modifier.weight(1f))
                p.typicalPerDay?.let {
                    BigNumber("Your usual pace", model.perDay(it), "a day, last ${Planner.WINDOW} days, ${b.facts.windowAnswered} answered", Modifier.weight(1f))
                }
            }
        }
        if (p.reachesTypical != null) {
            Muted("Where each pace lands by ${model.day(b.row.deadline)}")
            Text("At your usual pace: ${model.n(p.reachesTypical)} of ${model.n(p.remaining)}", color = clarity.text, style = MaterialTheme.typography.bodyMedium)
            if (p.reachesBest != null && p.bestPerDay != null) {
                Text(
                    "At your best fortnight (${model.perDay(p.bestPerDay)} a day): ${model.n(p.reachesBest)} of ${model.n(p.remaining)}",
                    color = clarity.text,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Text(model.statusText(p), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = if (p.status in setOf("short", "needs_best", "past_deadline")) clarity.danger else clarity.text)
        b.facts.answerRate?.let { Muted("Answer rate ${it.toInt()}%. Counted by the app, nothing estimated.") }
        if (b.linked.isEmpty()) Muted("No target is linked to it yet, so nothing counts toward it. Add a target for it on its goal.")
        Row {
            TextButton(onClick = { model.setBacklogStatus(b.row.id, "done") }) { Text("Mark done", color = clarity.yellow) }
            TextButton(onClick = { model.setBacklogStatus(b.row.id, "archived") }) { Text("Archive", color = clarity.muted) }
        }
    }
}

@Composable
private fun TargetBlock(t: PlanViewModel.TargetView, model: PlanViewModel, state: PlanViewModel.State) {
    Column(
        Modifier.fillMaxWidth().border(1.dp, clarity.hairline, RoundedCornerShape(14.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Target: ${t.row.name}", style = MaterialTheme.typography.titleSmall, color = clarity.text)
        Muted("${model.levelsLine(t.row)}, ${t.row.schedule}" + (t.row.endDay?.let { ", until ${model.day(it)}" } ?: ""))
        Muted(
            if (t.answered == 0) {
                "No answers in the last ${Planner.WINDOW} days."
            } else {
                "Last ${Planner.WINDOW} days: done ${t.done} of ${t.answered} answered days."
            },
        )
        t.reasons.forEach { Muted("Not or Part: $it.") }
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
private fun BigNumber(label: String, value: String, under: String, modifier: Modifier) {
    Column(modifier) {
        Muted(label)
        Text(value, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = clarity.yellow)
        Muted(under)
    }
}

@Composable
private fun StatusChip(status: String) {
    val bad = status == "behind"
    Text(
        status.replaceFirstChar { it.uppercase() },
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = if (bad) clarity.ground else clarity.text,
        modifier = Modifier
            .then(if (bad) Modifier.background(clarity.coral, RoundedCornerShape(12.dp)) else Modifier.border(1.dp, clarity.controlOutline, RoundedCornerShape(12.dp)))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
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
