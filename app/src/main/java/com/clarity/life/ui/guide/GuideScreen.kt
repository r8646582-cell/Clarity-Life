package com.clarity.life.ui.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.clarity.life.ui.theme.BoldCard
import com.clarity.life.ui.theme.Kicker
import com.clarity.life.ui.theme.clarity

/**
 * How Clarity works: a plain guide to the whole app, opened by a tap (the ? on Talk, or Settings). It is read
 * only, asks nothing and saves nothing; it is not an onboarding questionnaire (spec section 15 keeps those out).
 * Added at the owner's request, 13 Oct 2026, so a new person can find their way without help.
 */
@Composable
fun GuideScreen(onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(clarity.ground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconButton(onClick = onBack, modifier = Modifier.padding(top = 4.dp)) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = clarity.text)
        }
        Column(Modifier.padding(start = 8.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("How Clarity works", style = MaterialTheme.typography.displayLarge, color = clarity.text)
            Text(
                "A coach you talk to, and a quiet place for your goals, plans and promises. Five minutes to read.",
                style = MaterialTheme.typography.bodyLarge,
                color = clarity.muted,
            )
        }
        GUIDE.forEach { part -> Part(part) }
        Text(
            "You can open this guide again any time from the ? on Talk, or from Settings.",
            style = MaterialTheme.typography.bodySmall,
            color = clarity.muted,
            modifier = Modifier.padding(start = 8.dp, top = 4.dp),
        )
        Spacer(Modifier.height(32.dp))
    }
}

private class GuidePart(val icon: ImageVector, val kicker: String, val title: String, val points: List<String>)

private val GUIDE = listOf(
    GuidePart(
        Icons.Outlined.Key,
        "First",
        "Give the coach a key",
        listOf(
            "The coach thinks with an AI service (DeepSeek unless you pick another). It needs a key: a long password for apps, from your account there.",
            "Settings, Coach: tap Get a key, sign up, add a little credit, make an API key, copy it, and paste it under API key. Then tap Test connection.",
            "Until a key is saved, Talk shows a card with these same steps.",
        ),
    ),
    GuidePart(
        Icons.Outlined.ChatBubbleOutline,
        "Talk tab",
        "Talk about anything",
        listOf(
            "Type in the box at the bottom and send. A goal, a hard day, a choice: all welcome.",
            "Each conversation has a kind, shown under its title: Free talk, Untangle (sort out a tangle of thoughts), Decide (weigh a choice) or Plan and track (goals and numbers). Tap it to switch.",
            "☰ opens all your conversations. Tap one to go back to it; ⋯ on a row renames it or forgets it. Tap outside the list to close it.",
            "Press and hold a reply to copy it.",
        ),
    ),
    GuidePart(
        Icons.Outlined.TouchApp,
        "The one rule",
        "Nothing is saved until you tap",
        listOf(
            "The coach only talks. When it suggests something to keep, a button appears under its reply: Set it for a promise, Save plan for a plan, Keep it for something it learned, Switch for another kind of chat.",
            "Tap the button to save it, or Not now to let it go. If you do not tap, nothing changes.",
            "If the coach mentions a button that is not there, a short red line says so. Just ask for it again.",
        ),
    ),
    GuidePart(
        Icons.Outlined.NotificationsNone,
        "Promises",
        "Small promises, with a reminder",
        listOf(
            "A promise is one thing you will do at a time, like \"Watch the first lecture at 8 am\". Tap Set it and your phone reminds you then.",
            "Afterwards, answer it: Did it, Part, or Not. Not is never a failure; it helps the coach plan better.",
            "All promises are on Plan, Promises. If reminders come late, open Settings, Keep reminders reliable.",
        ),
    ),
    GuidePart(
        Icons.AutoMirrored.Outlined.ListAlt,
        "Plan tab",
        "Goals, today, and how it is going",
        listOf(
            "Today: what your plan asks of you today. Tap Did it, Part or Not, or the number you did. A day you skip is just \"no answer\", never a miss.",
            "Goals: what you are working toward, with targets the app counts for you. Make them with the coach (Plan and track) or yourself.",
            "Reviews: each week the app looks at how it went, and on Sunday evening the coach writes you a letter.",
        ),
    ),
    GuidePart(
        Icons.Outlined.AutoAwesome,
        "What I know tab",
        "What the coach has learned",
        listOf(
            "After you talk, the coach notes what matters: patterns, what helps, people, open threads. Only your own words count as proof.",
            "Tap a note to see why it is there. Tap That's right to confirm it, Edit to fix it, or Delete to remove it. A deleted note is never brought back.",
            "Your story shows the longer view: how long you have been at it, and what has helped.",
        ),
    ),
    GuidePart(
        Icons.Outlined.Lock,
        "Your data",
        "It stays on this phone",
        listOf(
            "Everything is kept on this phone, encrypted. Only your messages to the coach go to the AI service you chose. No accounts, no ads, no tracking.",
            "Make a backup in Settings, Your data: choose a folder (Google Drive works) and a passphrase. Write the passphrase on paper; without it a backup cannot be opened.",
            "To update the app, install the new version over the old one. Never uninstall first: that erases everything.",
        ),
    ),
)

@Composable
private fun Part(part: GuidePart) {
    BoldCard(gap = 10) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(40.dp).background(clarity.raised, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(part.icon, contentDescription = null, tint = clarity.yellow) }
            Column(Modifier.weight(1f)) {
                Kicker(part.kicker, clarity.yellow)
                Text(part.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = clarity.text)
            }
        }
        part.points.forEach { point ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.padding(top = 9.dp).size(6.dp).background(clarity.yellow, RoundedCornerShape(3.dp)),
                )
                Text(point, style = MaterialTheme.typography.bodyMedium, color = clarity.text, modifier = Modifier.weight(1f))
            }
        }
    }
}
