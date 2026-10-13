package com.clarity.life.ui.know

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clarity.life.core.PlanStateText
import com.clarity.life.core.Story
import com.clarity.life.data.entity.NoteRow
import com.clarity.life.ui.theme.Bold
import com.clarity.life.ui.theme.BoldCard
import com.clarity.life.ui.theme.Headline
import com.clarity.life.ui.theme.Kicker
import com.clarity.life.ui.theme.Pill
import com.clarity.life.ui.theme.PrimaryButton
import com.clarity.life.ui.theme.Quiet
import com.clarity.life.ui.theme.TextAction
import com.clarity.life.ui.theme.clarity

/**
 * Your story (spec 2.5, the owner's approved mockup of 13 Oct 2026): how long his story is and a This month /
 * This year / All of it switch; the monthly short version; the counts; a week-by-week chart per goal with the
 * plan's line under it; what he overcame; what helps him and what he is good at, each opening its proof in
 * What I know; then and now in his own words; his chapters by year. No scores, levels or streaks.
 */
@Composable
fun StoryScreen(
    onBack: () -> Unit,
    onTalk: () -> Unit,
    onProof: (NoteRow) -> Unit,
    onChapter: (Long) -> Unit,
    model: StoryViewModel = hiltViewModel(),
) {
    val s by model.state.collectAsStateWithLifecycle()
    BackHandler(onBack = onBack)
    LazyColumn(Modifier.fillMaxSize().background(clarity.ground)) {
        item {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextAction("‹ What I know", onClick = onBack)
                    Spacer(Modifier.weight(1f))
                    s.first?.let { Quiet("Since ${StoryViewModel.shortDay(it)} ${it.year}") }
                }
                val first = s.first
                val today = s.today
                if (first != null && today != null) {
                    Pill(Story.length(first, today))
                    Headline(Story.headline(first, today))
                } else if (!s.loading) {
                    Headline("Your story starts here.")
                    Quiet("It fills in from your conversations, check-ins and plans, a day at a time.")
                }
            }
        }
        if (s.first == null) return@LazyColumn

        item { ViewSwitch(s.view, model::setView) }

        item {
            BoldCard(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), gap = 10) {
                Quiet("The short version")
                val story = s.story
                if (story != null) {
                    Text(story.short, style = MaterialTheme.typography.headlineSmall, color = clarity.text)
                } else {
                    Text(
                        "Your first short version arrives at ${StoryViewModel.nextStory(s.today!!)}, written from what you did this month.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = clarity.text,
                    )
                }
            }
        }

        s.counts?.let { c -> item { CountTiles(s, c) } }

        if (s.charts.isNotEmpty()) item { ChartCard(s, model::showChart) }

        if (s.overcame.isNotEmpty() || s.doneFocuses.isNotEmpty()) {
            item { SectionTitle("What you overcame") }
            if (s.overcame.isNotEmpty()) {
                s.overcame.forEach { o ->
                    item {
                        BoldCard(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), gap = 12) {
                            Text(o.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = clarity.text)
                            o.then?.let { q -> ThenNow("Then", "\"${q.text}\" ${StoryViewModel.shortDay(java.time.LocalDate.parse(q.saidDay))}", false) }
                            ThenNow("You", o.did, false)
                            ThenNow("Now", o.now, true)
                        }
                    }
                }
            } else {
                s.doneFocuses.forEach { d ->
                    item {
                        BoldCard(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), gap = 12) {
                            Text(d.focus.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = clarity.text)
                            if (d.stepsKept.isNotEmpty()) {
                                ThenNow("You", "${d.stepsKept.size} ${if (d.stepsKept.size == 1) "step" else "steps"} kept: ${d.stepsKept.joinToString("; ")}", false)
                            }
                            ThenNow("Now", "You marked it done on ${StoryViewModel.day(d.day)}.", true)
                        }
                    }
                }
            }
        }

        if (s.helps.isNotEmpty() || s.strengths.isNotEmpty()) {
            item {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (s.helps.isNotEmpty()) NoteChips("What helps you", s.helps, onProof)
                    if (s.strengths.isNotEmpty()) NoteChips("What you're good at", s.strengths, onProof)
                    Text(
                        "The small number is how many times your own words or days showed it. Tap one to see them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = clarity.muted,
                    )
                }
            }
        }

        val then = s.thenQuote
        val now = s.nowQuote
        if (then != null && now != null) {
            item {
                BoldCard(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), gap = 14) {
                    Text("How you've changed, in your words", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = clarity.text)
                    Quiet("You said, ${StoryViewModel.shortDay(java.time.LocalDate.parse(then.saidDay))}")
                    Text("\"${then.text}\"", style = MaterialTheme.typography.bodyLarge, color = clarity.muted)
                    HorizontalDivider(color = clarity.hairline)
                    Text("You said, ${StoryViewModel.shortDay(java.time.LocalDate.parse(now.saidDay))}", style = MaterialTheme.typography.bodyMedium, color = clarity.yellow)
                    Text("\"${now.text}\"", style = MaterialTheme.typography.bodyLarge, color = clarity.text)
                }
            }
        }

        item { Years(s, onChapter) }

        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryButton("Talk about how far you've come") {
                    model.talk()
                    onTalk()
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ViewSwitch(view: Story.View, onView: (Story.View) -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .fillMaxWidth()
            .background(clarity.surface, Bold.pillShape)
            .padding(4.dp),
    ) {
        Story.View.values().forEach { v ->
            val on = v == view
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .background(if (on) clarity.yellow else clarity.surface, Bold.pillShape)
                    .clickable { onView(v) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    v.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) clarity.ground else clarity.muted,
                )
            }
        }
    }
}

@Composable
private fun CountTiles(s: StoryViewModel.State, c: com.clarity.life.story.StoryRepository.Counts) {
    val (from, to) = Story.viewRange(s.view, s.first!!, s.today!!)
    val chart = s.charts.firstOrNull()?.data
    val first: Pair<String, String> = if (chart != null) {
        val done = if (s.view == Story.View.ALL) chart.done else chart.doneWithin(from, to)
        PlanStateText.g(done) to "${chart.unit} done"
    } else {
        "${c.checkinDays}" to "days answered"
    }
    val tiles = listOf(
        first,
        "${c.promisesKept}" to if (c.promisesKept == 1) "promise kept" else "promises kept",
        "${c.overcome}" to if (c.overcome == 1) "thing overcome" else "things overcome",
    )
    Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.forEachIndexed { i, (n, label) ->
            Column(
                Modifier.weight(1f).background(clarity.surface, RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(n, style = MaterialTheme.typography.headlineSmall, color = if (i == 0) clarity.yellow else clarity.text)
                Text(label, style = MaterialTheme.typography.bodySmall, color = clarity.muted)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChartCard(s: StoryViewModel.State, onChart: (Int) -> Unit) {
    val chart = s.charts.getOrNull(s.chart) ?: return
    val c = chart.data
    BoldCard(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), gap = 14) {
        if (s.charts.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                s.charts.forEachIndexed { i, ch ->
                    val on = i == s.chart
                    Text(
                        ch.data.name,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (on) clarity.ground else clarity.muted,
                        modifier = Modifier
                            .background(if (on) clarity.yellow else clarity.raised, Bold.pillShape)
                            .clickable { onChart(i) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (s.charts.size > 1) (if (c.series.by == "week") "Week by week" else "Month by month") else c.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = clarity.text,
                modifier = Modifier.weight(1f),
            )
            c.finishedOn?.let { Kicker("Done ${StoryViewModel.day(it)}") }
        }
        Bars(chart.bars)
        Row {
            Text(StoryViewModel.shortDay(chart.bars.first().start), style = MaterialTheme.typography.bodySmall, color = clarity.muted)
            Spacer(Modifier.weight(1f))
            Text(
                if (c.finishedOn != null) StoryViewModel.shortDay(chart.bars.last().end) else "Today",
                style = MaterialTheme.typography.bodySmall,
                color = clarity.muted,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(10.dp).background(clarity.yellow, RoundedCornerShape(3.dp)))
                Text("What you did", style = MaterialTheme.typography.bodySmall, color = clarity.muted)
            }
            if (c.steps.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val ink = clarity.text
                    Canvas(Modifier.width(16.dp).height(2.dp)) {
                        drawLine(ink, Offset(0f, 0f), Offset(size.width, 0f), 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 4f)))
                    }
                    Text("What your plan asked for", style = MaterialTheme.typography.bodySmall, color = clarity.muted)
                }
            }
        }
        chartLine(chart)?.let { Quiet(it) }
    }
}

/** The bars and the plan's stairs. Counted by [Story.series]; drawn here, nothing worked out. */
@Composable
private fun Bars(bars: List<Story.Bar>) {
    val top = (bars.maxOf { maxOf(it.done, it.planned) }).coerceAtLeast(1.0) * 1.1
    val yellow = clarity.yellow
    val line = clarity.text
    val hair = clarity.hairline
    val described = bars.joinToString("; ") { "${StoryViewModel.shortDay(it.start)}: ${PlanStateText.g(it.done)} of ${PlanStateText.g(it.planned)}" }
    Canvas(Modifier.fillMaxWidth().height(150.dp).semantics { contentDescription = described }) {
        val slot = size.width / bars.size
        val gap = (slot * 0.18f).coerceAtMost(6.dp.toPx())
        val h = size.height - 2f
        fun y(v: Double) = (h - (v / top * h)).toFloat()
        drawLine(hair, Offset(0f, h + 1f), Offset(size.width, h + 1f), 1.dp.toPx())
        bars.forEachIndexed { i, b ->
            if (b.done > 0) {
                val bh = (b.done / top * h).toFloat()
                drawRoundRect(
                    yellow,
                    topLeft = Offset(i * slot + gap / 2, h - bh),
                    size = Size(slot - gap, bh),
                    cornerRadius = CornerRadius(3.dp.toPx()),
                )
            }
        }
        if (bars.any { it.planned > 0 }) {
            val path = Path()
            bars.forEachIndexed { i, b ->
                val yy = y(b.planned)
                if (i == 0) path.moveTo(0f, yy) else path.lineTo(i * slot, yy)
                path.lineTo((i + 1) * slot, yy)
            }
            drawPath(
                path,
                line.copy(alpha = 0.75f),
                style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
            )
        }
    }
}

/** One plain line under the chart, from the counted bars: the last finished stretch against the plan. */
private fun chartLine(chart: StoryViewModel.Chart): String? {
    val c = chart.data
    val g = PlanStateText::g
    c.finishedOn?.let { done ->
        val dl = chart.deadline
        return if (dl != null && done.isBefore(dl)) {
            val days = StoryViewModel.daysBefore(done, dl)
            "You finished all ${g(c.total)} ${c.unit} on ${StoryViewModel.day(done)}, $days ${if (days == 1L) "day" else "days"} before the deadline."
        } else {
            "You finished all ${g(c.total)} ${c.unit} on ${StoryViewModel.day(done)}."
        }
    }
    val bars = chart.bars
    val last = if (bars.size >= 2) bars[bars.size - 2] else return null
    val what = if (c.series.by == "week") "The week of ${StoryViewModel.shortDay(last.start)}" else StoryViewModel.monthName(last.start)
    return if (last.planned > 0) {
        "$what: you did ${g(last.done)} and your plan asked for ${g(last.planned)}."
    } else {
        "$what: you did ${g(last.done)}."
    }
}

@Composable
private fun ThenNow(label: String, text: String, now: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = if (now) clarity.yellow else clarity.muted, modifier = Modifier.width(44.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = clarity.text, modifier = Modifier.weight(1f))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NoteChips(title: String, notes: List<NoteRow>, onProof: (NoteRow) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = clarity.text)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            notes.forEach { n ->
                Text(
                    "${n.text}  · ${n.timesSeen}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = clarity.text,
                    modifier = Modifier
                        .background(clarity.raised, Bold.pillShape)
                        .clickable { onProof(n) }
                        .heightIn(min = 44.dp)
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                )
            }
        }
    }
}

@Composable
private fun Years(s: StoryViewModel.State, onChapter: (Long) -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text("Your years", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = clarity.text)
        Quiet("A chapter arrives every three months.")
        val byYear = s.chapters.groupBy { it.periodEnd.take(4) }
        byYear.forEach { (year, chapters) ->
            Text(year, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = clarity.yellow, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            chapters.forEach { c ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable { onChapter(c.id) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(c.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = clarity.text)
                        Quiet(
                            "${StoryViewModel.monthShort(java.time.LocalDate.parse(c.periodStart))} to " +
                                StoryViewModel.monthShort(java.time.LocalDate.parse(c.periodEnd)),
                        )
                    }
                    Text("›", style = MaterialTheme.typography.titleLarge, color = clarity.muted)
                }
                HorizontalDivider(color = clarity.hairline)
            }
        }
        s.today?.let {
            Column(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(top = 10.dp), verticalArrangement = Arrangement.Center) {
                Text("Being written", style = MaterialTheme.typography.bodyLarge, color = clarity.muted)
                Quiet(StoryViewModel.nextChapter(it))
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = clarity.text,
        textAlign = TextAlign.Start,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 6.dp),
    )
}
