package com.clarity.life.ui.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.clarity.life.data.entity.ReviewRow
import com.clarity.life.ui.theme.clarity
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import com.clarity.life.ui.theme.BoldCard
import com.clarity.life.ui.theme.Headline
import com.clarity.life.ui.theme.Kicker
import com.clarity.life.ui.theme.OutlineCard
import com.clarity.life.ui.theme.Quiet
import com.clarity.life.ui.theme.TextAction

/**
 * Plan, Reviews (docs/mockups/Reviews.dc.html): Weeks (the letter and the numbers of each week, a yellow dot while
 * the letter is unread, one quiet "delayed" line with Try again) and Months. A week opens its letter; the
 * letter's See the numbers opens the weekly review (Review.dc.html), which has Talk about it.
 */
@Composable
fun ReviewsSection(
    state: ReviewsViewModel.State,
    model: ReviewsViewModel,
    openLetterId: Long?,
    openReviewId: Long?,
    onOpenLetter: (Long?) -> Unit,
    onOpenReview: (Long?) -> Unit,
    onTalk: () -> Unit,
) {
    val letter = state.weeks.firstNotNullOfOrNull { w -> w.letter?.takeIf { it.id == openLetterId } }
    val review = (state.weeks.mapNotNull { it.review } + state.months).firstOrNull { it.id == openReviewId }
    if (review != null) {
        androidx.activity.compose.BackHandler { onOpenReview(null) }
        ReviewPage(review, state, model, onBack = { onOpenReview(null) }, onTalk = onTalk)
        return
    }
    if (letter != null) {
        val week = state.weeks.first { it.letter?.id == letter.id }
        androidx.activity.compose.BackHandler { onOpenLetter(null) }
        LaunchedEffect(letter.id) { model.openLetter(letter.id) }
        LetterPage(
            title = letter.title,
            body = letter.body,
            range = model.weekRange(week.monday),
            onBack = { onOpenLetter(null) },
            onTalk = {
                model.talkAboutLetter(letter.id)
                onTalk()
            },
            onNumbers = week.review?.let { r -> { onOpenReview(r.id) } },
            onDelete = {
                model.deleteLetter(letter.id)
                onOpenLetter(null)
            },
        )
        return
    }
    val newest = state.weeks.firstOrNull()
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 12.dp)) {
        Headline(
            when {
                newest?.letter != null && newest.letter.readAt == null -> "Your letter is here."
                state.delayedLetter != null -> "This week's letter is a little late."
                newest == null -> "Your first letter comes on Sunday."
                else -> "Your weeks, in letters."
            },
        )
        if (newest == null) {
            Quiet("Once something is tracked, a letter about your week arrives every Sunday evening, with the numbers behind it.")
        }
        state.delayedLetter?.let { d -> DelayedLine("This week's letter is delayed") { model.retryLetter(d) } }
        newest?.let { w ->
            BoldCard(gap = 10, onClick = { if (w.letter != null) onOpenLetter(w.letter.id) else w.review?.let { onOpenReview(it.id) } }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Kicker("Week of " + model.weekRange(w.monday), clarity.yellow)
                    Spacer(Modifier.weight(1f))
                    if (w.letter != null && w.letter.readAt == null) Box(Modifier.size(8.dp).background(clarity.yellow, CircleShape))
                }
                Text(
                    w.letter?.title ?: "This week's numbers",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = clarity.text,
                )
                w.letter?.body?.let { excerpt(it) }?.takeIf { it.isNotBlank() }?.let { Quiet(it) }
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    if (w.letter != null) TextAction("Read the letter") { onOpenLetter(w.letter.id) }
                    w.review?.let { r -> TextAction("See the numbers", color = clarity.muted) { onOpenReview(r.id) } }
                }
            }
        }
        val earlier = state.weeks.drop(1)
        if (earlier.isNotEmpty()) {
            Column {
                Text("Earlier", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = clarity.text, modifier = Modifier.padding(bottom = 4.dp))
                earlier.forEachIndexed { i, w ->
                    if (i > 0) HorizontalDivider(color = clarity.hairline)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp)
                            .clickable { if (w.letter != null) onOpenLetter(w.letter.id) else w.review?.let { onOpenReview(it.id) } }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(w.letter?.title ?: "Weekly review", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = clarity.text)
                            Quiet(model.weekRange(w.monday) + if (w.letter != null && w.letter.readAt == null) " · new" else "")
                        }
                        Text("›", style = MaterialTheme.typography.titleLarge, color = clarity.muted)
                    }
                }
            }
        }
        if (state.months.isEmpty()) {
            OutlineCard {
                Text("Your month in one page", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = clarity.text)
                Quiet("A monthly review arrives once a month you tracked has ended.")
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Months", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = clarity.text)
                state.months.forEach { m ->
                    OutlineCard(modifier = Modifier.clickable { onOpenReview(m.id) }) {
                        Quiet(model.monthName(m.periodStart))
                        Text(model.headline(m) ?: "Monthly review", style = MaterialTheme.typography.bodyLarge, color = clarity.text)
                    }
                }
            }
        }
        Quiet("A new letter arrives on Sunday evening. Chapters, one every three months, arrive in What I know.")
        Spacer(Modifier.height(24.dp))
    }
}

/** The opening of a letter for its card: its first paragraph, plain, cut at a word near 170 characters. */
private fun excerpt(body: String): String {
    val first = body.trim().split(Regex("\\n\\s*\\n")).firstOrNull { it.isNotBlank() }.orEmpty()
        .replace("**", "").replace(Regex("\\s+"), " ").trim()
    if (first.length <= 170) return first
    val cut = first.take(170)
    return cut.substring(0, cut.lastIndexOf(' ').takeIf { it > 100 } ?: cut.length).trimEnd(',', ';', ':') + "…"
}

@Composable
private fun LetterPage(
    title: String,
    body: String,
    range: String,
    onBack: () -> Unit,
    onTalk: () -> Unit,
    onNumbers: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    var asking by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        TextButton(onClick = onBack) { Text("‹ Reviews", color = clarity.yellow) }
        Muted("Your letter · $range")
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = clarity.text)
        body.split(Regex("\n\\s*\n")).filter { it.isNotBlank() }.forEach {
            Text(it.trim(), style = MaterialTheme.typography.bodyLarge, color = clarity.text)
        }
        PlanButton("Talk about this letter", filled = true, onClick = onTalk)
        onNumbers?.let { PlanButton("See the numbers", filled = false, onClick = it) }
        TextButton(onClick = { asking = true }) { Text("Delete this letter", color = clarity.danger) }
        Text("", modifier = Modifier.padding(bottom = 24.dp))
    }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            confirmButton = {
                TextButton(onClick = {
                    asking = false
                    onDelete()
                }) { Text("Delete", color = clarity.danger) }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text("Keep it", color = clarity.yellow) } },
            title = { Text("Delete this letter?") },
            text = { Text("It is gone for good, and this week's letter will not be written again.") },
            containerColor = clarity.surface,
            titleContentColor = clarity.text,
            textContentColor = clarity.muted,
        )
    }
}

@Composable
private fun ReviewPage(
    r: ReviewRow,
    state: ReviewsViewModel.State,
    model: ReviewsViewModel,
    onBack: () -> Unit,
    onTalk: () -> Unit,
) {
    LaunchedEffect(r.id) { model.openReview(r.id) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(onClick = onBack) { Text("‹ Reviews", color = clarity.yellow) }
        Muted(if (r.kind == "week") "Weekly review" else "Monthly review")
        Text(
            if (r.kind == "week") model.range(r.periodStart, r.periodEnd) else model.monthName(r.periodStart),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = clarity.text,
        )
        if (r.replanFlag == 1) {
            Column(
                Modifier.fillMaxWidth().background(clarity.coral, RoundedCornerShape(14.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("The plan may be off.", style = MaterialTheme.typography.titleMedium, color = clarity.ground)
                Text(
                    "A backlog fell short of its pace in two reviews in a row.",
                    style = MaterialTheme.typography.bodySmall,
                    color = clarity.ground,
                )
                Text(
                    "Talk about it",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = clarity.text,
                    modifier = Modifier
                        .heightIn(min = 44.dp)
                        .background(clarity.ground, RoundedCornerShape(10.dp))
                        .clickable {
                            model.talkAboutReview(r.id)
                            onTalk()
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
        Column(
            Modifier.fillMaxWidth().background(clarity.surface, RoundedCornerShape(16.dp)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Counted by the app", style = MaterialTheme.typography.labelLarge, color = clarity.muted)
            model.facts(r).forEach { line ->
                Text(
                    line.trimStart(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (line.startsWith("  ")) clarity.muted else clarity.text,
                    modifier = Modifier.padding(start = if (line.startsWith("  ")) 12.dp else 0.dp),
                )
            }
        }
        model.headline(r)?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = clarity.text) }
        when (state.notes[r.id]) {
            ReviewsViewModel.Note.WRITTEN -> model.narrative(r).split(Regex("\n\\s*\n")).filter { it.isNotBlank() }.forEach {
                Text(it.trim(), style = MaterialTheme.typography.bodyLarge, color = clarity.text)
            }
            ReviewsViewModel.Note.TOO_LITTLE -> Muted("Too few answers for a note this time. The numbers are all there is.")
            ReviewsViewModel.Note.DELAYED -> DelayedLine("The coach's note is delayed") { model.retryReview(r.id) }
            else -> Muted("The coach's note is being written. It appears here when it has passed the checks.")
        }
        val revisits = model.revisits(r)
        if (revisits.isNotEmpty()) {
            Heading("Worth talking about")
            revisits.forEach { q ->
                Text(
                    q.question,
                    style = MaterialTheme.typography.bodyMedium,
                    color = clarity.text,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .border(1.dp, clarity.controlOutline, RoundedCornerShape(12.dp))
                        .clickable {
                            model.talkAboutReview(r.id)
                            onTalk()
                        }
                        .padding(12.dp),
                )
            }
            Muted("Each one opens a Plan and track conversation. Nothing changes unless you tap a card there.")
        }
        if (r.replanFlag == 0) {
            PlanButton("Talk about it", filled = false) {
                model.talkAboutReview(r.id)
                onTalk()
            }
        }
        Text("", modifier = Modifier.padding(bottom = 24.dp))
    }
}

@Composable
private fun DelayedLine(text: String, onRetry: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = clarity.danger, modifier = Modifier.weight(1f))
        TextButton(onClick = onRetry) { Text("Try again", color = clarity.yellow) }
    }
}

