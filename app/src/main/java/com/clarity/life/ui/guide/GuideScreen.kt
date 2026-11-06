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
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
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
                "An honest friend you talk to every day, who remembers and writes you letters. Start here takes two minutes; the rest is there when you want it.",
                style = MaterialTheme.typography.bodyLarge,
                color = clarity.muted,
            )
        }
        GUIDE.forEach { level ->
            Column(Modifier.padding(start = 8.dp, top = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(level.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = clarity.yellow)
                Text(level.line, style = MaterialTheme.typography.bodyMedium, color = clarity.muted)
            }
            level.parts.forEach { part -> Part(part) }
        }
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

/** One level of the guide: Start here, Every day, Go further. */
private class GuideLevel(val name: String, val line: String, val parts: List<GuidePart>)

private val GUIDE = listOf(
    GuideLevel(
        "Start here",
        "The three things to know before your first talk.",
        listOf(
            GuidePart(
                Icons.Outlined.Key,
                "Step 1",
                "Give the coach a key",
                listOf(
                    "The coach thinks with an AI service (DeepSeek unless you pick another). It needs a key: a long password for apps, from your account there.",
                    "Settings, Coach: tap Get a key, sign up, add a little credit, make an API key, copy it, and paste it under API key. Tap Save, then Test connection. \"Connected.\" means you are ready.",
                    "Until a key is saved, Talk shows a card with these same steps.",
                ),
            ),
            GuidePart(
                Icons.Outlined.ChatBubbleOutline,
                "Step 2",
                "Just talk",
                listOf(
                    "Type in the box at the bottom of Talk and send. A plan, a hard day, a choice, a random thought: all welcome. Write the way you would to a friend.",
                    "There is one conversation a day. A new one starts by itself when your day ends, and what the coach learned carries over. Old ones are under ☰.",
                    "The reply appears as it is written. You can leave the app; it keeps writing. With no signal, your message shows \"Waiting to send\" and goes when the network is back.",
                ),
            ),
            GuidePart(
                Icons.Outlined.AutoAwesome,
                "Step 3",
                "It remembers, and you can check",
                listOf(
                    "After you talk, the coach quietly notes what matters: facts about your life, people, patterns, what helps you and what does not. Only your own words count as proof.",
                    "Open Know to see it all. \"Is this right?\" shows what it is not sure of: tap That's right, Not quite to fix it, or Delete. A deleted note never comes back.",
                    "Say \"forget that\" in the chat and the app asks before deleting the note the coach just used.",
                ),
            ),
        ),
    ),
    GuideLevel(
        "Every day",
        "How to get the most from it.",
        listOf(
            GuidePart(
                Icons.Outlined.Flag,
                "Plans",
                "Make a plan together, in the chat",
                listOf(
                    "Tell the coach the goal, the deadline, how much is left and the hours you really have. It checks the numbers honestly, and says so plainly if they do not reach.",
                    "You agree a normal day, a minimum for bad days and what to do when the likeliest thing gets in the way. It never makes up a date.",
                    "The app keeps a short list of what you are working on, at most three big things, on Know. A deadline is taken only from a real date you wrote; anything vaguer, the coach asks about.",
                ),
            ),
            GuidePart(
                Icons.Outlined.Forum,
                "Hard days",
                "Tell it when a day goes wrong",
                listOf(
                    "Say what happened, honestly. It checks whether \"I'll make it up tomorrow\" really fits, looks for the cause without blame, and helps with one small step.",
                    "Something said in anger or exhaustion (\"I'm done with this subject\") never changes your list. Only a calm decision does.",
                    "It is honest both ways: kind when you are hurting, and straight with you when you are fooling yourself.",
                ),
            ),
            GuidePart(
                Icons.Outlined.MailOutline,
                "Letters",
                "A letter every week",
                listOf(
                    "Every Sunday evening the coach writes you a letter about your week: what went well, where it fell apart, and one small thing to try. A quiet week gets a short letter or none.",
                    "There is also a letter for each month, one for the year, and one when you finish something big. They are under Letters.",
                    "Tap Talk about this letter to discuss it. If it got something wrong, say so; it will listen.",
                ),
            ),
        ),
    ),
    GuideLevel(
        "Go further",
        "For when the basics feel easy.",
        listOf(
            GuidePart(
                Icons.Outlined.Lightbulb,
                "Tips",
                "Get the most from your coach",
                listOf(
                    "Be specific: \"I watched 2 of 4 lectures, I was tired after 6 pm\" helps far more than \"it went badly\".",
                    "Ask for what you want: \"just listen\", \"be blunt with me\", \"make me a plan to finish by 30 November\".",
                    "Correct it when it is wrong. Fix notes on Know that are not right, and confirm the ones that are.",
                    "Press and hold a reply to copy it or see its details.",
                ),
            ),
            GuidePart(
                Icons.Outlined.Build,
                "If something goes wrong",
                "Quick fixes",
                listOf(
                    "A reply failed or stopped: tap Try again under it. If it keeps failing, Settings, Coach, Test connection says why (a wrong key, no credit, or the service is down).",
                    "Letters or memory not updating: Settings may say background work is paused until tomorrow. That is a daily safety limit on spending; it resets by itself.",
                    "Notifications late or missing: Settings, Keep reminders reliable. On Xiaomi phones allow autostart and no battery limits.",
                    "If the app ever closes by itself, Settings, Advanced shows what happened, with no messages or keys in it.",
                ),
            ),
            GuidePart(
                Icons.Outlined.Lock,
                "Your data and settings",
                "It stays on this phone",
                listOf(
                    "Everything is kept on this phone, encrypted. Only your messages to the coach go to the AI service you chose. No accounts, no ads, no tracking.",
                    "Back up in Settings, Your data: choose a folder (Google Drive works) and a passphrase. Write the passphrase on paper; without it a backup cannot be opened.",
                    "Settings also has: app lock, hide in recents, when your day ends, the Sunday letter alert, an optional daily nudge (off at first), Export everything and Erase everything.",
                    "To update the app, install the new version over the old one. Never uninstall first: that erases everything.",
                ),
            ),
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
