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

/**
 * The due card at the top of Talk (docs/mockups/Talk.dc.html), written by the app, never by the coach:
 * what is due on his day or earlier and has no answer yet, at most two items, with Done, Not done (then the
 * reason chips) and Moved. "N more" opens Plan, Promises.
 */
@Composable
fun DueCard(state: PromisesViewModel.State, model: PromisesViewModel, onSeeAll: () -> Unit) {
    if (state.due.isEmpty()) return
    val shown = state.due.take(com.clarity.life.core.PromiseText.DUE_CARD_MAX)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 6.dp)
            .background(clarity.yellow, RoundedCornerShape(16.dp))
            .padding(start = 16.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        shown.forEachIndexed { i, item ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.kicker.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = clarity.ground,
                        modifier = Modifier.weight(1f),
                    )
                    val more = state.due.size - shown.size
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
                Text(item.row.text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = clarity.ground)
                Answers(item, model, onYellow = true, withLetGo = false)
            }
        }
    }
}

/** Done, Not done (then reason chips), Moved and optionally Let go: each one tap, each one command. */
@Composable
fun Answers(item: PromisesViewModel.Item, model: PromisesViewModel, onYellow: Boolean, withLetGo: Boolean) {
    var askingReason by rememberSaveable(item.row.id) { mutableStateOf(false) }
    var moving by rememberSaveable(item.row.id) { mutableStateOf(false) }
    val ink = if (onYellow) clarity.ground else clarity.text
    val fill = if (onYellow) clarity.ground else clarity.yellow
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
    val shape = RoundedCornerShape(10.dp)
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = if (filled) onFill else ink,
        modifier = modifier
            .heightIn(min = 44.dp)
            .then(if (filled) Modifier.background(fill, shape) else Modifier.border(2.dp, ink, shape))
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
        .border(1.dp, ink, RoundedCornerShape(20.dp))
        .clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 12.dp),
)

/**
 * Plan, Promises (docs/mockups/Promises.dc.html): needs an answer, coming up, and past outcomes, which are
 * kept forever. An answer from the last day can be undone.
 */
@Composable
fun PromisesSection(state: PromisesViewModel.State, model: PromisesViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        state.quietLine?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = clarity.danger) }

        Heading("Needs an answer")
        if (state.due.isEmpty()) Muted("Nothing waiting for an answer.")
        state.due.forEach { item ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(clarity.surface, RoundedCornerShape(16.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(item.row.text, style = MaterialTheme.typography.titleMedium, color = clarity.text)
                Muted(item.kicker)
                Answers(item, model, onYellow = false, withLetGo = true)
            }
        }

        Heading("Coming up")
        if (state.coming.isEmpty()) Muted("No promises ahead. They come only from cards you set in Talk.")
        state.coming.forEach { item -> ComingRow(item, model) }

        Heading("Past")
        if (state.past.isEmpty()) Muted("Answered promises stay here, with what happened.")
        state.past.forEach { item ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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

        Text(
            "Promises come only from cards you tapped. Reminders ring only for the ones you asked for.",
            style = MaterialTheme.typography.bodySmall,
            color = clarity.muted,
            modifier = Modifier.padding(top = 12.dp, bottom = 24.dp),
        )
    }
}

private val OUTCOMES = mapOf("kept" to "Done", "not_done" to "Not done", "moved" to "Moved", "let_go" to "Let go")

@Composable
private fun ComingRow(item: PromisesViewModel.Item, model: PromisesViewModel) {
    var open by rememberSaveable(item.row.id) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, clarity.hairline, RoundedCornerShape(14.dp))
            .clickable { open = !open }
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(item.row.text, style = MaterialTheme.typography.bodyMedium, color = clarity.text)
        Muted(item.time + if (item.row.remind == 1) " · reminder on" else " · no reminder")
        if (open) Answers(item, model, onYellow = false, withLetGo = true)
    }
}

@Composable
internal fun Heading(text: String) =
    Text(text, style = MaterialTheme.typography.titleMedium, color = clarity.text, modifier = Modifier.padding(top = 10.dp))

@Composable
internal fun Muted(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = clarity.muted)
