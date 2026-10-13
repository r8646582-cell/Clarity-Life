package com.clarity.life.ui.talk

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.TrackChanges
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.clarity.life.data.entity.ConversationRow
import com.clarity.life.data.entity.FocusRow
import com.clarity.life.ui.plan.PlanViewModel
import com.clarity.life.ui.plan.PromisesViewModel
import com.clarity.life.ui.theme.Bold
import com.clarity.life.ui.theme.BoldCard
import com.clarity.life.ui.theme.Kicker
import com.clarity.life.ui.theme.clarity

/**
 * Talk home (spec section 2.1, the 0.1.6 redesign): what Talk shows while no conversation is open, so it is
 * never a blank screen. A greeting by the time of day, the letter card when one is unread, today's step with a
 * one-tap check-in, the next promise, and two or three ways to start a conversation. Every line here is written
 * by code. (The morning brief was removed on 13 Oct 2026, the owner's decision.)
 */
@Composable
fun TalkHome(
    modifier: Modifier,
    plan: PlanViewModel.State,
    planModel: PlanViewModel,
    promises: PromisesViewModel.State,
    latest: ConversationRow?,
    hasKey: Boolean,
    provider: String,
    onSetUp: () -> Unit,
    onOpenGuide: () -> Unit,
    focuses: List<FocusRow>,
    letterCard: @Composable () -> Unit,
    onStart: (chatType: String, focusId: Long?) -> Unit,
    onOpen: (Long) -> Unit,
    onOpenToday: () -> Unit,
    onOpenPromises: () -> Unit,
) {
    LazyColumn(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(Modifier.padding(start = 8.dp, top = 12.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(greeting(java.time.LocalTime.now().hour), style = MaterialTheme.typography.displayLarge, color = clarity.text)
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
                planModel.planDay(plan)?.takeIf { plan.ready }?.let { day ->
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
        val open = plan.todayRows.firstOrNull { it.answer == null }
        if (open != null && !plan.restToday) {
            item(key = "step") { StepCard(open, plan, planModel, onOpenToday) }
        } else if (plan.todayRows.isNotEmpty() && !plan.restToday) {
            item(key = "step") { QuietRow(Icons.Outlined.TrackChanges, "Today", "All answered. That is the habit.", onOpenToday) }
        }
        // What is due already has its own card above (the due card), so this row shows the next one after it.
        val next = nextPromise(promises)
        next?.let { p ->
            item(key = "promise") {
                QuietRow(Icons.Outlined.Schedule, "Next promise · ${p.time}", p.row.text, onOpenPromises)
            }
        }
        item {
            Kicker("Start a conversation", modifier = Modifier.padding(start = 8.dp, top = 16.dp))
        }
        val starts = buildList {
            latest?.takeIf { it.title.isNotBlank() }?.let { c ->
                add(Start(Icons.Outlined.History, c.title, "Pick up where you left off") { onOpen(c.id) })
            }
            if (plan.todayRows.isNotEmpty() || plan.goals.isNotEmpty()) {
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

/** The "Next promise" row: never one the due card already shows, so a promise is not on Talk home twice. */
fun nextPromise(promises: PromisesViewModel.State): PromisesViewModel.Item? = promises.coming.firstOrNull()

/**
 * The words after today's number: "2 lectures · Reading", but just "2 CAF lectures" when the name already says
 * the unit, so it never reads "2 lectures · CAF lectures".
 */
fun stepLabel(unit: String, name: String): String {
    val u = unit.trim().lowercase()
    if (u.isEmpty()) return name
    val saysUnit = name.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).any { w -> w == u || w == u + "s" || u == w + "s" }
    return if (saysUnit) name else "$unit · $name"
}

/**
 * By the phone's clock: night before 5 (12:30 am is not an evening), morning before 12, afternoon before 17,
 * evening otherwise. Warm, never a nudge to go to bed.
 */
fun greeting(hour: Int): String = when {
    hour in 0..4 -> "Hello, night owl."
    hour in 5..11 -> "Good morning."
    hour in 12..16 -> "Good afternoon."
    else -> "Good evening."
}

@Composable
private fun StepCard(row: PlanViewModel.TodayRow, plan: PlanViewModel.State, model: PlanViewModel, onOpenToday: () -> Unit) {
    val t = row.target
    BoldCard(gap = 14) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Kicker("Today") }
            if (row.steps > 1) Text("Step ${row.step} of ${row.steps}", style = MaterialTheme.typography.bodySmall, color = clarity.muted)
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (row.quantity) {
                Text(model.n(t.target), style = MaterialTheme.typography.displayMedium, color = clarity.yellow)
            }
            Text(
                if (row.quantity) stepLabel(t.unit, t.name) else t.name,
                style = MaterialTheme.typography.titleLarge,
                color = clarity.text,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val did = if (row.quantity) "Did ${model.n(t.target)}" else "Did it"
            HomeButton(did, primary = true, Modifier.weight(2f)) { model.answer(row, "target", plan.today) }
            HomeButton("Part", primary = false, Modifier.weight(1f), onClick = onOpenToday)
            HomeButton("Not", primary = false, Modifier.weight(1f), onClick = onOpenToday)
        }
    }
}

@Composable
private fun HomeButton(label: String, primary: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .heightIn(min = 50.dp)
            .then(
                if (primary) {
                    Modifier.background(clarity.yellow, Bold.buttonShape)
                } else {
                    Modifier.background(clarity.raised, Bold.buttonShape).border(1.dp, clarity.controlOutline, Bold.buttonShape)
                },
            )
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal,
            color = if (primary) clarity.onYellow else clarity.text,
        )
    }
}

@Composable
private fun QuietRow(icon: ImageVector, over: String, line: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(clarity.surface, Bold.cardShape)
            .border(1.dp, clarity.hairline, Bold.cardShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(40.dp).background(clarity.raised, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = clarity.yellow)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(over, style = MaterialTheme.typography.bodySmall, color = clarity.muted)
            Text(line, style = MaterialTheme.typography.titleMedium, color = clarity.text, maxLines = 2)
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = clarity.muted)
    }
}

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
