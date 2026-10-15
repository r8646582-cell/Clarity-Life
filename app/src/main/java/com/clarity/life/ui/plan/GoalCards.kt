package com.clarity.life.ui.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.clarity.life.core.PlanFinish
import com.clarity.life.core.Planner
import com.clarity.life.ui.theme.BigFact
import com.clarity.life.ui.theme.BoldCard
import com.clarity.life.ui.theme.CalmCard
import com.clarity.life.ui.theme.Kicker
import com.clarity.life.ui.theme.OutlineCard
import com.clarity.life.ui.theme.PrimaryButton
import com.clarity.life.ui.theme.Quiet
import com.clarity.life.ui.theme.TextAction
import com.clarity.life.ui.theme.clarity
import java.time.temporal.ChronoUnit

/** The pill at the top of a goal: when it starts, whether its plan is on track, or which day it is. */
internal fun statusPill(g: PlanViewModel.GoalView, state: PlanViewModel.State, model: PlanViewModel): Pair<String, Boolean> {
    val today = state.today
    val first = g.firstStart
    if (first != null && first.isAfter(today)) {
        val day = model.showDay(first)
        return (if (first == today.plusDays(1)) "Starts tomorrow, $day" else "Starts $day") to false
    }
    val finish = g.backlogs.firstNotNullOfOrNull { it.facts.finish }
    return when {
        g.backlogs.isNotEmpty() && g.backlogs.all { it.facts.projection.status == "done" } -> "Done" to false
        finish?.status == "finishes" -> "On track" to false
        finish?.status == "late" || finish?.status == "never" -> "Plan falls short" to false
        g.status == "behind" -> "Behind" to true
        first != null -> "Day ${ChronoUnit.DAYS.between(first, today) + 1}" to false
        else -> g.status.replaceFirstChar { it.uppercase() } to false
    }
}

internal fun subtitle(g: PlanViewModel.GoalView, model: PlanViewModel): String? {
    val b = g.backlogs.firstOrNull()
    val deadline = g.row.deadline
    return when {
        b != null -> "${model.n(b.row.total)} ${b.row.unit} by ${model.day(b.row.deadline)}"
        deadline != null -> "By ${model.day(deadline)}"
        else -> null
    }
}

/**
 * Where the plan's own steps finish it; without steps, the pace the calendar needs. All counted by the app.
 * When the saved plan falls short, a calm card offers the fix instead of a red number (spec section 17, item 5).
 */
@Composable
internal fun FinishCard(
    b: PlanViewModel.BacklogView,
    g: PlanViewModel.GoalView,
    state: PlanViewModel.State,
    model: PlanViewModel,
    onFix: (draft: String) -> Unit,
) {
    val f = b.facts.finish
    val p = b.facts.projection
    val first = g.firstStart
    val started = first != null && !first.isAfter(state.today)
    if (f != null && (f.status == "late" || f.status == "never") && p.status != "done") {
        ShortPlanCard(b, g, f, p.requiredPerDay, model, onFix)
        return
    }
    BoldCard(gap = 6) {
        val finishDay = f?.finish
        val spare = f?.daysSpare
        val required = p.requiredPerDay
        when {
            p.status == "done" -> {
                Quiet("Finished")
                BigFact("${model.n(b.row.total)} of ${model.n(b.row.total)}")
                Text("Every one of them is done.", color = clarity.text, style = MaterialTheme.typography.bodyLarge)
            }
            f?.status == "finishes" && finishDay != null && spare != null -> {
                Quiet(if (started && b.facts.done > 0) "Your plan still finishes" else "Your plan finishes")
                BigFact(model.showDay(finishDay))
                Text(
                    when (spare) {
                        0 -> "On your deadline day"
                        1 -> "1 day before your deadline"
                        else -> "$spare days before your deadline"
                    },
                    color = clarity.text,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            required != null -> {
                Quiet("You need")
                BigFact(model.perDayUp(required))
                Text("a day, ${p.daysLeft} scheduled days left", color = clarity.text, style = MaterialTheme.typography.bodyLarge)
            }
            else -> Quiet(model.statusText(p))
        }
    }
}

/**
 * The plan falls short of the goal: no red number, a calm offer of the fix. One plain step reads "Your saved
 * plan is 2 a day. Add your ramp?"; steps that still fall short read "Your saved steps reach 222 of 300." The
 * button opens a Plan chat with a message already typed (not sent). Every number here is the app's.
 */
@Composable
private fun ShortPlanCard(
    b: PlanViewModel.BacklogView,
    g: PlanViewModel.GoalView,
    f: PlanFinish,
    required: Double?,
    model: PlanViewModel,
    onFix: (draft: String) -> Unit,
) {
    var later by rememberSaveable(g.row.id) { mutableStateOf(false) }
    val steps = b.facts.steps
    val reaches = model.n(f.reachesByDeadline ?: 0.0)
    val of = model.n(f.remaining)
    val by = model.day(b.row.deadline)
    val single = steps.size <= 1
    val perDay = steps.lastOrNull()?.target?.let { model.n(it) }
    val headline = if (single && perDay != null) "Your saved plan is $perDay a day. Add your ramp?" else "Your saved steps reach $reaches of $of. Adjust them?"
    if (later) {
        OutlineCard {
            Text("At your saved plan, $reaches of $of by $by", style = MaterialTheme.typography.titleSmall, color = clarity.text)
            TextAction(if (single) "Add your ramp" else "Adjust the steps") { later = false }
        }
        return
    }
    CalmCard {
        Kicker("One thing to fix", clarity.yellow)
        Text(headline, style = MaterialTheme.typography.headlineMedium, color = clarity.text)
        Text(
            "At your saved plan you reach $reaches of $of by $by." +
                (required?.let { " Finishing on time takes ${model.perDayUp(it)} a day from here." } ?: ""),
            style = MaterialTheme.typography.bodyMedium,
            color = clarity.text,
        )
        val draft = buildString {
            append("My saved plan for \"${g.row.title}\" ")
            append(if (single && perDay != null) "is $perDay a day" else "has ${steps.size} steps")
            append(", and it reaches $reaches of $of by $by. ")
            append(if (single) "Help me add the ramp so it gets there." else "Help me adjust the steps so it gets there.")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton(if (single) "Add the ramp" else "Adjust the plan", Modifier) { onFix(draft) }
            TextAction("Not now", color = clarity.muted) { later = true }
        }
    }
}

/** Today's step, with Check in; once answered, what was answered. Only on a day it is scheduled. */
@Composable
internal fun TodayStepCard(g: PlanViewModel.GoalView, state: PlanViewModel.State, model: PlanViewModel, onToday: () -> Unit) {
    if (state.restToday) return
    val row = state.todayRows.firstOrNull { it.target.goalId == g.row.id } ?: return
    val t = row.target
    BoldCard(gap = 8) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Today",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = clarity.text,
                modifier = Modifier.weight(1f),
            )
            if (row.steps > 1) Quiet("Step ${row.step} of ${row.steps}")
        }
        BigFact(model.n(t.target) + if (t.unit.isBlank()) "" else " ${t.unit}")
        if (t.minimum > 0 && t.minimum < t.target) Quiet("${model.n(t.minimum)} still counts on a bad day")
        if (row.answer == null) {
            PrimaryButton("Check in", Modifier.fillMaxWidth().padding(top = 6.dp), onClick = onToday)
        } else {
            Text(model.answeredLine(row), color = clarity.yellow, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** The plan's steps as a timeline: done (grey), now (yellow), next (hollow). */
@Composable
internal fun StepsCard(g: PlanViewModel.GoalView, state: PlanViewModel.State, model: PlanViewModel) {
    BoldCard(gap = 16) {
        Text("The plan", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = clarity.text)
        g.steps.forEach { st ->
            val t = st.target
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    Modifier
                        .padding(top = 5.dp)
                        .size(14.dp)
                        .then(
                            when (st.phase) {
                                "now" -> Modifier.background(clarity.yellow, CircleShape)
                                "done" -> Modifier.background(clarity.controlOutline, CircleShape)
                                else -> Modifier.border(2.dp, clarity.controlOutline, CircleShape)
                            },
                        ),
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val unit = if (t.unit.isBlank()) "" else " ${t.unit}"
                    val per = if (t.schedule == "daily") " a day" else ", ${t.schedule}"
                    Text(
                        "${model.n(t.target)}$unit$per",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (st.phase == "done") clarity.muted else clarity.text,
                    )
                    val end = st.end
                    val dates = if (end != null) "${model.showDay(st.start)} to ${model.showDay(end)}" else "From ${model.showDay(st.start)}"
                    val tag = when {
                        st.phase == "done" -> " · done"
                        st.phase == "now" && !st.start.isAfter(state.today) -> " · now"
                        else -> ""
                    }
                    Quiet(dates + tag)
                }
            }
        }
        val current = g.steps.firstOrNull { it.phase == "now" } ?: g.steps.firstOrNull { it.phase == "next" }
        current?.target?.takeIf { it.minimum > 0 && it.minimum < it.target }?.let {
            HorizontalDivider(color = clarity.hairline)
            Quiet("On a bad day, ${model.n(it.minimum)} still counts. Missed days are not failures; they show what to change.")
        }
    }
}

/**
 * The strip's heading counts every box it draws, days with nothing scheduled included, so a Mon/Wed/Fri target
 * reads "Last 7 days" above seven boxes, not "Last 3 days".
 */
internal fun weekHeading(marks: List<PlanViewModel.DayMark>): String = "Last ${marks.size} days"

/** The last seven days since tracking began: full days yellow, minimum days lighter, a miss outlined. */
@Composable
internal fun WeekCard(b: PlanViewModel.BacklogView) {
    val days = b.recent.filter { it.level != "off" }
    if (days.size < 2) return // a strip of one day says nothing yet
    BoldCard(gap = 12) {
        Text(weekHeading(b.recent), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = clarity.text)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            b.recent.forEach { m ->
                Column(
                    Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val shape = RoundedCornerShape(8.dp)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(36.dp)
                            .then(
                                when (m.level) {
                                    "target", "beyond" -> Modifier.background(clarity.yellow, shape)
                                    "minimum" -> Modifier.background(clarity.yellow.copy(alpha = 0.45f), shape)
                                    "rest" -> Modifier.background(clarity.raised, shape)
                                    "not" -> Modifier.border(2.dp, clarity.danger, shape)
                                    "off" -> Modifier
                                    else -> Modifier.border(1.dp, clarity.hairline, shape)
                                },
                            ),
                    )
                    Text(m.day.dayOfWeek.name.take(1), style = MaterialTheme.typography.labelSmall, color = clarity.muted)
                }
            }
        }
        val answered = days.count { it.level != null && it.level != "rest" }
        val scheduled = days.count { it.level != "rest" }
        Quiet("$answered of $scheduled days answered. Full days yellow, minimum days lighter, an empty box is no answer.")
    }
}

/**
 * How far along: "118 of 300 done" on the left, the days left on the right, the bar under them. Before there is
 * a pace, one quiet line under the bar says when it comes.
 */
@Composable
internal fun ProgressLine(b: PlanViewModel.BacklogView, model: PlanViewModel) {
    val p = b.facts.projection
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "${model.n(b.facts.done)} of ${model.n(b.row.total)} done",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = clarity.text,
                modifier = Modifier.weight(1f),
            )
            if (p.status != "done" && p.daysLeft > 0) {
                Text(
                    if (p.daysLeft == 1) "1 day left" else "${p.daysLeft} days left",
                    style = MaterialTheme.typography.bodySmall,
                    color = clarity.muted,
                )
            }
        }
        val share = if (b.row.total > 0) (b.facts.done / b.row.total).toFloat().coerceIn(0f, 1f) else 0f
        Box(Modifier.fillMaxWidth().height(6.dp).background(clarity.raised, RoundedCornerShape(999.dp))) {
            if (share > 0f) Box(Modifier.fillMaxWidth(share).height(6.dp).background(clarity.yellow, RoundedCornerShape(999.dp)))
        }
        if (p.status != "done" && p.typicalPerDay == null) {
            Text(
                if (p.bestPerDay != null) {
                    "Your usual pace shows again after ${Planner.MIN_ANSWERED} answered days in ${Planner.WINDOW}."
                } else {
                    "Your usual and best pace show after ${Planner.MIN_ANSWERED} answered days."
                },
                style = MaterialTheme.typography.bodySmall,
                color = clarity.muted,
            )
        }
    }
}

/**
 * Where each pace lands (spec section 2.2): what the deadline needs, then your usual pace and your best
 * fortnight with where each one gets you, then the status line. Every number is the planner's.
 */
@Composable
internal fun PaceCard(b: PlanViewModel.BacklogView, model: PlanViewModel) {
    val p = b.facts.projection
    val typical = p.typicalPerDay ?: return
    val required = p.requiredPerDay ?: return
    if (p.status == "done" || p.status == "past_deadline") return
    val done = b.facts.done
    fun lands(reach: Double?): String = when {
        reach == null -> ""
        reach >= p.remaining - 1e-9 -> "on time"
        else -> "reaches ${model.n(done + reach)}"
    }
    BoldCard(gap = 0) {
        Kicker("Where each pace lands", modifier = Modifier.padding(bottom = 6.dp))
        PaceRow("You need", null, "${model.perDayUp(required)} a day", strong = false)
        HorizontalDivider(color = clarity.hairline)
        PaceRow("Your usual pace", "${model.perDay(typical)} a day, last ${Planner.WINDOW} days", lands(p.reachesTypical), strong = false)
        p.bestPerDay?.let { best ->
            HorizontalDivider(color = clarity.hairline)
            PaceRow("Your best fortnight", "${model.perDay(best)} a day", lands(p.reachesBest), strong = p.status == "needs_best")
        }
        HorizontalDivider(color = clarity.hairline)
        Text(
            model.statusText(p),
            style = MaterialTheme.typography.bodySmall,
            color = clarity.muted,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun PaceRow(label: String, sub: String?, value: String, strong: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = clarity.text)
            sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = clarity.muted) }
        }
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall,
            color = if (strong) clarity.yellow else clarity.text,
        )
    }
}
