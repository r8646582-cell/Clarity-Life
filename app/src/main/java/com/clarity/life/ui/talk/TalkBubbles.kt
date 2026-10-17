package com.clarity.life.ui.talk

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clarity.life.chat.CardSaver
import com.clarity.life.chat.ReplyBus
import com.clarity.life.core.LiveText
import com.clarity.life.core.RichText
import com.clarity.life.data.entity.CardRow
import com.clarity.life.data.entity.MessageRow
import com.clarity.life.ui.theme.CoachDot
import com.clarity.life.ui.theme.clarity
import kotlinx.coroutines.flow.StateFlow

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MessageBubble(
    row: MessageRow,
    live: StateFlow<ReplyBus.Live?>,
    isLive: Boolean,
    onGrow: () -> Unit,
    cards: List<CardRow>,
    receipts: Map<Long, String>,
    cardLines: Map<Long, String>,
    previews: Map<Long, CardSaver.Preview>,
    actions: CardActions,
    onTryAgain: () -> Unit,
) {
    val his = row.role == "user"
    // A reply that was streaming when this bubble first appeared is typed out; older ones show at once.
    val typed = remember(row.id) { row.status == "streaming" }
    var menu by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    val writing = !his && (typed || isLive || row.status == "streaming")
    Column(Modifier.fillMaxWidth()) {
        if (writing || row.content.isNotEmpty()) Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = if (his) Arrangement.End else Arrangement.Start,
        ) {
            Box {
                // His messages are bubbles on the right; the coach's replies use the full width, like a page.
                // A long press opens Copy and Details.
                Column(
                    (
                        if (his) {
                            Modifier
                                .widthIn(max = 320.dp)
                                .background(clarity.text, RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
                        } else {
                            Modifier.fillMaxWidth()
                        }
                    )
                        .combinedClickable(
                            onClick = {},
                            onLongClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                menu = true
                            },
                        )
                        .padding(
                            horizontal = if (his) 14.dp else 4.dp,
                            vertical = if (his) 10.dp else 6.dp,
                        ),
                ) {
                    if (his) {
                        Text(row.content, style = MaterialTheme.typography.bodyLarge, color = clarity.ground)
                    } else {
                        // The coach's presence dot beside its reply; it breathes while the reply is being written.
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CoachDot(alive = writing, modifier = Modifier.padding(top = 1.dp))
                            Column(Modifier.weight(1f)) {
                                if (writing) {
                                    ReplyText(row, live, onGrow)
                                } else if (row.content.isNotEmpty()) {
                                    // The coach may mark a few words bold or write a short list; his own text is never changed.
                                    Text(
                                        remember(row.content) { styled(row.content) },
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontSize = 17.sp,
                                        lineHeight = 26.sp,
                                        color = clarity.text,
                                    )
                                }
                            }
                        }
                    }
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Copy") },
                        onClick = {
                            menu = false
                            val text = if (isLive) live.value?.text?.let(LiveText::visible).orEmpty() else row.content
                            clipboard.setText(AnnotatedString(text))
                            // Android 13 and later show their own "Copied" bubble.
                            if (android.os.Build.VERSION.SDK_INT < 33) {
                                android.widget.Toast.makeText(context, "Copied", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Details") },
                        onClick = {
                            menu = false
                            details = true
                        },
                    )
                }
            }
        }
        when (row.status) {
            "interrupted" -> QuietLineWithAction("That reply was cut off.", "Try again", onTryAgain)
            "failed" -> QuietLineWithAction("That reply did not arrive.", "Try again", onTryAgain)
            else -> Unit
        }
        cards.forEach { card -> SuggestionCard(card, receipts[card.id], cardLines[card.id], previews[card.id], actions) }
    }
    if (details) MessageDetails(row, onClose = { details = false })
}

/** A coach reply with its bold words and list lines drawn; the parsing is in [RichText]. */
private fun styled(text: String): AnnotatedString {
    val rich = RichText.parse(text)
    return buildAnnotatedString {
        append(rich.text)
        rich.bold.forEach { range ->
            addStyle(
                SpanStyle(fontWeight = FontWeight.Bold),
                range.first,
                range.last + 1,
            )
        }
    }
}

/**
 * A reply being written, typed out at an even pace (a few characters a frame) however the network delivers
 * it. The live text is read once a frame, not collected, so a new piece of text redraws only this one Text;
 * when the stored reply takes over, the typing carries on instead of jumping, and the loop then stops.
 */
@Composable
private fun ReplyText(
    row: MessageRow,
    live: StateFlow<ReplyBus.Live?>,
    onGrow: () -> Unit,
) {
    val rowNow by rememberUpdatedState(row)
    var full by remember(row.id) { mutableStateOf("") }
    var shown by remember(row.id) { mutableIntStateOf(0) }
    LaunchedEffect(row.id) {
        while (true) {
            withFrameNanos { }
            val r = rowNow
            val now = live.value?.takeIf { it.messageId == r.id }
            val source = when {
                now != null -> LiveText.visible(now.text)
                // Between the end of the stream and the stored reply arriving, keep what is on screen.
                r.status == "streaming" -> full
                else -> r.content
            }
            if (source != full) full = source
            val step = LiveText.step(full.length - shown)
            if (step > 0) {
                shown += step
                onGrow()
            } else if (shown > full.length) {
                shown = full.length // the stored reply is shorter: its card lines were taken out
            }
            if (now == null && r.status != "streaming" && shown >= full.length) break
        }
    }
    if (full.isEmpty()) {
        if (row.status == "streaming") ThinkingLine()
    } else {
        Text(styled(full.take(shown)), style = MaterialTheme.typography.bodyLarge, fontSize = 17.sp, lineHeight = 26.sp, color = clarity.text)
    }
}

@Composable
private fun ThinkingLine() {
    // Three dots that fill in turn, so a long think still looks alive.
    val transition = rememberInfiniteTransition(label = "thinking")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            tween(1200, easing = LinearEasing),
        ),
        label = "dots",
    )
    val dots = ".".repeat(phase.toInt() + 1)
    Text(
        "Thinking$dots",
        style = MaterialTheme.typography.bodyLarge,
        fontStyle = FontStyle.Italic,
        color = clarity.muted,
    )
}

/** Details of one message: when it was written and, for the coach, which model and thinking level. */
@Composable
private fun MessageDetails(row: MessageRow, onClose: () -> Unit) {
    val his = row.role == "user"
    val at = java.time.Instant.ofEpochMilli(row.createdAt).atZone(java.time.ZoneId.systemDefault())
    val time = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm").format(at)
    val lines = buildList {
        add((if (his) "Sent" else "Written") to time)
        if (!his) {
            val label = row.model
            val model = label?.substringBefore(", thinking ")
            val level = label?.substringAfter(", thinking ", "")?.takeIf { it.isNotEmpty() }
            add("Model" to (model ?: "Not recorded (written before this was kept)"))
            if (level != null) add("Thinking" to level.replaceFirstChar { it.uppercase() })
            row.tokensIn?.let {
                add("Tokens read" to "%,d (total prompt)".format(it))
                add("Prompt cache" to "Active (DeepSeek prefix cached)")
            }
            row.tokensOut?.let { add("Tokens written" to "%,d".format(it)) }
            val status = when (row.status) {
                "complete" -> "Complete"
                "streaming" -> "Still being written"
                "interrupted" -> "Cut off"
                "failed" -> "Did not arrive"
                else -> row.status
            }
            add("Status" to status)
        }
        add("Length" to "${row.content.length} characters")
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        containerColor = clarity.surface,
        title = { Text(if (his) "Your message" else "Coach reply", color = clarity.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                lines.forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(k, style = MaterialTheme.typography.bodyMedium, color = clarity.muted)
                        Spacer(Modifier.widthIn(min = 12.dp))
                        Text(v, style = MaterialTheme.typography.bodyMedium, color = clarity.text)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close", color = clarity.yellow) } },
    )
}

@Composable
private fun QuietLineWithAction(line: String, action: String, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(line, style = MaterialTheme.typography.bodySmall, color = clarity.danger)
        TextButton(onClick = onAction) { Text(action, color = clarity.yellow) }
    }
}
