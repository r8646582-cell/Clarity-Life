package com.clarity.life.ui.know

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
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
import com.clarity.life.ui.theme.PrimaryButton
import com.clarity.life.ui.theme.Quiet
import com.clarity.life.ui.theme.TextAction
import com.clarity.life.ui.theme.clarity

/**
 * Your story (spec 2.5, the owner's approved mockup of 13 Oct 2026, made cleaner at the owner's request the same day):
 * how long his story is and a This month / This year / All of it switch (once the views differ); the monthly
 * short version; the counts (once one is not zero); a week-by-week chart per goal with the
 * plan's line under it; what he overcame, folded to Then and Now; what helps him and what he is good at, each
 * opening its proof in a sheet over this page; then and now in his own words; his chapters by year. No scores,
 * levels or streaks.
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
                TextAction("‹ What I know", onClick = onBack)
                val first = s.first
                val today = s.today
                if (first != null && today != null) {
                    Column(Modifier.padding(top = 8.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Kicker("Your story", color = clarity.yellow)
                        Headline(Story.headline(first, today))
                        Quiet("${Story.length(first, today)} · since ${StoryViewModel.shortDay(first)} ${first.year}")
                    }
                } else if (!s.loading) {
                    Headline("Your story starts here.")
                    Quiet("It fills in from your conversations, check-ins and plans, a day at a time.")
                }
            }
        }
        if (s.first == null) return@LazyColumn

        if (s.first!!.isBefore(s.today!!.withDayOfMonth(1))) item { ViewSwitch(s.view, model::setView) }

        item {
            val story = s.story
            if (story != null) {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp).height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(clarity.yellow, Bold.pillShape))
                    Column(Modifier.padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Kicker("The short version")
                        Text(story.short, style = MaterialTheme.typography.headlineSmall, color = clarity.text)
                    }
                }
            } else {
                Row(
                    Modifier
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                        .fillMaxWidth()
                        .background(clarity.yellowTint, Bold.cardShape)
                        .border(1.dp, clarity.yellowEdge, Bold.cardShape)
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.AutoStories, contentDescription = null, tint = clarity.yellow)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("A short summary of your month", style = MaterialTheme.typography.titleSmall, color = clarity.text)
                        Quiet("The first one arrives at ${StoryViewModel.nextStory(s.today!!)}.")
                    }
                }
            }
        }

        s.counts?.let { c -> item { CountTiles(s, c) } }

        if (s.charts.isNotEmpty()) item { ChartCard(s, model::showChart) }

        if (s.overcame.isNotEmpty() || s.doneFocuses.isNotEmpty()) {
            item { SectionTitle("What you overcame") }
            if (s.overcame.isNotEmpty()) {
                s.overcame.forEach { o -> item { OvercameCard(o) } }
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
            item { SectionTitle("Lessons & Insights") }
            if (s.helps.isNotEmpty()) item { NoteList("What helps you", s.helps, onProof) }
            if (s.strengths.isNotEmpty()) item { NoteList("What you're good at", s.strengths, onProof) }
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
        "${c.checkinDays}" to if (c.checkinDays == 1) "day answered" else "days answered"
    }
    val tiles = listOf(
        first,
        "${c.promisesKept}" to if (c.promisesKept == 1) "promise kept" else "promises kept",
        "${c.overcome}" to if (c.overcome == 1) "thing overcome" else "things overcome",
    )
    // Three zeros on the first days say nothing; the card appears with the first thing counted.
    if (tiles.all { it.first == "0" }) return
    Row(
        Modifier
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(clarity.surface, Bold.cardShape)
            .border(1.dp, clarity.hairline, Bold.cardShape)
            .padding(vertical = 18.dp),
    ) {
        tiles.forEachIndexed { i, (n, label) ->
            if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(clarity.hairline))
            Column(
                Modifier.weight(1f).padding(horizontal = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(n, style = MaterialTheme.typography.headlineLarge, color = if (i == 0) clarity.yellow else clarity.text)
                Text(label, style = MaterialTheme.typography.bodySmall, color = clarity.muted, textAlign = TextAlign.Center)
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
        var picked by rememberSaveable(s.chart, chart.bars.size) { mutableStateOf<Int?>(null) }
        Bars(chart.bars, partialLast = c.finishedOn == null, picked = picked) { i -> picked = if (picked == i) null else i }
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
        (picked?.let { barLine(chart, it) } ?: chartLine(chart))?.let { Quiet(it) }
    }
}

/**
 * The bars and the plan's stairs, over a few quiet gridlines with round numbers. Counted by [Story.series];
 * drawn here, nothing worked out. The stretch still under way is drawn lighter. Tapping a bar picks it: the
 * others dim and the line under the chart reads that stretch; tapping it again lets go.
 */
@Composable
private fun Bars(bars: List<Story.Bar>, partialLast: Boolean, picked: Int?, onPick: (Int) -> Unit) {
    val most = bars.maxOf { maxOf(it.done, it.planned) }.coerceAtLeast(1.0)
    val step = gridStep(most)
    val top = kotlin.math.ceil(most / step) * step
    val yellow = clarity.yellow
    val line = clarity.text
    val hair = clarity.hairline
    val label = MaterialTheme.typography.labelSmall.copy(color = clarity.muted, fontWeight = FontWeight.Normal, letterSpacing = 0.sp)
    val measurer = rememberTextMeasurer()
    val described = bars.joinToString("; ") { "${StoryViewModel.shortDay(it.start)}: ${PlanStateText.g(it.done)} of ${PlanStateText.g(it.planned)}" }
    val n = bars.size
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(168.dp)
            .semantics { contentDescription = described }
            .pointerInput(n) {
                detectTapGestures { pos ->
                    val gutter = 30.dp.toPx()
                    val slot = (size.width - gutter) / n
                    val i = ((pos.x - gutter) / slot).toInt()
                    if (i in 0 until n) onPick(i)
                }
            },
    ) {
        val gutter = 30.dp.toPx()
        val h = size.height - 2f
        val w = size.width - gutter
        fun y(v: Double) = (h - (v / top * h)).toFloat()
        var v = 0.0
        while (v <= top + 1e-9) {
            val yy = y(v)
            drawLine(hair, Offset(gutter, yy), Offset(size.width, yy), 1.dp.toPx())
            val t = measurer.measure(PlanStateText.g(v), label)
            drawText(t, topLeft = Offset(gutter - 8.dp.toPx() - t.size.width, (yy - t.size.height / 2f).coerceIn(0f, size.height - t.size.height)))
            v += step
        }
        val slot = w / n
        val gap = (slot * 0.36f).coerceAtMost(14.dp.toPx())
        bars.forEachIndexed { i, b ->
            if (b.done > 0) {
                val bh = (b.done / top * h).toFloat()
                val alpha = when {
                    picked != null && picked != i -> 0.32f
                    partialLast && i == n - 1 && picked != i -> 0.45f
                    else -> 1f
                }
                drawRoundRect(
                    yellow.copy(alpha = alpha),
                    topLeft = Offset(gutter + i * slot + gap / 2, h - bh),
                    size = Size(slot - gap, bh),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                )
            }
        }
        if (bars.any { it.planned > 0 }) {
            val path = Path()
            bars.forEachIndexed { i, b ->
                val yy = y(b.planned)
                if (i == 0) path.moveTo(gutter, yy) else path.lineTo(gutter + i * slot, yy)
                path.lineTo(gutter + (i + 1) * slot, yy)
            }
            drawPath(
                path,
                line.copy(alpha = 0.7f),
                style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(9f, 7f))),
            )
        }
    }
}

/** A round gridline step (1, 2 or 5 times a power of ten) that gives about three lines up to [most]. */
internal fun gridStep(most: Double): Double {
    val raw = most / 3.0
    if (raw <= 1.0) return 1.0
    val pow = Math.pow(10.0, kotlin.math.floor(kotlin.math.log10(raw)))
    val f = raw / pow
    return pow * when {
        f <= 1.0 -> 1.0
        f <= 2.0 -> 2.0
        f <= 5.0 -> 5.0
        else -> 10.0
    }
}

/** The line for a bar he tapped: that stretch, what he did and what the plan asked for. */
private fun barLine(chart: StoryViewModel.Chart, i: Int): String? {
    val b = chart.bars.getOrNull(i) ?: return null
    val c = chart.data
    val g = PlanStateText::g
    val what = if (c.series.by == "week") "The week of ${StoryViewModel.shortDay(b.start)}" else StoryViewModel.monthName(b.start)
    val sofar = if (c.finishedOn == null && i == chart.bars.size - 1) " so far" else ""
    return if (b.planned > 0) {
        "$what$sofar: you did ${g(b.done)} and your plan asked for ${g(b.planned)}."
    } else {
        "$what$sofar: you did ${g(b.done)}."
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
    if (bars.all { it.done <= 0.0 }) {
        return if (bars.any { it.planned > 0 }) {
            "Bars start with your first check-in. The dashed line is what your plan asks for."
        } else {
            "Bars start with your first check-in."
        }
    }
    val last = if (bars.size >= 2) bars[bars.size - 2] else return null
    val what = if (c.series.by == "week") "The week of ${StoryViewModel.shortDay(last.start)}" else StoryViewModel.monthName(last.start)
    return if (last.planned > 0) {
        "$what: you did ${g(last.done)} and your plan asked for ${g(last.planned)}."
    } else {
        "$what: you did ${g(last.done)}."
    }
}

/**
 * One thing he overcame, folded: the title, Then in his own words and Now. Tapping it unfolds what he did in
 * between, in place. Without a Then quote there is nothing to fold, so what he did shows from the start.
 */
@Composable
private fun OvercameCard(o: StoryViewModel.Overcame) {
    val then = o.then
    var open by rememberSaveable(o.title) { mutableStateOf(false) }
    val folds = then != null
    BoldCard(
        Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        gap = 10,
        onClick = if (folds) ({ open = !open }) else null,
    ) {
        Text(o.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = clarity.text)
        if (then != null) {
            ThenNow("Then", "“${then.text}”", false, quote = true)
            if (open) ThenNow("You", o.did, false)
        } else {
            ThenNow("You", o.did, false)
        }
        ThenNow("Now", o.now, true)
        if (folds) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (open) "You said it on ${StoryViewModel.shortDay(java.time.LocalDate.parse(then!!.saidDay))}" else "What you did",
                    style = MaterialTheme.typography.bodySmall,
                    color = clarity.muted,
                )
                Icon(
                    if (open) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                    contentDescription = null,
                    tint = clarity.muted,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun ThenNow(label: String, text: String, now: Boolean, quote: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = if (now) clarity.yellow else clarity.muted, modifier = Modifier.width(44.dp))
        Text(
            text,
            style = if (quote) MaterialTheme.typography.headlineSmall.copy(fontSize = 18.sp, lineHeight = 24.sp) else MaterialTheme.typography.bodyMedium,
            color = if (now || quote) clarity.text else clarity.muted,
            modifier = Modifier.weight(1f),
        )
    }
}

/** How many notes a list shows before "Show N more": after a year the list stays a short read. */
private const val SHOWN_NOTES = 3

/**
 * What helps him, or what he is good at: one row each, its words on at most two lines, and how often he said it
 * written out under it (a tick when he confirmed it). The surest come first; after three, "Show N more". A row
 * opens its proof sheet over this page.
 */
/**
 * Synthesizes a cohesive narrative summary from verified notes.
 */
private fun synthesizeLessonsSummary(title: String, notes: List<NoteRow>): String {
    if (notes.isEmpty()) return ""
    val isStrength = title.contains("good at", ignoreCase = true) || notes.first().kind == "strength"
    val cleaned = notes.map { it.text.trim().trimEnd('.') }
    return if (isStrength) {
        when (cleaned.size) {
            1 -> "Your strongest verified capability is ${cleaned[0]}. This serves as a reliable personal anchor across your work and challenges."
            2 -> "Across your sessions, your core strengths emerge around ${cleaned[0]}, backed by ${cleaned[1]}. When you lean into these together, you build natural momentum."
            else -> "Across your journey, your core strengths center on ${cleaned[0]}. You consistently thrive when pairing this with ${cleaned[1]}, supported by ${cleaned[2]}."
        }
    } else {
        when (cleaned.size) {
            1 -> "What reliably protects your focus and state: ${cleaned[0]}. This has proven to be your most effective foundation."
            2 -> "Your most reliable support system combines ${cleaned[0]} with ${cleaned[1]}. Together, these consistently keep you grounded and moving forward."
            else -> "Your proven life anchors: you function at your best when prioritizing ${cleaned[0]}, backed by ${cleaned[1]} and ${cleaned[2]}."
        }
    }
}

/**
 * What helps him, or what he is good at: a clean, collapsible folder dossier.
 * Collapsed by default to prevent clutter over time. When tapped, it reveals
 * a synthesized lessons summary, numbered takeaways, and clickable tag pills at the bottom.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NoteList(title: String, notes: List<NoteRow>, onProof: (NoteRow) -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(false) }
    if (notes.isEmpty()) return
    val summaryText = synthesizeLessonsSummary(title, notes)

    Column(
        Modifier
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .fillMaxWidth()
            .background(clarity.surface, Bold.cardShape)
            .border(
                1.dp,
                if (open) clarity.yellow.copy(alpha = 0.45f) else clarity.hairline,
                Bold.cardShape,
            ),
    ) {
        // Folder Header Row (always visible, tap to toggle)
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { open = !open }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier
                    .size(42.dp)
                    .background(
                        if (open) clarity.yellow.copy(alpha = 0.16f) else clarity.raised,
                        RoundedCornerShape(10.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (open) Icons.Outlined.FolderOpen else Icons.Outlined.Folder,
                    contentDescription = null,
                    tint = if (open) clarity.yellow else clarity.muted,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = clarity.text,
                )
                Text(
                    if (open) "Synthesized lessons & evidence" else "${notes.size} ${if (notes.size == 1) "lesson" else "lessons"} · Tap to open folder",
                    style = MaterialTheme.typography.bodySmall,
                    color = clarity.muted,
                )
            }
            Text(
                "${notes.size}",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (open) clarity.ground else clarity.muted,
                modifier = Modifier
                    .background(if (open) clarity.yellow else clarity.raised, Bold.pillShape)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
            Icon(
                if (open) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                contentDescription = if (open) "Close folder" else "Open folder",
                tint = clarity.muted,
            )
        }

        // Inside the Folder (expanded view)
        if (open) {
            HorizontalDivider(color = clarity.hairline, modifier = Modifier.padding(horizontal = 16.dp))

            Column(
                Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // 1. Synthesized Summary Card
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(clarity.raised, RoundedCornerShape(12.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            Icons.Outlined.AutoStories,
                            contentDescription = null,
                            tint = clarity.yellow,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            "Synthesized Summary",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = clarity.yellow,
                        )
                    }
                    Text(
                        summaryText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = clarity.text,
                        lineHeight = 22.sp,
                    )
                }

                // 2. Individual Lessons List
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Core Lessons",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = clarity.muted,
                    )
                    notes.forEachIndexed { i, n ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.Button) { onProof(n) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                "${i + 1}.",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = clarity.yellow,
                                modifier = Modifier.padding(top = 1.dp),
                            )
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    n.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = clarity.text,
                                    lineHeight = 21.sp,
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.padding(top = 2.dp),
                                ) {
                                    if (n.confidence == "confirmed") {
                                        Icon(
                                            Icons.Outlined.Check,
                                            contentDescription = "Confirmed by you",
                                            tint = clarity.green,
                                            modifier = Modifier.size(13.dp),
                                        )
                                        Text("Confirmed", style = MaterialTheme.typography.labelSmall, color = clarity.green)
                                        Text("·", style = MaterialTheme.typography.labelSmall, color = clarity.muted)
                                    }
                                    Text(
                                        if (n.timesSeen == 1) "Seen once in your words" else "Seen ${n.timesSeen} times in your words",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = clarity.muted,
                                    )
                                }
                            }
                            Icon(
                                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                contentDescription = "See proof",
                                tint = clarity.controlOutline,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }

                // 3. Interactive Tag Pills at the Bottom
                HorizontalDivider(color = clarity.hairline)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Tags (tap to see proof quotes)",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = clarity.muted,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        notes.forEach { n ->
                            val confirmed = n.confidence == "confirmed"
                            Row(
                                modifier = Modifier
                                    .background(
                                        if (confirmed) clarity.yellow.copy(alpha = 0.15f) else clarity.raised,
                                        Bold.pillShape,
                                    )
                                    .border(
                                        1.dp,
                                        if (confirmed) clarity.yellow.copy(alpha = 0.4f) else clarity.hairline,
                                        Bold.pillShape,
                                    )
                                    .clickable { onProof(n) }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                if (confirmed) {
                                    Icon(
                                        Icons.Outlined.Check,
                                        contentDescription = null,
                                        tint = clarity.green,
                                        modifier = Modifier.size(12.dp),
                                    )
                                }
                                Text(
                                    n.text,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = clarity.text,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Years(s: StoryViewModel.State, onChapter: (Long) -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text("Your years", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = clarity.text)
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
