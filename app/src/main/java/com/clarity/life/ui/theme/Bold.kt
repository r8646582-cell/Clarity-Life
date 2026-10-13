package com.clarity.life.ui.theme

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The 0.1.6 redesign's building blocks (docs/mockups/redesign, approved 13 Oct 2026): one bordered surface card,
 * a quieter outlined card, the calm yellow offer card, a pill, a kicker label, a big serif fact, the coach's
 * presence dot and the buttons. Every screen uses these, so the app reads as one design.
 */
object Bold {
    val cardShape = RoundedCornerShape(22.dp)
    val buttonShape = RoundedCornerShape(14.dp)
    val pillShape = RoundedCornerShape(999.dp)
}

/** The surface card that holds one idea: a soft fill with a hairline edge. */
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
            .border(1.dp, clarity.hairline, Bold.cardShape)
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

/** A calm offer: a warm tint, never red. Used when something could be better (the goal's plan falls short). */
@Composable
fun CalmCard(modifier: Modifier = Modifier, gap: Int = 12, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .background(clarity.yellowTint, Bold.cardShape)
            .border(1.dp, clarity.yellowEdge, Bold.cardShape)
            .padding(22.dp),
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

/** The small capital label above a card's main line: "TODAY", "DUE TONIGHT". */
@Composable
fun Kicker(text: String, color: Color = clarity.muted, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        letterSpacing = 1.sp,
        color = color,
        modifier = modifier,
    )
}

/** The one big fact of a screen, in the serif. */
@Composable
fun BigFact(text: String, color: Color = clarity.yellow) {
    Text(text, style = MaterialTheme.typography.displayMedium, fontSize = 48.sp, lineHeight = 50.sp, color = color)
}

/** The screen's headline: the one thing that matters now, in the serif. */
@Composable
fun Headline(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.displaySmall, color = clarity.text, modifier = modifier)
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
            .heightIn(min = 52.dp)
            .background(clarity.yellow, Bold.buttonShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = clarity.onYellow, textAlign = TextAlign.Center)
    }
}

/** A secondary action: a raised fill with an edge, so it reads as a real button. */
@Composable
fun SecondaryButton(label: String, modifier: Modifier = Modifier.fillMaxWidth(), onClick: () -> Unit) {
    Box(
        modifier
            .heightIn(min = 48.dp)
            .background(clarity.raised, Bold.buttonShape)
            .border(1.dp, clarity.controlOutline, Bold.buttonShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = clarity.text, textAlign = TextAlign.Center)
    }
}

/** A destructive action (Erase everything, Delete): outlined in the danger colour, never filled. */
@Composable
fun DangerButton(label: String, modifier: Modifier = Modifier.fillMaxWidth(), onClick: () -> Unit) {
    Box(
        modifier
            .heightIn(min = 48.dp)
            .border(1.dp, clarity.coralEdge, Bold.buttonShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = clarity.danger, textAlign = TextAlign.Center)
    }
}

/** A text-only action: Edit, Not now. */
@Composable
fun TextAction(label: String, color: Color = clarity.yellow, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        color = color,
        modifier = modifier.heightIn(min = 44.dp).clickable(role = Role.Button, onClick = onClick).padding(vertical = 10.dp, horizontal = 2.dp),
    )
}

/**
 * The coach's presence mark: a small yellow dot with a soft halo. While the coach is writing ([alive]) the
 * halo breathes, so a reply in progress never looks frozen.
 */
@Composable
fun CoachDot(alive: Boolean = false, size: Dp = 10.dp, modifier: Modifier = Modifier) {
    val yellow = clarity.yellow
    val pulse = if (alive) {
        val t = rememberInfiniteTransition(label = "coach")
        val v by t.animateFloat(0.25f, 0.9f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "halo")
        v
    } else {
        0.35f
    }
    Canvas(modifier.size(size * 2.2f)) {
        val c = Offset(this.size.width / 2, this.size.height / 2)
        val r = size.toPx() / 2
        drawCircle(yellow.copy(alpha = 0.10f * pulse * 2), radius = r * 2.1f, center = c)
        drawCircle(yellow.copy(alpha = 0.18f * pulse * 2), radius = r * 1.5f, center = c)
        drawCircle(yellow, radius = r, center = c)
    }
}

/** A small green tick in a soft circle: saved, kept, done. */
@Composable
fun GreenTick(size: Dp = 26.dp) {
    val green = clarity.green
    Box(Modifier.size(size).background(clarity.greenTint, Bold.pillShape), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size * 0.46f)) {
            val w = this.size.width
            val h = this.size.height
            val s = Stroke(width = w * 0.15f, cap = StrokeCap.Round)
            drawLine(green, Offset(w * 0.08f, h * 0.55f), Offset(w * 0.4f, h * 0.85f), strokeWidth = s.width, cap = StrokeCap.Round)
            drawLine(green, Offset(w * 0.4f, h * 0.85f), Offset(w * 0.95f, h * 0.15f), strokeWidth = s.width, cap = StrokeCap.Round)
        }
    }
}
