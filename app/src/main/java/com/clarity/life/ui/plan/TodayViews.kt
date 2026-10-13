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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import com.clarity.life.ui.theme.BoldCard
import com.clarity.life.ui.theme.Headline
import com.clarity.life.ui.theme.Quiet
import com.clarity.life.ui.theme.TextAction

/**
 * Plan, Today: the daily check-in, redesigned (the owner's approved mockup of 13 Oct 2026). A headline that fits
 * the day (day 1, all answered, it starts tomorrow), then one card per target scheduled today: big number
 * buttons for an amount (Did it / Part / Not when there is no unit), and once answered, what it means for the
 * plan in the app's own numbers. What got in the way is asked only below the target. Unanswered stays "no
 * answer"; a planned rest day is neither done nor missed.
 */
@Composable
fun TodaySection(state: PlanViewModel.State, model: PlanViewModel, brief: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 12.dp)) {
        state.quietLine?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = clarity.danger) }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
            Quiet(model.longDay(state.today))
            Headline(todayHeadline(state, model))
        }
        // This morning's brief (spec section 8), the same lines Talk home shows.
        brief?.let { com.clarity.life.ui.talk.BriefCard(it) }
        if (state.restToday) {
            BoldCard(gap = 8) {
                Text("Rest day", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = clarity.text)
                Quiet("Planned. It counts as neither done nor missed, and it is not counted in days left.")
                TextAction("Not a rest day after all") { model.setRest(state.today, false) }
            }
            return@Column
        }
        if (state.todayRows.isEmpty()) {
            Quiet(
                if (model.nextStart(state) != null) {
                    "Your first check-in shows here on the day your plan starts."
                } else {
                    "Targets come from a plan you save in a Plan and track chat, or from Edit on a goal."
                },
            )
        }
        state.todayRows.forEach { row -> TodayCard(row, state, model) }
        if (state.todayRows.isNotEmpty()) {
            com.clarity.life.ui.theme.SecondaryButton("Make today a rest day") { model.setRest(state.today, true) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** The one line that fits today. */
private fun todayHeadline(state: PlanViewModel.State, model: PlanViewModel): String {
    if (state.restToday) return "A rest day. Nothing to answer."
    if (state.todayRows.isEmpty()) {
        val next = model.nextStart(state)
        return when {
            next == null -> "Nothing scheduled today."
            next == state.today.plusDays(1) -> "Your plan starts tomorrow."
            else -> "Your plan starts ${model.showDay(next)}."
        }
    }
    val open = state.todayRows.size - state.answeredToday
    val day = model.planDay(state)
    return when {
        open == 0 -> "All answered. That is the habit."
        day == 1L -> "Day 1. Start small, start today."
        open == 1 -> "One to answer today."
        else -> "$open to answer today."
    }
}

@Composable
private fun TodayCard(row: PlanViewModel.TodayRow, state: PlanViewModel.State, model: PlanViewModel) {
    val t = row.target
    val a = row.answer
    val hasPart = t.minimum > 0 && t.minimum < t.target
    BoldCard(gap = 14) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = clarity.text)
                Quiet(
                    model.levelsLine(t).replace(" · ", ", ") + (if (row.steps > 1) " · step ${row.step} of ${row.steps}" else ""),
                )
            }
            if (a != null) TextAction("Clear", color = clarity.muted) { model.clear(row, state.today) }
        }
        if (row.quantity && t.target <= MAX_BUTTON) {
            Quiet("How many did you do?")
            val top = (kotlin.math.ceil(t.target).toInt() + 1).coerceAtMost(MAX_BUTTON.toInt() + 1)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (0..top).forEach { k ->
                    val chosen = a != null && a.level != "rest" &&
                        (if (k == top) a.amount >= k - 1e-9 else kotlin.math.abs(a.amount - k) < 1e-9)
                    NumberButton(if (k == top) "$k+" else "$k", chosen, Modifier.weight(1f)) {
                        if (k == 0) model.answer(row, "not", state.today) else model.setAmount(row, k.toDouble(), state.today)
                    }
                }
            }
            if (a != null && a.amount >= top - 1e-9) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Quiet("More than that?", Modifier.weight(1f))
                    Stepper("−") { model.setAmount(row, a.amount - 1, state.today) }
                    Text(
                        "${model.n(a.amount)} ${t.unit}",
                        style = MaterialTheme.typography.titleMedium,
                        color = clarity.text,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(min = 72.dp),
                    )
                    Stepper("+") { model.setAmount(row, a.amount + 1, state.today) }
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val done = a != null && a.level in setOf("target", "beyond")
                NumberButton(if (a?.level == "beyond") "Beyond" else "Did it", done, Modifier.weight(1f)) {
                    model.answer(row, "target", state.today)
                }
                if (hasPart) {
                    NumberButton("Part", a?.level == "minimum", Modifier.weight(1f)) { model.answer(row, "minimum", state.today) }
                }
                NumberButton("Not", a?.level == "not", Modifier.weight(1f)) { model.answer(row, "not", state.today) }
            }
            if (row.quantity) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Quiet("How many?", Modifier.weight(1f))
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
        }
        if (a != null) {
            Column(
                Modifier.fillMaxWidth().background(clarity.raised, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(model.answeredLine(row), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = clarity.yellow)
                model.planLine(row)?.let { Quiet(it) }
            }
        }
        if (a != null && a.level in Planner.SHORT_LEVELS) {
            Text("What got in the way?", style = MaterialTheme.typography.bodyMedium, color = clarity.text)
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
            Quiet("It is information, never a mark against you.")
        } else if (a == null && hasPart) {
            Quiet("Below ${model.n(t.minimum)}, it asks what got in the way, in one tap.")
        }
    }
}

/** Amounts above this are entered with the stepper; below it, one big button per number. */
private const val MAX_BUTTON = 5.0

@Composable
private fun NumberButton(label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Text(
        label,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        color = if (on) clarity.ground else clarity.text,
        textAlign = TextAlign.Center,
        modifier = modifier
            .heightIn(min = 56.dp)
            .then(if (on) Modifier.background(clarity.yellow, shape) else Modifier.border(1.dp, clarity.controlOutline, shape))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
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
