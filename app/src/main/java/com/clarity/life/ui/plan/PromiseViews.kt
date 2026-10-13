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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.clarity.life.ui.PromiseTimeDialog
import com.clarity.life.ui.theme.clarity
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import com.clarity.life.ui.theme.BoldCard
import com.clarity.life.ui.theme.Headline
import com.clarity.life.ui.theme.Kicker
import com.clarity.life.ui.theme.OutlineCard
import com.clarity.life.ui.theme.Quiet
import com.clarity.life.ui.theme.TextAction

/** Done, Not done (then reason chips), Moved and optionally Let go: each one tap, each one command. */
@Composable
fun Answers(item: PromisesViewModel.Item, model: PromisesViewModel, onYellow: Boolean, withLetGo: Boolean) {
    var askingReason by rememberSaveable(item.row.id) { mutableStateOf(false) }
    var moving by rememberSaveable(item.row.id) { mutableStateOf(false) }
    val ink = if (onYellow) clarity.ground else clarity.text
    val fill = if (onYellow) clarity.ground else clarity.text
    val onFill = if (onYellow) clarity.text else clarity.ground

    if (askingReason) {
        Text("What got in the way?", style = MaterialTheme.typography.bodySmall, color = ink)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            model.reasons.forEach { reason ->
                Chip(reason, ink) {
                    askingReason = false
                    model.notDone(item.row.id, reason)
                }
            }
            Chip("Skip", ink) {
                askingReason = false
                model.notDone(item.row.id, null)
            }
        }
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AnswerButton("Done", filled = true, fill = fill, onFill = onFill, ink = ink, Modifier.weight(1f)) { model.done(item.row.id) }
        AnswerButton("Not done", filled = false, fill = fill, onFill = onFill, ink = ink, Modifier.weight(1f)) { askingReason = true }
        AnswerButton("Moved", filled = false, fill = fill, onFill = onFill, ink = ink, Modifier.weight(1f)) { moving = true }
        if (withLetGo) {
            AnswerButton("Let go", filled = false, fill = fill, onFill = onFill, ink = ink, Modifier.weight(1f)) { model.letGo(item.row.id) }
        }
    }
    if (moving) {
        val (date, time) = model.localDue(item.row)
        PromiseTimeDialog(
            title = "Move \"${item.row.text}\"",
            hint = "The old time is kept as moved, and the promise starts again at the new one.",
            initialDate = model.today().coerceAtLeast(date),
            initialTime = time,
            today = model.today(),
            show = model::show,
            isAhead = model::isAhead,
            confirmLabel = "Move",
            onConfirm = { d, t ->
                moving = false
                model.move(item.row.id, d, t)
            },
            onDismiss = { moving = false },
        )
    }
}

@Composable
private fun AnswerButton(
    label: String,
    filled: Boolean,
    fill: Color,
    onFill: Color,
    ink: Color,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = if (filled) FontWeight.SemiBold else FontWeight.Medium,
        color = if (filled) onFill else ink,
        modifier = modifier
            .heightIn(min = 48.dp)
            .then(
                if (filled) {
                    Modifier.background(fill, shape)
                } else {
                    Modifier.background(clarity.raised, shape).border(1.dp, clarity.controlOutline, shape)
                },
            )
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

@Composable
internal fun Chip(label: String, ink: Color, onClick: () -> Unit) = Text(
    label,
    style = MaterialTheme.typography.labelMedium,
    color = ink,
    modifier = Modifier
        .heightIn(min = 44.dp)
        .border(1.dp, clarity.controlOutline, RoundedCornerShape(999.dp))
        .clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 12.dp),
)

/**
 * Plan, Promises, redesigned (the owner's approved mockup of 13 Oct 2026): a headline that says what needs
 * him now, a one-line record of the recent ones, what needs an answer as cards, what is coming as dated
 * tiles, and the past folded into one line until he opens it. Past outcomes are kept forever; an answer
 * from the last day can be undone.
 */
@Composable
fun PromisesSection(state: PromisesViewModel.State, model: PromisesViewModel) {
    var showPast by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 12.dp)) {
        state.quietLine?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = clarity.danger) }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Headline(
                when {
                    state.due.size == 1 -> "One needs an answer."
                    state.due.size > 1 -> "${state.due.size} need an answer."
                    state.coming.isNotEmpty() -> "Nothing waiting."
                    state.past.isNotEmpty() -> "All answered."
                    else -> "No promises yet."
                },
            )
            val week = state.coming.count { !model.localDue(it.row).first.isAfter(model.today().plusDays(6)) }
            val recent = state.past.take(5)
            val kept = recent.count { it.row.status == "kept" }
            val parts = listOfNotNull(
                when (week) {
                    0 -> null
                    1 -> "One coming up this week."
                    else -> "$week coming up this week."
                },
                if (recent.size >= 2) "$kept of the last ${recent.size} kept." else null,
            )
            if (parts.isNotEmpty()) Quiet(parts.joinToString(" "))
            if (state.due.isEmpty() && state.coming.isEmpty() && state.past.isEmpty()) {
                Quiet("A promise is one thing you said you will do, at a time. They come only from cards you set in Talk.")
            }
        }

        state.due.forEach { item ->
            BoldCard(gap = 12) {
                Kicker(item.kicker, clarity.yellow)
                Text(item.row.text, style = MaterialTheme.typography.headlineSmall, color = clarity.text)
                Answers(item, model, onYellow = false, withLetGo = true)
            }
        }

        if (state.coming.isNotEmpty()) {
            Kicker("Coming up", modifier = Modifier.padding(start = 4.dp, top = 8.dp))
            BoldCard(gap = 0) {
                state.coming.forEachIndexed { i, item ->
                    if (i > 0) HorizontalDivider(color = clarity.hairline)
                    ComingRow(item, model)
                }
            }
        }

        if (state.past.isNotEmpty()) {
            OutlineCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Past", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = clarity.text)
                        val kept = state.past.count { it.row.status == "kept" }
                        Quiet("${state.past.size} ${if (state.past.size == 1) "promise" else "promises"} · $kept kept")
                    }
                    TextAction(if (showPast) "Hide" else "See all") { showPast = !showPast }
                }
                if (showPast) {
                    state.past.forEach { item ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(item.row.text, style = MaterialTheme.typography.bodyMedium, color = clarity.text)
                                val reason = item.row.reason?.let { " · reason: $it" }.orEmpty()
                                Muted(item.time + reason)
                            }
                            Text(OUTCOMES[item.row.status] ?: item.row.status, style = MaterialTheme.typography.labelMedium, color = clarity.muted)
                            if (model.canUndo(item.row)) {
                                TextButton(onClick = { model.undo(item.row.id) }) { Text("Undo", color = clarity.yellow) }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

private val OUTCOMES = mapOf("kept" to "Done", "not_done" to "Not done", "moved" to "Moved", "let_go" to "Let go")
private val WEEKDAY_SHORT = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")

/** A promise ahead: a date tile, the words, the time and whether a reminder rings. Tap to answer it early. */
@Composable
private fun ComingRow(item: PromisesViewModel.Item, model: PromisesViewModel) {
    var open by rememberSaveable(item.row.id) { mutableStateOf(false) }
    val (date, _) = model.localDue(item.row)
    Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier.width(52.dp).background(clarity.raised, RoundedCornerShape(14.dp)).padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(WEEKDAY_SHORT[date.dayOfWeek.value - 1], style = MaterialTheme.typography.labelSmall, color = clarity.muted)
                Text("${date.dayOfMonth}", style = MaterialTheme.typography.headlineSmall, color = clarity.text)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.row.text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = clarity.text)
                Quiet(item.time + if (item.row.remind == 1) " · reminder on" else " · no reminder")
            }
        }
        if (open) Answers(item, model, onYellow = false, withLetGo = true)
    }
}

@Composable
internal fun Heading(text: String) =
    Text(text, style = MaterialTheme.typography.titleMedium, color = clarity.text, modifier = Modifier.padding(top = 10.dp))

@Composable
internal fun Muted(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = clarity.muted)
