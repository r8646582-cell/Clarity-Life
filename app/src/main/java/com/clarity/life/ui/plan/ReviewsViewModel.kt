package com.clarity.life.ui.plan

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clarity.life.chat.TalkRequests
import com.clarity.life.core.LetterPeriods
import com.clarity.life.core.ReviewFacts
import com.clarity.life.core.StrictJson
import com.clarity.life.core.TimeFacts
import com.clarity.life.data.entity.LetterRow
import com.clarity.life.data.entity.ReviewRow
import com.clarity.life.writing.LetterWriter
import com.clarity.life.writing.ReviewWriter
import com.clarity.life.writing.WritingScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Plan, Reviews (docs/mockups/Reviews.dc.html, Review.dc.html, Letter.dc.html) and the letter card on Talk
 * (TalkLetter.dc.html). Reads only; opening marks a letter or review read, and Delete removes a letter's text for
 * good (its key stays, so it is never written again). Talk about it opens a new conversation that has the letter
 * or the review in front of the coach.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ReviewsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val reviews: ReviewWriter,
    private val letters: LetterWriter,
    private val scheduler: WritingScheduler,
    private val requests: TalkRequests,
) : ViewModel() {

    /** What sits under a review's numbers. */
    enum class Note { WRITTEN, TOO_LITTLE, COMING, DELAYED }

    data class Week(val monday: LocalDate, val review: ReviewRow?, val letter: LetterRow?)

    /** A month: its review (the numbers) and its letter, written from the month's weekly letters. */
    data class Month(val first: LocalDate, val review: ReviewRow?, val letter: LetterRow?)

    data class Revisit(val ref: String?, val question: String)

    data class State(
        val weeks: List<Week> = emptyList(),
        val months: List<Month> = emptyList(),
        /** Yearly letters, newest first. */
        val years: List<LetterRow> = emptyList(),
        /** Every letter he has (weekly, monthly and yearly), to open one by id. */
        val letters: List<LetterRow> = emptyList(),
        val notes: Map<Long, Note> = emptyMap(),
        /** The Monday of the week whose letter gave up after its checks, if it is the latest week. */
        val delayedLetter: LocalDate? = null,
        /** The key of the monthly or yearly letter that gave up after its checks, if it is the latest one. */
        val delayedPeriodLetters: List<String> = emptyList(),
        /** The newest letter he has not opened: the card on Talk and the yellow dot. */
        val newLetter: LetterRow? = null,
        val unread: Boolean = false,
    )

    private val minute = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(60_000)
        }
    }

    val state: StateFlow<State> = combine(reviews.watchAll(), letters.watchAll(), minute) { r, l, now -> Triple(r, l, now) }
        .mapLatest { (all, allLetters, now) ->
            val kinds = allLetters.groupBy { LetterPeriods.kindOf(it.key) }
            val byMonday = kinds[LetterPeriods.WEEK].orEmpty().associateBy { LocalDate.parse(it.periodStart) }
            val byMonth = kinds[LetterPeriods.MONTH].orEmpty().associateBy { LocalDate.parse(it.periodStart) }
            val monthReviews = all.filter { it.kind == "month" }.associateBy { LocalDate.parse(it.periodStart) }
            val weekReviews = all.filter { it.kind == "week" }.associateBy { LocalDate.parse(it.periodStart) }
            val mondays = (byMonday.keys + weekReviews.keys).sortedDescending()
            val notes = all.associate { it.id to noteState(it) }
            val latest = letters.dueWeek(now)
            State(
                weeks = mondays.map { Week(it, weekReviews[it], byMonday[it]) },
                months = (byMonth.keys + monthReviews.keys).sortedDescending().map { Month(it, monthReviews[it], byMonth[it]) },
                years = kinds[LetterPeriods.YEAR].orEmpty().sortedByDescending { it.periodStart },
                letters = allLetters,
                notes = notes,
                delayedLetter = latest?.takeIf { letters.delayed(it) },
                delayedPeriodLetters = listOfNotNull(
                    letters.dueMonth(now)?.let { LetterPeriods.monthKey(it) },
                    letters.dueYear(now)?.let { LetterPeriods.yearKey(it) },
                ).filter { letters.delayedKey(it) },
                // The newest written, so a monthly letter is not hidden behind the week that ends the same day.
                newLetter = allLetters.filter { it.readAt == null }.maxByOrNull { it.readyAt },
                unread = allLetters.any { it.readAt == null } || all.any { it.readAt == null },
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), State())

    private suspend fun noteState(r: ReviewRow): Note = when {
        r.narrative.isNotBlank() -> Note.WRITTEN
        (ReviewFacts.fromJson(r.factsJson)?.answered ?: 0) == 0 -> Note.TOO_LITTLE
        reviews.jobStatus(r) == "failed" -> Note.DELAYED
        else -> Note.COMING
    }

    // ---------- labels ----------

    fun range(first: String, last: String): String {
        val a = LocalDate.parse(first)
        val b = LocalDate.parse(last)
        val left = TimeFacts.shortDay(a).substringAfter(' ')
        val right = TimeFacts.shortDay(b).substringAfter(' ')
        return if (a.month == b.month) "${a.dayOfMonth} to $right" else "$left to $right"
    }

    fun weekRange(monday: LocalDate): String = range(monday.toString(), monday.plusDays(6).toString())

    fun kind(l: LetterRow): String = LetterPeriods.kindOf(l.key)

    /** What a letter is about, as its page and card show it: "5 to 11 Oct", "October 2026" or "2026". */
    fun letterPeriod(l: LetterRow): String = when (kind(l)) {
        LetterPeriods.MONTH -> LetterPeriods.monthName(LocalDate.parse(l.periodStart))
        LetterPeriods.YEAR -> l.periodStart.take(4)
        else -> range(l.periodStart, l.periodEnd)
    }

    /** "weekly", "monthly" or "yearly". */
    fun letterAdjective(l: LetterRow): String = LetterPeriods.adjective(l.key)

    fun monthName(first: String): String = ReviewFacts.title("month", LocalDate.parse(first), LocalDate.parse(first)).substringBefore(',')

    fun facts(r: ReviewRow): List<String> = ReviewFacts.fromJson(r.factsJson)?.lines?.drop(1).orEmpty()

    /** A monthly review is stored as its headline, a blank line, then the narrative. */
    fun headline(r: ReviewRow): String? = if (r.kind == "month") r.narrative.substringBefore("\n\n").takeIf { it.isNotBlank() } else null

    fun narrative(r: ReviewRow): String = if (r.kind == "month") r.narrative.substringAfter("\n\n", "") else r.narrative

    fun revisits(r: ReviewRow): List<Revisit> = try {
        StrictJson.parse(r.revisitJson).asList.orEmpty().mapNotNull { item ->
            val o = item.asObj?.map ?: return@mapNotNull null
            Revisit(o["ref"]?.asStr, o["question"]?.asStr ?: return@mapNotNull null)
        }
    } catch (e: Exception) {
        emptyList()
    }

    // ---------- taps ----------

    /** Reading a week's (or a month's) letter is reading that period: its review stops counting as new too (no lasting dot). */
    fun openLetter(id: Long) = act {
        val now = System.currentTimeMillis()
        letters.markRead(id, now)
        state.value.weeks.firstOrNull { it.letter?.id == id }?.review?.let { reviews.markRead(it.id, now) }
        state.value.months.firstOrNull { it.letter?.id == id }?.review?.let { reviews.markRead(it.id, now) }
    }

    fun openReview(id: Long) = act { reviews.markRead(id, System.currentTimeMillis()) }

    fun deleteLetter(id: Long) = act { letters.delete(id, System.currentTimeMillis()) }

    fun talkAboutLetter(id: Long) = requests.startConversation("free", "letter", id)

    /** Talk about it, or a revisit question: a Plan and track conversation with the review in front of the coach. */
    fun talkAboutReview(id: Long) = requests.startConversation("plan", "review", id)

    fun retryLetter(monday: LocalDate) = scheduler.retryLetter(monday)

    fun retryPeriodLetter(key: String) = scheduler.retryPeriodLetter(key)

    fun retryReview(id: Long) = scheduler.retryReview(id)

    /** Requests from other tabs for Plan to show something (the letter card's Read). */
    val planRequests get() = requests.planRequests

    fun takePlanRequest(): TalkRequests.Show? = requests.takePlan()

    /** The letter card's Read button: Plan opens on Reviews, with the letter open. */
    fun showLetter(id: Long) = requests.showInPlan(TalkRequests.Show("Reviews", letterId = id))

    /** A button on Talk that leads to one part of Plan opens that part, not whichever was shown last. */
    fun showSection(section: String) = requests.showInPlan(TalkRequests.Show(section))

    private fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                com.clarity.life.CrashLog.record(context, e)
            }
        }
    }
}
