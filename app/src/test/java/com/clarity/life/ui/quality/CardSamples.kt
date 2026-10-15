package com.clarity.life.ui.quality

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.clarity.life.chat.CardSaver
import com.clarity.life.core.PromiseTimes
import com.clarity.life.data.entity.CardRow
import com.clarity.life.ui.talk.CardActions
import com.clarity.life.ui.talk.SuggestionCard
import com.clarity.life.ui.theme.ClarityTheme
import com.clarity.life.ui.theme.clarity
import java.time.LocalDate
import java.time.LocalTime

/**
 * One card of every kind the coach can offer, with made-up words, for the screen tests (Max plan Phase 7).
 * [taps] records every button press, so a test can prove nothing happens without one.
 */
class CardSamples {
    data class Sample(val card: CardRow, val fields: Map<String, String>, val receipt: String? = null, val preview: CardSaver.Preview? = null)

    val taps = mutableListOf<String>()

    private fun row(id: Long, type: String, status: String = "offered") =
        CardRow(id = id, messageId = 1, type = type, payloadJson = "{}", status = status, createdAt = 0)

    val promise = Sample(row(1, "promise"), mapOf("text" to "Send the CV to Mara", "when" to "tomorrow 9am", "remind" to "true"))
    val unclearPromise = Sample(row(2, "promise"), mapOf("text" to "Call the landlord", "when" to "sometime soon", "remind" to "false"))
    val note = Sample(row(3, "note"), mapOf("kind" to "pattern", "text" to "Evenings after a long shift are when the studying slips."))
    val focus = Sample(row(4, "focus"), mapOf("name" to "Exam in June", "why" to "Most of the last two weeks has been about it."))
    val mode = Sample(row(5, "mode"), mapOf("to" to "untangle"))
    val plan = Sample(
        row(6, "plan"),
        mapOf("title" to "Pass the exam", "rationale" to "Three short sessions a week, with Sundays off."),
        preview = CardSaver.Preview.Ready(listOf("Goal: Pass the exam", "Target: study 45 minutes on Mon, Wed, Fri", "Review on Sun 1 Nov")),
    )
    val blocked = Sample(row(7, "target_change"), mapOf("target" to "Study"), preview = CardSaver.Preview.Blocked("date"))
    val saved = Sample(row(8, "note", status = "saved"), mapOf("kind" to "value", "text" to "x"), receipt = "Kept in What I know.")
    val dismissed = Sample(row(9, "focus", status = "dismissed"), mapOf("name" to "x"))
    val checkin = Sample(row(10, "checkin"), mapOf("target" to "T1", "amount" to "3"))
    val promiseStatus = Sample(row(11, "promise_status"), mapOf("promise" to "P1", "status" to "done"))

    /** Every offered card, in the order the gallery shows them. */
    val offered = listOf(promise, unclearPromise, note, focus, mode, plan, blocked)
    val all = offered + listOf(saved, dismissed, checkin, promiseStatus)

    private val byId = all.associateBy { it.card.id }

    val actions = CardActions(
        fieldsOf = { byId.getValue(it.id).fields },
        promiseTime = { _, w ->
            if (w == "tomorrow 9am") PromiseTimes.Resolution(true, LocalDate.of(2026, 10, 15), LocalTime.of(9, 0))
            else PromiseTimes.Resolution(false, null, null)
        },
        show = { _, _ -> "Thu 15 Oct, 9:00 am" },
        isAhead = { _, _ -> true },
        today = { LocalDate.of(2026, 10, 14) },
        setPromise = { id, _, _, _ -> taps += "setPromise $id" },
        keepNote = { taps += "keepNote $it" },
        startFocus = { taps += "startFocus $it" },
        switchMode = { taps += "switchMode $it" },
        notNow = { taps += "notNow $it" },
        openPromises = { taps += "openPromises" },
        savePlan = { taps += "savePlan $it" },
        openPlan = { taps += "openPlan" },
        logCheckin = { taps += "logCheckin $it" },
        updatePromiseStatus = { id, st -> taps += "updatePromiseStatus $id $st" },
        targetName = { "Tax lectures" },
        promiseText = { "Study 1 hour" },
    )

    @Composable
    fun Gallery(samples: List<Sample> = all) {
        ClarityTheme {
            Column(
                Modifier.fillMaxSize().background(clarity.ground).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                samples.forEach { SuggestionCard(it.card, it.receipt, null, it.preview, actions) }
            }
        }
    }
}
