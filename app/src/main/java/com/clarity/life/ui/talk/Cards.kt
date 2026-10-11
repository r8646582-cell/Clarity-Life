package com.clarity.life.ui.talk

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.clarity.life.core.PromiseTimes
import com.clarity.life.data.entity.CardRow
import com.clarity.life.ui.PromiseTimeDialog
import com.clarity.life.ui.theme.clarity
import java.time.LocalDate
import java.time.LocalTime

/**
 * What a card under a coach reply can do. Every action is a tap of his; the card never acts by itself.
 */
class CardActions(
    val fieldsOf: (CardRow) -> Map<String, String>,
    val promiseTime: (CardRow, String) -> PromiseTimes.Resolution,
    val show: (LocalDate, LocalTime) -> String,
    val isAhead: (LocalDate, LocalTime) -> Boolean,
    val today: () -> LocalDate,
    /** Card id, the moment, and whether the card asked for a reminder. */
    val setPromise: (Long, LocalDate, LocalTime, Boolean) -> Unit,
    val keepNote: (Long) -> Unit,
    val startFocus: (Long) -> Unit,
    val switchMode: (Long) -> Unit,
    val notNow: (Long) -> Unit,
    val openPromises: () -> Unit,
    /** Save plan, Change it or Save on a plan, target change or backlog card. */
    val savePlan: (Long) -> Unit = {},
    val openPlan: () -> Unit = {},
    /** F3 to the focus's name, for a mode card into a focus; null when it is not active. */
    val focusName: (String?) -> String? = { null },
)

private val NOTE_KINDS = mapOf(
    "pattern" to "pattern",
    "what_helps" to "what helps",
    "what_doesnt" to "what doesn't help",
    "value" to "value",
    "thread" to "open thread",
)

private val MODE_NAMES = mapOf(
    "free" to "Free talk",
    "untangle" to "Untangle",
    "decide" to "Decide",
    "plan" to "Plan and track",
)

/**
 * A suggestion card (docs/mockups/Talk.dc.html and TalkStates.dc.html): coral while offered, a dark Saved
 * receipt built from the database row once he taps, one quiet line once he says Not now.
 */
@Composable
fun SuggestionCard(
    card: CardRow,
    receipt: String?,
    line: String?,
    preview: com.clarity.life.chat.CardSaver.Preview?,
    actions: CardActions,
) {
    when (card.status) {
        "saved" -> SavedReceipt(
            receipt ?: "Saved earlier. It has since been changed or removed.",
            onView = when {
                receipt == null -> null
                card.type == "promise" -> actions.openPromises
                card.type in com.clarity.life.chat.CardSaver.PLAN_TYPES -> actions.openPlan
                else -> null
            },
        )
        "dismissed" -> Text(
            "Not saved. The coach will not offer it again in this chat.",
            style = MaterialTheme.typography.bodySmall,
            color = clarity.muted,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp),
        )
        else -> OfferedCard(card, line, preview, actions)
    }
}

@Composable
private fun OfferedCard(card: CardRow, line: String?, preview: com.clarity.life.chat.CardSaver.Preview?, actions: CardActions) {
    val f = actions.fieldsOf(card)
    var picking by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .background(clarity.coral, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (card.type) {
            "promise" -> {
                val res = actions.promiseTime(card, f["when"].orEmpty())
                val remind = f["remind"] == "true"
                Kicker("Suggested promise")
                Headline(f["text"].orEmpty())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (res.ok) Chip(actions.show(res.date!!, res.time!!)) else Chip("Pick a time")
                    Chip(if (remind) "Reminder on" else "No reminder")
                }
                if (!res.ok) {
                    Body(
                        if (res.date != null || res.time != null) {
                            "\"${f["when"].orEmpty()}\" could mean more than one time. Check the day and time before you set it."
                        } else {
                            "The app could not read \"${f["when"].orEmpty()}\" as a time, so you choose it."
                        },
                    )
                }
                line?.let { Body(it) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (res.ok) {
                        Primary("Set it") { actions.setPromise(card.id, res.date!!, res.time!!, remind) }
                        Plain("Change", underline = true) { picking = true }
                    } else {
                        Primary("Pick a time") { picking = true }
                    }
                    Spacer(Modifier.weight(1f))
                    Plain("Not now") { actions.notNow(card.id) }
                }
                if (picking) {
                    PromiseTimeDialog(
                        title = f["text"].orEmpty(),
                        hint = "Nothing is saved until you tap Set it.",
                        initialDate = res.date,
                        initialTime = res.time,
                        today = actions.today(),
                        show = actions.show,
                        isAhead = actions.isAhead,
                        confirmLabel = "Set it",
                        onConfirm = { d, t ->
                            picking = false
                            actions.setPromise(card.id, d, t, remind)
                        },
                        onDismiss = { picking = false },
                    )
                }
            }

            "note" -> {
                Kicker("Keep this? (${NOTE_KINDS[f["kind"]] ?: "note"})")
                Headline(f["text"].orEmpty())
                Body("It goes into What I know as something you confirmed. You can edit or delete it there.")
                line?.let { Body(it) }
                Buttons("Keep it", { actions.keepNote(card.id) }, { actions.notNow(card.id) })
            }

            "focus" -> {
                Kicker("Suggested focus")
                Headline(f["name"].orEmpty())
                f["why"]?.let { Body(it) }
                line?.let { Body(it) }
                Buttons("Start it", { actions.startFocus(card.id) }, { actions.notNow(card.id) })
            }

            "mode" -> {
                val to = if (f["to"] == "focus") {
                    actions.focusName(f["focus"])?.let { "your focus $it" }
                } else {
                    MODE_NAMES[f["to"]]
                }
                Headline(if (to != null) "Switch to $to?" else "Switch the kind of conversation?")
                line?.let { Body(it) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (to != null) Primary("Switch") { actions.switchMode(card.id) }
                    Spacer(Modifier.weight(1f))
                    Plain("Stay here") { actions.notNow(card.id) }
                }
            }

            else -> {
                // Plan, target change and backlog: the card shows, line by line, exactly what a tap writes.
                Kicker(
                    when (card.type) {
                        "plan" -> "Suggested plan"
                        "target_change" -> "Suggested change"
                        else -> "Suggested backlog"
                    },
                )
                Headline(f["title"] ?: f["name"] ?: f["target"]?.let { "Change $it" } ?: f["backlog"]?.let { "Change $it" } ?: "")
                (f["rationale"] ?: f["reason"])?.let { Body(it) }
                when (preview) {
                    is com.clarity.life.chat.CardSaver.Preview.Ready -> {
                        Column(
                            Modifier.fillMaxWidth().background(Color(0x2414121A), RoundedCornerShape(10.dp)).padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text("What will be saved", style = MaterialTheme.typography.labelSmall, color = clarity.ground)
                            preview.lines.forEach { Text("· $it", style = MaterialTheme.typography.bodyMedium, color = clarity.ground) }
                        }
                        line?.let { Body(it) }
                        Buttons(
                            when (card.type) {
                                "plan" -> "Save plan"
                                "target_change" -> "Change it"
                                else -> "Save"
                            },
                            { actions.savePlan(card.id) },
                            { actions.notNow(card.id) },
                        )
                    }
                    is com.clarity.life.chat.CardSaver.Preview.Blocked -> {
                        Body(TalkViewModel.blockedLine(preview.why))
                        Row {
                            Spacer(Modifier.weight(1f))
                            Plain("Not now") { actions.notNow(card.id) }
                        }
                    }
                    null -> Body("Working out what this would save...")
                }
            }
        }
    }
}

@Composable
private fun SavedReceipt(text: String, onView: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .background(clarity.surface, RoundedCornerShape(16.dp))
            .border(1.dp, clarity.hairline, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(32.dp).background(clarity.yellow, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Check, contentDescription = null, tint = clarity.ground, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text("Saved", style = MaterialTheme.typography.labelLarge, color = clarity.text)
            Text(text, style = MaterialTheme.typography.bodySmall, color = clarity.muted)
        }
        if (onView != null) {
            TextButton(onClick = onView, modifier = Modifier.heightIn(min = 44.dp)) {
                Text("View", color = clarity.yellow)
            }
        }
    }
}

@Composable
private fun Kicker(text: String) =
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = clarity.ground)

@Composable
private fun Headline(text: String) =
    Text(text, style = MaterialTheme.typography.titleLarge, color = clarity.ground)

@Composable
private fun Body(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = clarity.ground)

@Composable
private fun Chip(text: String) = Text(
    text,
    style = MaterialTheme.typography.labelMedium,
    color = clarity.ground,
    modifier = Modifier
        .background(Color(0x2414121A), RoundedCornerShape(8.dp))
        .padding(horizontal = 10.dp, vertical = 5.dp),
)

@Composable
private fun Primary(label: String, onClick: () -> Unit) = Button(
    onClick = onClick,
    modifier = Modifier.heightIn(min = 44.dp),
    shape = RoundedCornerShape(10.dp),
    colors = ButtonDefaults.buttonColors(containerColor = clarity.ground, contentColor = clarity.text),
) { Text(label) }

@Composable
private fun Plain(label: String, underline: Boolean = false, onClick: () -> Unit) =
    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 44.dp)) {
        Text(
            label,
            color = clarity.ground,
            textDecoration = if (underline) TextDecoration.Underline else null,
        )
    }

@Composable
private fun Buttons(primary: String, onPrimary: () -> Unit, onNotNow: () -> Unit) =
    Row(verticalAlignment = Alignment.CenterVertically) {
        Primary(primary, onPrimary)
        Spacer(Modifier.weight(1f))
        Plain("Not now", onClick = onNotNow)
    }
