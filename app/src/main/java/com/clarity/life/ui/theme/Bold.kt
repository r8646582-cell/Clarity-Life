package com.clarity.life.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The redesign's building blocks (the owner's approved mockups of 13 Oct 2026, artifact "Clarity Goal screen
 * redesign"): one soft card, one outlined card, a small pill, a kicker label, a big fact and the two buttons.
 * Every screen uses these, so the app reads as one design: one clear thing at the top, calm cards below.
 */
object Bold {
    val cardShape = RoundedCornerShape(22.dp)
    val buttonShape = RoundedCornerShape(18.dp)
    val pillShape = RoundedCornerShape(999.dp)
}

/** The soft surface card that holds one idea. */
@Composable
fun BoldCard(
    modifier: Modifier = Modifier,
    gap: Int = 12,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(clarity.surface, Bold.cardShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(gap.dp),
        content = content,
    )
}

/** The quieter outlined card, for what is next or secondary. */
@Composable
fun OutlineCard(modifier: Modifier = Modifier, gap: Int = 6, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .border(1.dp, clarity.hairline, Bold.cardShape)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(gap.dp),
        content = content,
    )
}

/** A small status pill: "Starts tomorrow, Wed 14 Oct", "On track". Warm when [warn]. */
@Composable
fun Pill(text: String, warn: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = if (warn) clarity.ground else clarity.yellow,
        modifier = Modifier
            .background(if (warn) clarity.coral else clarity.raised, Bold.pillShape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** The small capital label above a card's main line: "DUE TONIGHT", "NEW THIS WEEK". */
@Composable
fun Kicker(text: String, color: Color = clarity.yellow) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
        color = color,
    )
}

/** The one big fact of a screen, in yellow. */
@Composable
fun BigFact(text: String, color: Color = clarity.yellow) {
    Text(text, fontSize = 42.sp, lineHeight = 46.sp, fontWeight = FontWeight.Bold, color = color)
}

/** The screen's headline: the one thing that matters now. */
@Composable
fun Headline(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        fontSize = 30.sp,
        lineHeight = 35.sp,
        fontWeight = FontWeight.Bold,
        color = clarity.text,
        modifier = modifier,
    )
}

@Composable
fun Quiet(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = clarity.muted, modifier = modifier)
}

/** The one main action of a screen. */
@Composable
fun PrimaryButton(label: String, modifier: Modifier = Modifier.fillMaxWidth(), onClick: () -> Unit) {
    Box(
        modifier
            .heightIn(min = 54.dp)
            .background(clarity.yellow, Bold.buttonShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = clarity.ground, textAlign = TextAlign.Center)
    }
}

/** A secondary action, outlined. */
@Composable
fun SecondaryButton(label: String, modifier: Modifier = Modifier.fillMaxWidth(), onClick: () -> Unit) {
    Box(
        modifier
            .heightIn(min = 48.dp)
            .border(1.dp, clarity.controlOutline, Bold.buttonShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = clarity.text, textAlign = TextAlign.Center)
    }
}

/** A text-only action: Edit, See all, Make today a rest day. */
@Composable
fun TextAction(label: String, color: Color = clarity.yellow, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        color = color,
        modifier = modifier.heightIn(min = 44.dp).clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 2.dp),
    )
}
