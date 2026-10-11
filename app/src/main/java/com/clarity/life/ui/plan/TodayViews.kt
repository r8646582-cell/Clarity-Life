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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
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
import com.clarity.life.ui.theme.clarity

/**
 * Plan, Today (docs/mockups/Today.dc.html): the daily check-in. One card per active target scheduled today:
 * Did it / Part / Not, an amount stepper when the target has a unit, and after Not or Part the reason chips
 * and an optional line. A planned rest day is neither done nor missed. Unanswered stays "no answer".
 */
@Composable
fun TodaySection(state: PlanViewModel.State, model: PlanViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        state.quietLine?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = clarity.danger) }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Bottom) {
            Text(state.todayLabel, style = MaterialTheme.typography.titleLarge, color = clarity.text, modifier = Modifier.weight(1f))
            if (state.todayRows.isNotEmpty() && !state.restToday) {
                Muted("${state.answeredToday} of ${state.todayRows.size} answered")
            }
        }
        if (state.restToday) {
            Column(
                Modifier.fillMaxWidth().background(clarity.surface, RoundedCornerShape(16.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Rest day", style = MaterialTheme.typography.titleMedium, color = clarity.text)
                Muted("Planned. It counts as neither done nor missed, and it is not counted in days left.")
                TextButton(onClick = { model.setRest(state.today, false) }) { Text("Not a rest day after all", color = clarity.yellow) }
            }
            return@Column
        }
        if (state.todayRows.isEmpty()) {
            Muted(
                "Nothing is scheduled today. Targets come from a plan you save in a Plan and track chat, " +
                    "or from Add a target on a goal.",
            )
        }
        state.todayRows.forEach { row -> TodayCard(row, state, model) }
        Muted("Unanswered stays \"no answer\". Nothing is guessed.")
        if (state.todayRows.isNotEmpty()) {
            Text(
                "Make today a rest day",
                style = MaterialTheme.typography.labelLarge,
                color = clarity.text,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .border(1.dp, clarity.controlOutline, RoundedCornerShape(12.dp))
                    .clickable { model.setRest(state.today, true) }
                    .padding(vertical = 12.dp),
            )
        }
        Muted("A rest day pauses every target for the day.")
    }
}

@Composable
private fun TodayCard(row: PlanViewModel.TodayRow, state: PlanViewModel.State, model: PlanViewModel) {
    val t = row.target
    val a = row.answer
    val hasPart = t.minimum > 0 && t.minimum < t.target
    Column(
        Modifier
            .fillMaxWidth()
            .background(clarity.surface, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = clarity.text)
                Muted(model.levelsLine(t))
            }
            if (a != null) TextButton(onClick = { model.clear(row, state.today) }) { Text("Clear", color = clarity.muted) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val done = a != null && a.level in setOf("target", "beyond")
            LevelButton(if (a?.level == "beyond") "Beyond" else "Did it", done, Modifier.weight(1f)) {
                model.answer(row, "target", state.today)
            }
            if (hasPart) {
                LevelButton("Part", a?.level == "minimum", Modifier.weight(1f)) { model.answer(row, "minimum", state.today) }
            }
            LevelButton("Not", a?.level == "not", Modifier.weight(1f)) { model.answer(row, "not", state.today) }
        }
        if (row.quantity) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("How many?", style = MaterialTheme.typography.bodyMedium, color = clarity.text, modifier = Modifier.weight(1f))
                val amount = a?.amount ?: 0.0
                Stepper("−") { model.setAmount(row, amount - 1, state.today) }
                Text(
                    "${model.n(amount)} ${t.unit}",
                    style = MaterialTheme.typography.titleMedium,
                    color = clarity.text,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(min = 72.dp),
                )
                Stepper("+") { model.setAmount(row, amount + 1, state.today) }
            }
        }
        if (a != null && a.level in Planner.SHORT_LEVELS) {
            Text("What got in the way?", style = MaterialTheme.typography.bodySmall, color = clarity.text)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                model.reasons.forEach { r ->
                    ReasonChip(r, a.reason == r) { model.reason(row, if (a.reason == r) null else r, state.today) }
                }
            }
            var note by rememberSaveable(t.id, a.dayKey) { mutableStateOf(a.note.orEmpty()) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(200) },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("One line about it, optional", color = clarity.muted) },
                    colors = planFieldColors(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                )
                if (note != a.note.orEmpty()) {
                    TextButton(onClick = { model.note(row, note, state.today) }) { Text("Save", color = clarity.yellow) }
                }
            }
        }
    }
}

@Composable
private fun LevelButton(label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = if (on) clarity.ground else clarity.text,
        textAlign = TextAlign.Center,
        modifier = modifier
            .heightIn(min = 44.dp)
            .then(if (on) Modifier.background(clarity.yellow, shape) else Modifier.border(2.dp, clarity.controlOutline, shape))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    )
}

@Composable
private fun Stepper(label: String, onClick: () -> Unit) = Text(
    label,
    style = MaterialTheme.typography.titleLarge,
    color = clarity.text,
    textAlign = TextAlign.Center,
    modifier = Modifier
        .widthIn(min = 44.dp)
        .heightIn(min = 44.dp)
        .border(1.dp, clarity.controlOutline, RoundedCornerShape(10.dp))
        .clickable(onClick = onClick)
        .padding(vertical = 8.dp),
)

@Composable
private fun ReasonChip(label: String, on: Boolean, onClick: () -> Unit) = Text(
    label,
    style = MaterialTheme.typography.labelMedium,
    color = if (on) clarity.ground else clarity.text,
    modifier = Modifier
        .heightIn(min = 44.dp)
        .then(
            if (on) {
                Modifier.background(clarity.yellow, RoundedCornerShape(20.dp))
            } else {
                Modifier.border(1.dp, clarity.controlOutline, RoundedCornerShape(20.dp))
            },
        )
        .clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 12.dp),
)

@Composable
internal fun planFieldColors() = TextFieldDefaults.colors(
    focusedTextColor = clarity.text,
    unfocusedTextColor = clarity.text,
    focusedContainerColor = clarity.surface,
    unfocusedContainerColor = clarity.surface,
    focusedIndicatorColor = clarity.controlOutline,
    unfocusedIndicatorColor = clarity.hairline,
    cursorColor = clarity.yellow,
)

/**
 * Today's check-ins on the due card at the top of Talk (spec section 2.1): written by the app, only the targets
 * scheduled today with no answer yet, in the places the promises left ([room]). Did it, Part, Not; the reason
 * chips and the amount are on Plan, Today.
 */
@Composable
fun DueCheckins(state: PlanViewModel.State, model: PlanViewModel, room: Int, onSeeAll: () -> Unit) {
    if (state.restToday || room <= 0) return
    val open = state.todayRows.filter { it.answer == null }
    if (open.isEmpty()) return
    val shown = open.take(room)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 6.dp)
            .background(clarity.yellow, RoundedCornerShape(16.dp))
            .padding(start = 16.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        shown.forEachIndexed { i, row ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "TODAY'S CHECK-IN",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = clarity.ground,
                        modifier = Modifier.weight(1f),
                    )
                    val more = open.size - shown.size
                    if (i == 0 && more > 0) {
                        Text(
                            "$more more",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = clarity.ground,
                            modifier = Modifier.clickable(onClick = onSeeAll).padding(4.dp),
                        )
                    }
                }
                Text(row.target.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = clarity.ground)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val t = row.target
                    DueButton("Did it", filled = true, Modifier.weight(1f)) { model.answer(row, "target", state.today) }
                    if (t.minimum > 0 && t.minimum < t.target) {
                        DueButton("Part", filled = false, Modifier.weight(1f)) { model.answer(row, "minimum", state.today); onSeeAll() }
                    }
                    DueButton("Not", filled = false, Modifier.weight(1f)) { model.answer(row, "not", state.today); onSeeAll() }
                }
            }
        }
    }
}

@Composable
private fun DueButton(label: String, filled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = if (filled) clarity.text else clarity.ground,
        textAlign = TextAlign.Center,
        modifier = modifier
            .heightIn(min = 44.dp)
            .then(if (filled) Modifier.background(clarity.ground, shape) else Modifier.border(2.dp, clarity.ground, shape))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    )
}
