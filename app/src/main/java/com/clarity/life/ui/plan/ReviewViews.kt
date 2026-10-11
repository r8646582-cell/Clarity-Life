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
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Heading("Weeks")
        if (state.weeks.isEmpty()) Muted("The first review appears on Sunday evening, once something is tracked.")
        state.weeks.forEachIndexed { i, w ->
            if (i == 0 && state.delayedLetter != null && state.delayedLetter == w.monday && w.letter == null) {
                DelayedLine("This week's letter is delayed") { model.retryLetter(w.monday) }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(clarity.surface, RoundedCornerShape(16.dp))
                    .clickable {
                        if (w.letter != null) onOpenLetter(w.letter.id) else w.review?.let { onOpenReview(it.id) }
                    }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Muted(model.weekRange(w.monday) + if (w.letter?.readAt == null && w.letter != null) " · new" else "")
                    Text(
                        w.letter?.title ?: "Weekly review",
                        style = MaterialTheme.typography.titleMedium,
                        color = clarity.text,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (w.letter != null) SmallChip("Letter")
                        if (w.review != null) {
                            SmallChip("Numbers", Modifier.clickable { onOpenReview(w.review.id) })
                        }
                    }
                }
                if (w.letter != null && w.letter.readAt == null) {
                    Box(Modifier.size(10.dp).background(clarity.yellow, CircleShape))
                }
            }
        }
        if (state.delayedLetter != null && state.weeks.none { it.monday == state.delayedLetter }) {
            DelayedLine("This week's letter is delayed") { model.retryLetter(state.delayedLetter) }
        }
        Heading("Months")
        if (state.months.isEmpty()) Muted("A monthly review appears once a month you tracked has ended.")
        state.months.forEach { m ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, clarity.hairline, RoundedCornerShape(14.dp))
                    .clickable { onOpenReview(m.id) }
                    .padding(12.dp),
            ) {
                Muted(model.monthName(m.periodStart))
                Text(model.headline(m) ?: "Monthly review", style = MaterialTheme.typography.bodyLarge, color = clarity.text)
            }
        }
        Text(
            "A new letter arrives on Sunday evening. Chapters, one every three months, arrive in What I know later.",
            style = MaterialTheme.typography.bodySmall,
            color = clarity.muted,
            modifier = Modifier.padding(top = 12.dp, bottom = 24.dp),
        )
    }
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

@Composable
private fun SmallChip(text: String, modifier: Modifier = Modifier) = Text(
    text,
    style = MaterialTheme.typography.labelMedium,
    color = clarity.text,
    modifier = modifier
        .border(1.dp, clarity.controlOutline, RoundedCornerShape(10.dp))
        .padding(horizontal = 10.dp, vertical = 4.dp),
)

/**
 * The letter card at the top of Talk (docs/mockups/TalkLetter.dc.html): only while a new letter is unread; it
 * goes once the letter is opened.
 */
@Composable
fun LetterCard(state: ReviewsViewModel.State, model: ReviewsViewModel, onRead: () -> Unit) {
    val letter = state.newLetter ?: return
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)
            .border(2.dp, clarity.yellow, RoundedCornerShape(16.dp))
            .padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Muted("Your weekly letter, ${model.range(letter.periodStart, letter.periodEnd)}")
            Text(letter.title, style = MaterialTheme.typography.titleMedium, color = clarity.text)
        }
        Text(
            "Read",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = clarity.ground,
            modifier = Modifier
                .heightIn(min = 44.dp)
                .background(clarity.yellow, RoundedCornerShape(10.dp))
                .clickable {
                    model.showLetter(letter.id)
                    onRead()
                }
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}
