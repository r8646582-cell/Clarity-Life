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
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Build
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
                "A coach you talk to, and a quiet place for your goals, plans and promises. Start here takes two minutes; the rest is there when you want it.",
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
                "Talk about anything",
                listOf(
                    "Type in the box at the bottom of Talk and send. A goal, a hard day, a choice: all welcome. Write the way you would to a friend.",
                    "Talk's home also offers two or three ways to start, like \"Something is on my mind\". Tap one to begin.",
                    "The reply appears as it is written. You can leave the app; it keeps writing and is there when you come back.",
                ),
            ),
            GuidePart(
                Icons.Outlined.TouchApp,
                "Step 3",
                "Nothing is saved until you tap",
                listOf(
                    "The coach only talks. When it suggests something to keep, a button appears under its reply: Set it for a promise, Save plan for a plan, Keep it for something it learned, Switch for another kind of chat.",
                    "Tap the button to save it, or Not now to let it go. If you do nothing, nothing changes.",
                    "Every date, day count and number you see is worked out by the app, not guessed by the coach.",
                ),
            ),
        ),
    ),
    GuideLevel(
        "Every day",
        "The habits that make Clarity useful.",
        listOf(
            GuidePart(
                Icons.Outlined.NotificationsNone,
                "Promises",
                "Small promises, with a reminder",
                listOf(
                    "A promise is one thing you will do at a time, like \"Watch the first lecture at 8 am\". Ask the coach for one, check the day and time on the card, and tap Set it. Your phone reminds you then.",
                    "Afterwards, answer it: Done, Not done, or Moved to a new time. For Not done, tap a reason (tired, no time, forgot, felt too big...). Not done is never a failure; the reasons help the coach make the next promise fit.",
                    "All promises are on Plan, Promises. While one is due and not answered, the Plan tab at the bottom shows a dot; it goes once you answer.",
                ),
            ),
            GuidePart(
                Icons.AutoMirrored.Outlined.ListAlt,
                "Plan tab",
                "Today and your goals",
                listOf(
                    "Today: what your plan asks of you today. Tap Did it, Part or Not, or the number you did. Until you answer, a dot shows on the Plan tab and on Today.",
                    "A day you skip is just \"no answer\", never a miss. A planned rest day counts as neither.",
                    "Goals: what you are working toward, with targets the app counts for you and a label saying how it is going.",
                ),
            ),
            GuidePart(
                Icons.Outlined.AutoAwesome,
                "What I know tab",
                "What the coach has learned",
                listOf(
                    "After you talk, the coach notes what matters: your profile, patterns, what helps and what does not, people, open threads. Only your own words count as proof.",
                    "Tap a note to see the words it came from. Tap That's right to confirm it, Edit to fix it, or Delete to remove it. A deleted note is never brought back.",
                    "The coach reads these notes in every conversation, so a correct, confirmed note makes every future talk better.",
                ),
            ),
        ),
    ),
    GuideLevel(
        "Go further",
        "Everything else, for when the basics feel easy.",
        listOf(
            GuidePart(
                Icons.Outlined.Forum,
                "Kinds of talk",
                "Pick the right kind of conversation",
                listOf(
                    "Under each conversation's title is its kind. Tap it to switch at any time; what you said so far stays.",
                    "Free talk: anything. Untangle: sort out a knot of thoughts or feelings. Decide: weigh a choice, step by step. Plan and track: build or fix a plan; this one sees all your goals and numbers.",
                    "☰ lists every conversation, grouped by day. Search it, or tap ⋯ on a row to rename it or forget it. Forget also removes anything learned only from it.",
                    "Press and hold a reply to copy it or see its details.",
                ),
            ),
            GuidePart(
                Icons.Outlined.Flag,
                "Plans",
                "Make a plan that holds",
                listOf(
                    "On Plan, Goals, tap Plan with coach. Tell it the goal, the deadline and what a normal day looks like for you. It answers with a plan card; check it and tap Save plan.",
                    "Or tap Write a goal and fill it in yourself.",
                    "Once you have answered a few days, the app works out your real pace and whether it reaches the goal in time. If it does not, ask the coach in Plan and track to adjust the plan; it sees those numbers too.",
                ),
            ),
            GuidePart(
                Icons.Outlined.CenterFocusStrong,
                "Focuses",
                "Follow one big thing for months",
                listOf(
                    "A focus is a long-running subject, like \"social anxiety\" or \"the final exam\". The coach may offer one as a card; tap to start it.",
                    "Talk inside a focus (pick it under a conversation's title) and the coach remembers the whole story of it, not just today's chat.",
                    "Its summary is in What I know, Focuses: edit it, pause it, or mark it done.",
                ),
            ),
            GuidePart(
                Icons.Outlined.MailOutline,
                "Reviews and letters",
                "Look back, every week",
                listOf(
                    "Every Sunday evening the coach writes you a letter about your week. A dot on Plan means one is waiting; it is under Plan, Reviews.",
                    "In the letter, tap Talk about this letter to discuss it, or See the numbers for the week's facts. Each month there is a review too, plan against what happened.",
                    "Over time What I know builds Your story and Chapters: the longer view of your year.",
                ),
            ),
            GuidePart(
                Icons.Outlined.Lightbulb,
                "Pro tips",
                "Get the most from your coach",
                listOf(
                    "Be specific: \"I watched 2 of 4 lectures, I was tired after 6 pm\" helps far more than \"it went badly\".",
                    "Ask for exactly what you want: \"give me a promise for tonight\", \"make me a plan to finish by 30 Nov\", \"just listen\".",
                    "Answer your check-ins, even with Not. The weekly letter and your pace are only as good as your answers.",
                    "Confirm notes that are right and fix the ones that are not. Read the Sunday letter, then talk about it.",
                    "Keep promises small. One you can do tonight beats a big one you avoid.",
                ),
            ),
            GuidePart(
                Icons.Outlined.Build,
                "If something goes wrong",
                "Quick fixes",
                listOf(
                    "A reply failed or stopped: tap Try again under it. If it keeps failing, Settings, Coach, Test connection says why (a wrong key, no credit, or the service is down).",
                    "A red line says the coach spoke of a button that is not there: ask for it again, for example \"send the promise card\".",
                    "Reminders late or missing: Settings, Keep reminders reliable. Allow notifications, no battery limits, autostart and alarms. On Xiaomi phones this matters a lot.",
                    "If the app ever closes by itself, Settings, Advanced shows what happened, with no messages or keys in it.",
                ),
            ),
            GuidePart(
                Icons.Outlined.Lock,
                "Your data and settings",
                "It stays on this phone",
                listOf(
                    "Everything is kept on this phone, encrypted. Only your messages to the coach go to the AI service you chose. No accounts, no ads, no tracking.",
                    "Back up in Settings, Your data: choose a folder (Google Drive works) and a passphrase. Write the passphrase on paper; without it a backup cannot be opened. Restore brings it all back on a new phone.",
                    "Settings also has: app lock (fingerprint), hide in recents, when your day ends, the Sunday letter and its alert, how hard the coach thinks (Low is quick; High and Max are slower and more careful), Export everything, and Erase everything.",
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
