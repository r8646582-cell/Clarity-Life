package com.clarity.life.ui.letters

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clarity.life.ui.plan.ReviewsViewModel
import com.clarity.life.ui.theme.BoldCard
import com.clarity.life.ui.theme.Headline
import com.clarity.life.ui.theme.Kicker
import com.clarity.life.ui.theme.PrimaryButton
import com.clarity.life.ui.theme.Quiet
import com.clarity.life.ui.theme.clarity

/**
 * Letters (docs/mockups-v2/Letters.dc.html and Letter.dc.html): one list, newest first, each with its kind
 * (WEEKLY, MONTHLY, YEARLY). A letter opens as plain text with "Talk about this letter". Reading never needs
 * the network. The deleted letters stay hidden (their rows remain so they are never written again).
 */
@Composable
fun LettersScreen(onTalk: () -> Unit, model: ReviewsViewModel = hiltViewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    var openId by rememberSaveable { mutableStateOf<Long?>(null) }
    val shown = state.letters.filter { it.deletedAt == null }.sortedByDescending { it.readyAt }
    val open = shown.firstOrNull { it.id == openId }

    if (open != null) {
        BackHandler { openId = null }
        LaunchedEffect(open.id) { model.openLetter(open.id) }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            TextButton(onClick = { openId = null }) { Text("‹ Letters", color = clarity.yellow) }
            Kicker(kindLabel(model, open) + " · " + model.letterPeriod(open))
            Text(open.title, style = MaterialTheme.typography.displaySmall, color = clarity.text)
            open.body.split(Regex("\n\\s*\n")).filter { it.isNotBlank() }.forEach {
                Text(it.trim(), style = MaterialTheme.typography.headlineSmall, fontSize = 20.sp, lineHeight = 30.sp, color = clarity.text)
            }
            PrimaryButton("Talk about this letter") {
                model.talkAboutLetter(open.id)
                onTalk()
            }
            Text("", modifier = Modifier.padding(bottom = 24.dp))
        }
        return
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Headline("Letters")
        if (shown.isEmpty()) {
            Quiet("Your first letter comes on Sunday, when there is enough to say. A quiet week gets a short letter or none.")
        }
        shown.forEach { letter ->
            BoldCard(gap = 6, onClick = { openId = letter.id }) {
                Kicker(kindLabel(model, letter) + if (letter.readAt == null) " · new" else "")
                Text(letter.title, style = MaterialTheme.typography.headlineSmall, color = clarity.text)
                Quiet(model.letterPeriod(letter))
            }
        }
    }
}

private fun kindLabel(model: ReviewsViewModel, l: com.clarity.life.data.entity.LetterRow): String =
    when (model.kind(l)) {
        com.clarity.life.core.LetterPeriods.MONTH -> "MONTHLY"
        com.clarity.life.core.LetterPeriods.YEAR -> "YEARLY"
        com.clarity.life.core.LetterPeriods.WEEK -> "WEEKLY"
        else -> "MILESTONE"
    }
