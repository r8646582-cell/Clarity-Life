package com.clarity.life.ui.know

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clarity.life.chat.TalkRequests
import com.clarity.life.core.Chapters
import com.clarity.life.core.StrictJson
import com.clarity.life.core.Story
import com.clarity.life.data.ClarityDatabase
import com.clarity.life.data.entity.ChapterRow
import com.clarity.life.data.entity.NoteRow
import com.clarity.life.data.entity.QuoteRow
import com.clarity.life.data.entity.StoryRow
import com.clarity.life.story.StoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Your story (spec 2.5, the owner's approved mockup of 13 Oct 2026). Every number comes from
 * [StoryRepository] and [Story]; the only AI text is the monthly story's short version and overcame lines,
 * which were checked before they were saved. The page never writes anything: That's right, Not quite and
 * Delete happen in the proof sheet, through What I know's commands, and Talk opens a new conversation.
 */
@HiltViewModel
class StoryViewModel @Inject constructor(
    private val db: ClarityDatabase,
    private val stories: StoryRepository,
    private val requests: TalkRequests,
) : ViewModel() {

    /** One thing he overcame, as the page shows it: Then (his words, when kept), what he did, and Now. */
    data class Overcame(val title: String, val then: QuoteRow?, val did: String, val now: String)

    data class Chart(val data: StoryRepository.GoalChart, val bars: List<Story.Bar>, val deadline: LocalDate?)

    data class State(
        val loading: Boolean = true,
        /** Null when there is nothing yet: no conversation, check-in or plan. */
        val first: LocalDate? = null,
        val today: LocalDate? = null,
        val view: Story.View = Story.View.ALL,
        val counts: StoryRepository.Counts? = null,
        val charts: List<Chart> = emptyList(),
        val chart: Int = 0,
        val story: StoryRow? = null,
        val thenQuote: QuoteRow? = null,
        val nowQuote: QuoteRow? = null,
        val overcame: List<Overcame> = emptyList(),
        val doneFocuses: List<StoryRepository.DoneFocus> = emptyList(),
        val helps: List<NoteRow> = emptyList(),
        val strengths: List<NoteRow> = emptyList(),
        val chapters: List<ChapterRow> = emptyList(),
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> get() = _state

    private var facts: StoryRepository.Facts? = null
    private val deadlines = mutableMapOf<Long, LocalDate>()

    init {
        reload()
        viewModelScope.launch {
            combine(
                db.story().watchLatestStory(),
                db.story().watchHelpsAndStrengths(),
                db.memory().watchChapters(),
            ) { story, notes, chapters -> Triple(story, notes, chapters) }.collect { (story, notes, chapters) ->
                val (then, now) = quotes(story)
                val overcame = overcame(story)
                _state.update {
                    it.copy(
                        story = story,
                        thenQuote = then,
                        nowQuote = now,
                        overcame = overcame,
                        helps = notes.filter { n -> n.kind == "what_helps" }.sortedWith(NOTE_ORDER),
                        strengths = notes.filter { n -> n.kind == "strength" }.sortedWith(NOTE_ORDER),
                        chapters = chapters,
                    )
                }
            }
        }
    }

    /** Reads the counted facts again (on open, and when the page comes back into view). */
    fun reload() = viewModelScope.launch {
        val f = stories.facts(System.currentTimeMillis())
        facts = f
        deadlines.clear()
        db.story().storyBacklogs().forEach { deadlines[it.id] = LocalDate.parse(it.deadline) }
        if (f == null) {
            _state.update { it.copy(loading = false, first = null) }
            return@launch
        }
        apply(f, _state.value.view)
    }

    fun setView(view: Story.View) {
        val f = facts ?: return
        _state.update { it.copy(view = view) }
        viewModelScope.launch { apply(f, view) }
    }

    fun showChart(i: Int) = _state.update { it.copy(chart = i) }

    /** Talk about how far you've come: a new free-talk conversation in Talk. */
    fun talk() = requests.startConversation("free")

    private suspend fun apply(f: StoryRepository.Facts, view: Story.View) {
        val (from, to) = Story.viewRange(view, f.first, f.today)
        val counts = stories.counts(f, from, to)
        // A chart shows the stretches of the view; a goal with nothing in the view is left out of it.
        val charts = f.charts.mapNotNull { c ->
            val bars = c.series.bars.filter { !it.end.isBefore(from) }
            if (bars.isEmpty()) null else Chart(c, bars, deadlines[c.backlogId])
        }
        _state.update {
            it.copy(
                loading = false,
                first = f.first,
                today = f.today,
                view = view,
                counts = counts,
                charts = charts,
                chart = it.chart.coerceIn(0, (charts.size - 1).coerceAtLeast(0)),
                doneFocuses = f.doneFocuses.filter { d -> !d.day.isBefore(from) },
            )
        }
    }

    /** The then and now quotes, only when both are still there (a forgotten conversation takes its words). */
    private suspend fun quotes(story: StoryRow?): Pair<QuoteRow?, QuoteRow?> {
        val ids = listOfNotNull(story?.thenQuoteId, story?.nowQuoteId)
        if (ids.size < 2) return null to null
        val found = db.story().quotes(ids).associateBy { it.id }
        val then = found[story!!.thenQuoteId]
        val now = found[story.nowQuoteId]
        return if (then != null && now != null) then to now else null to null
    }

    private suspend fun overcame(story: StoryRow?): List<Overcame> {
        if (story == null) return emptyList()
        val items = runCatching { StrictJson.parse(story.overcameJson) }.getOrNull()?.asList ?: return emptyList()
        val parsed = items.mapNotNull { it.asObj?.map }
        val ids = parsed.mapNotNull { m -> m["then_quote_id"]?.asNum?.toLong() }
        val found = if (ids.isEmpty()) emptyMap() else db.story().quotes(ids).associateBy { it.id }
        return parsed.mapNotNull { m ->
            val title = m["title"]?.asStr ?: return@mapNotNull null
            Overcame(
                title = title,
                then = m["then_quote_id"]?.asNum?.toLong()?.let { found[it] },
                did = m["did"]?.asStr.orEmpty(),
                now = m["now"]?.asStr.orEmpty(),
            )
        }
    }

    companion object {
        /** Confirmed first, then the most often seen, then the most recent: the first rows are the surest. */
        private val NOTE_ORDER = compareByDescending<NoteRow> { it.confidence == "confirmed" }
            .thenByDescending { it.timesSeen }
            .thenByDescending { it.lastSeen }

        private val MONTHS = listOf(
            "January", "February", "March", "April", "May", "June", "July", "August", "September", "October",
            "November", "December",
        )
        private val SHORT_MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        private val DAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

        fun day(d: LocalDate): String = "${DAYS[d.dayOfWeek.value - 1]} ${d.dayOfMonth} ${SHORT_MONTHS[d.monthValue - 1]}"

        fun shortDay(d: LocalDate): String = "${d.dayOfMonth} ${SHORT_MONTHS[d.monthValue - 1]}"

        fun monthName(d: LocalDate): String = MONTHS[d.monthValue - 1]

        fun monthShort(d: LocalDate): String = SHORT_MONTHS[d.monthValue - 1]

        /** When the next monthly story arrives: the first of next month. */
        fun nextStory(today: LocalDate): String = "the start of " + monthName(today.withDayOfMonth(1).plusMonths(1))

        /** When the chapter being written arrives: when its quarter ends. */
        fun nextChapter(today: LocalDate): String {
            val q = Chapters.quarterOf(today)
            return "${SHORT_MONTHS[q.start.monthValue - 1]} to ${SHORT_MONTHS[q.end.monthValue - 1]} · arrives in " +
                monthName(q.end.plusDays(1))
        }

        /** Days from [finish] to [deadline], counted by code for "30 days before the deadline". */
        fun daysBefore(finish: LocalDate, deadline: LocalDate): Long = ChronoUnit.DAYS.between(finish, deadline)
    }
}
