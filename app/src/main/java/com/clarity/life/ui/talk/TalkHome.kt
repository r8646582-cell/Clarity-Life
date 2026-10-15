package com.clarity.life.ui.talk

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.TrackChanges
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.clarity.life.data.entity.ConversationRow
import com.clarity.life.data.entity.FocusRow
import com.clarity.life.ui.theme.Bold
import com.clarity.life.ui.theme.BoldCard
import com.clarity.life.ui.theme.Kicker
import com.clarity.life.ui.theme.clarity

/**
 * Talk home (spec section 2.1, the 0.1.6 redesign): what Talk shows while no conversation is open, so it is
 * never a blank screen. A greeting by name, the letter card when one is unread, and two or three ways to start
 * a conversation. Every line here is written by code. Today's steps and due promises live on Plan only, with a
 * dot on the Plan tab while one waits for an answer (the owner's decision, 14 Oct 2026: on Talk home a promise
 * and the plan step for the same lectures looked like two cards for one task). The morning brief was removed
 * on 13 Oct 2026, the owner's decision.
 */
@Composable
fun TalkHome(
    modifier: Modifier,
    planDay: Long?,
    hasPlan: Boolean,
    latest: ConversationRow?,
    hasKey: Boolean,
    provider: String,
    userName: String,
    onSetUp: () -> Unit,
    onOpenGuide: () -> Unit,
    focuses: List<FocusRow>,
    letterCard: @Composable () -> Unit,
    onStart: (chatType: String, focusId: Long?) -> Unit,
    onOpen: (Long) -> Unit,
) {
    // The start-up warm-up waits for this (FirstScreen), so loading the meaning model never slows the home.
    LaunchedEffect(Unit) { com.clarity.life.FirstScreen.mark() }
    LazyColumn(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(Modifier.padding(start = 8.dp, top = 12.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(greeting(userName), style = MaterialTheme.typography.displayLarge, color = clarity.text)
                // The very first visit: one line on what this is, gone once there is a conversation.
                if (latest == null) {
                    Text(
                        "Your coach is here for anything: a goal, a hard day, a choice. It remembers what matters " +
                            "and helps you keep your plans. Nothing is saved unless you tap to save it.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = clarity.muted,
                        modifier = Modifier.padding(top = 6.dp, end = 8.dp),
                    )
                    com.clarity.life.ui.theme.TextAction("New here? See how Clarity works", onClick = onOpenGuide)
                }
                planDay?.let { day ->
                    Text(
                        "Day $day of your plan.",
                        style = MaterialTheme.typography.headlineSmall,
                        fontStyle = FontStyle.Italic,
                        color = clarity.muted,
                    )
                }
            }
        }
        if (!hasKey) item(key = "setup") { SetupCard(provider, onSetUp) }
        item { letterCard() }
        item {
            Kicker("Start a conversation", modifier = Modifier.padding(start = 8.dp, top = 16.dp))
        }
        val starts = buildList {
            latest?.takeIf { it.title.isNotBlank() }?.let { c ->
                add(Start(Icons.Outlined.History, c.title, "Pick up where you left off") { onOpen(c.id) })
            }
            if (hasPlan) {
                add(Start(Icons.Outlined.CalendarMonth, "Look at my plan", "Plan and track") { onStart("plan", null) })
            }
            add(Start(Icons.Outlined.Psychology, "Something is on my mind", "Untangle") { onStart("untangle", null) })
            focuses.firstOrNull()?.let { f ->
                add(Start(Icons.Outlined.TrackChanges, f.name, "Focus") { onStart("free", f.id) })
            }
            add(Start(Icons.Outlined.CalendarMonth, "Help me decide something", "Decide") { onStart("decide", null) })
        }.take(3)
        starts.forEach { s -> item { StartRow(s) } }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

/**
 * Shown on Talk home until a key is saved: the one thing a new person must do before the coach can answer,
 * in three plain steps, with a button to the key page and one straight to Settings, Coach.
 */
@Composable
private fun SetupCard(provider: String, onSetUp: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val page = com.clarity.life.settings.DefaultSettings.keyPage(provider)
    val service = when (provider) {
        "deepseek" -> "DeepSeek"
        "anthropic" -> "Anthropic"
        else -> "your AI service"
    }
    BoldCard(gap = 10) {
        Kicker("Before your first talk", color = clarity.yellow)
        Text("Your coach needs a key", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = clarity.text)
        Text(
            "The coach thinks with an AI service. You make an account there, copy a key (a long password for apps), " +
                "and paste it here once. It stays locked on this phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = clarity.muted,
        )
        listOf(
            "Tap Get a key and sign up at $service. Add a little credit there.",
            "Make a new API key and copy it.",
            "Come back, tap Paste my key, and paste it under API key.",
        ).forEachIndexed { i, step ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${i + 1}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = clarity.yellow)
                Text(step, style = MaterialTheme.typography.bodyMedium, color = clarity.text, modifier = Modifier.weight(1f))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            if (page != null) {
                com.clarity.life.ui.theme.SecondaryButton("Get a key", Modifier.weight(1f)) {
                    openPage(context, page)
                }
            }
            com.clarity.life.ui.theme.PrimaryButton("Paste my key", Modifier.weight(1f), onClick = onSetUp)
        }
    }
}

/** Opens a page in the phone's browser; a phone with no browser simply does nothing. */
internal fun openPage(context: android.content.Context, url: String) {
    try {
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
    } catch (e: android.content.ActivityNotFoundException) {
        // Nothing to open it with: the steps still name the service.
    }
}

/**
 * "Hello, Sam." with the name from Settings, Coach, or just "Hello." until one is set. The same at any hour:
 * the date sits above it and the plan line below, so a time-of-day greeting only added a way to be wrong
 * (it said "Good evening." at 12:30 am).
 */
fun greeting(name: String): String = name.trim().let { if (it.isEmpty()) "Hello." else "Hello, $it." }

private data class Start(val icon: ImageVector, val title: String, val kind: String, val onClick: () -> Unit)

@Composable
private fun StartRow(s: Start) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .background(clarity.surface, RoundedCornerShape(18.dp))
            .border(1.dp, clarity.hairline, RoundedCornerShape(18.dp))
            .clickable(role = Role.Button, onClick = s.onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(s.icon, contentDescription = null, tint = clarity.yellow, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(s.title, style = MaterialTheme.typography.bodyLarge, color = clarity.text, maxLines = 1)
            Text(s.kind, style = MaterialTheme.typography.bodySmall, color = clarity.muted)
        }
    }
}
