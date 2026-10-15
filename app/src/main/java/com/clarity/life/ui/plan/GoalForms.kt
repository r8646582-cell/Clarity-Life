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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.clarity.life.core.Planner
import com.clarity.life.ui.theme.Quiet
import com.clarity.life.ui.theme.clarity

private val AREAS = listOf("studies", "health", "habits", "mind", "money", "relationships", "meaning", "rest_joy", "general")

internal fun number(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

/** What a typed date reads as, shown under the field before he saves (the same code reads card dates). */
internal fun dayHint(model: PlanViewModel, state: PlanViewModel.State, text: String, empty: String): String =
    if (text.isBlank()) empty else model.readDay(text, state.today)?.let { "Reads as ${model.showDay(it)}" }
        ?: "Cannot read that date. Try 20 Nov, Sat or 2026-11-20 (not in the past)."

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

@Composable
internal fun Field(label: String, value: String, onChange: (String) -> Unit, number: Boolean = false, hint: String? = null) {
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

@Composable
internal fun FormBox(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(clarity.raised, RoundedCornerShape(16.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = clarity.text)
        content()
    }
}

@Composable
internal fun FormButtons(ok: Boolean, onCancel: () -> Unit, onSave: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PlanButton("Cancel", filled = false, modifier = Modifier.weight(1f), onClick = onCancel)
        PlanButton(if (ok) "Save" else "Fill in the fields", filled = ok, modifier = Modifier.weight(1f)) { if (ok) onSave() }
    }
}

@Composable
internal fun AddGoalForm(state: PlanViewModel.State, model: PlanViewModel) {
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
internal fun AddTargetForm(goalId: Long, backlogs: List<PlanViewModel.BacklogView>, state: PlanViewModel.State, model: PlanViewModel) {
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
internal fun AddBacklogForm(goalId: Long, state: PlanViewModel.State, model: PlanViewModel) {
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

/** Seven fields, Monday to Sunday, in hours (1.5 is an hour and a half); stored as minutes. */
@Composable
internal fun HoursForm(hours: List<Double>?, onDone: () -> Unit, model: PlanViewModel) {
    val names = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
    val fields = names.indices.map { i ->
        rememberSaveable(i) { mutableStateOf(hours?.let { model.n(it[i] / 60.0) } ?: "") }
    }
    FormBox("Hours you have for your plans") {
        Quiet("The time you can really give your goals on a usual day, after work, school and rest.")
        names.forEachIndexed { i, n ->
            Field(n, fields[i].value, { fields[i].value = it.take(5) }, number = true)
        }
        val values = fields.map { f -> if (f.value.isBlank()) 0.0 else number(f.value) }
        val ok = values.all { it != null && it >= 0 && it <= 24 }
        FormButtons(ok, onCancel = onDone) {
            model.setHours(values.map { Planner.roundHalfUp(it!! * 60.0) })
            onDone()
        }
        if (hours != null) {
            TextButton(onClick = {
                model.setHours(null)
                onDone()
            }) { Text("Clear my hours", color = clarity.muted) }
        }
    }
}
