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
import com.clarity.life.data.entity.BriefRow
import com.clarity.life.data.entity.ConversationRow
import com.clarity.life.data.entity.FocusRow
import com.clarity.life.ui.plan.PlanViewModel
import com.clarity.life.ui.plan.PromisesViewModel
import com.clarity.life.ui.theme.Bold
import com.clarity.life.ui.theme.BoldCard
import com.clarity.life.ui.theme.CoachDot
import com.clarity.life.ui.theme.Kicker
import com.clarity.life.ui.theme.clarity

/**
 * Talk home (spec section 2.1, the 0.1.6 redesign): what Talk shows while no conversation is open, so it is
 * never a blank screen. A greeting by the time of day, the letter card when one is unread, the morning brief,
 * today's step with a one-tap check-in, the next promise, and two or three ways to start a conversation. Every
 * line here is written by code except the brief, which passed its checks before it was stored.
 */
@Composable
fun TalkHome(
    modifier: Modifier,
    plan: PlanViewModel.State,
    planModel: PlanViewModel,
    promises: PromisesViewModel.State,
    brief: BriefRow?,
    latest: ConversationRow?,
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
        item { letterCard() }
        brief?.let { b -> item(key = "brief") { BriefCard(b.body) } }
        val open = plan.todayRows.firstOrNull { it.answer == null }
        if (open != null && !plan.restToday) {
            item(key = "step") { StepCard(open, plan, planModel, onOpenToday) }
        } else if (plan.todayRows.isNotEmpty() && !plan.restToday) {
            item(key = "step") { QuietRow(Icons.Outlined.TrackChanges, "Today", "All answered. That is the habit.", onOpenToday) }
        }
        val next = promises.due.firstOrNull() ?: promises.coming.firstOrNull()
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

/** By the phone's clock: morning before 12, afternoon before 17, evening otherwise. */
fun greeting(hour: Int): String = when {
    hour in 4..11 -> "Good morning."
    hour in 12..16 -> "Good afternoon."
    else -> "Good evening."
}

@Composable
fun BriefCard(body: String, modifier: Modifier = Modifier) {
    BoldCard(modifier, gap = 10) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CoachDot()
            Text("Your morning brief", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = clarity.text)
        }
        Text(body, style = MaterialTheme.typography.bodyLarge, color = clarity.text)
    }
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
                if (row.quantity && t.unit.isNotBlank()) "${t.unit} · ${t.name}" else t.name,
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
