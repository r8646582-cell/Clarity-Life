package com.clarity.life.ui.plan

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clarity.life.core.PromiseText
import com.clarity.life.core.PromiseTimes
import com.clarity.life.core.TimeFacts
import com.clarity.life.data.entity.PromiseRow
import com.clarity.life.promise.PromiseRepository
import com.clarity.life.settings.DefaultSettings
import com.clarity.life.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Promises as the due card on Talk and Plan, Promises show them. Every button is a command on
 * [PromiseRepository]; this class only reads, and works out the labels in code ([PromiseText]).
 */
@HiltViewModel
class PromisesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val promises: PromiseRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    data class Item(val row: PromiseRow, val kicker: String, val time: String)

    data class State(
        /** Due on his day or earlier, not answered yet: the due card and "Needs an answer". */
        val due: List<Item> = emptyList(),
        /** Open and later than today: "Coming up". */
        val coming: List<Item> = emptyList(),
        /** Answered, newest first: "Past". */
        val past: List<Item> = emptyList(),
        val quietLine: String? = null,
    )

    private val zone = MutableStateFlow(java.util.TimeZone.getDefault().id to DefaultSettings.DAY_BOUNDARY_HOUR)
    private val quietLine = MutableStateFlow<String?>(null)

    /** Labels such as "in 2 hours" age, so the lists are worked out again every minute. */
    private val minute = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(60_000)
        }
    }

    val state: StateFlow<State> = combine(
        promises.watchOpen(),
        promises.watchPast(),
        zone,
        minute,
        quietLine,
    ) { open, past, (tz, boundary), now, line ->
        val at = Instant.ofEpochMilli(now)
        val dueIds = PromiseText.dueNow(open.map { PromiseRepository.model(it) }, at, tz, boundary).map { it.id }.toSet()
        fun item(p: PromiseRow) = Item(
            row = p,
            kicker = PromiseText.dueKicker(Instant.ofEpochMilli(p.dueAt), at, tz, boundary),
            time = TimeFacts.cardTime(Instant.ofEpochMilli(p.dueAt), tz),
        )
        State(
            due = open.filter { it.id in dueIds }.map(::item),
            coming = open.filter { it.id !in dueIds }.map(::item),
            past = past.map(::item),
            quietLine = line,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), State())

    init {
        viewModelScope.launch { zone.value = settings.timeZone() to settings.dayBoundaryHour() }
    }

    val reasons: List<String> get() = PromiseText.REASONS

    fun done(id: Long) = act { promises.done(id, System.currentTimeMillis()) }

    fun notDone(id: Long, reason: String?) = act { promises.notDone(id, reason, System.currentTimeMillis()) }

    fun letGo(id: Long) = act { promises.letGo(id, System.currentTimeMillis()) }

    fun undo(id: Long) = act { promises.undo(id, System.currentTimeMillis()) }

    fun move(id: Long, date: LocalDate, time: LocalTime) = act {
        val moved = promises.move(id, PromiseTimes.toInstant(date, time, zone.value.first), System.currentTimeMillis())
        if (moved == null) quietLine.value = "That time has passed. Pick a later one."
    }

    /** True for an answer he gave in the last day, which can still be undone (a move cannot). */
    fun canUndo(row: PromiseRow): Boolean =
        row.status != "moved" && (row.resolvedAt ?: 0) > System.currentTimeMillis() - UNDO_WINDOW_MS

    fun today(): LocalDate = LocalDate.now(ZoneId.of(zone.value.first))

    fun show(date: LocalDate, time: LocalTime): String =
        TimeFacts.cardTime(PromiseTimes.toInstant(date, time, zone.value.first), zone.value.first)

    fun isAhead(date: LocalDate, time: LocalTime): Boolean =
        PromiseTimes.toInstant(date, time, zone.value.first).toEpochMilli() > System.currentTimeMillis()

    fun localDue(row: PromiseRow): Pair<LocalDate, LocalTime> {
        val t = TimeFacts.local(Instant.ofEpochMilli(row.dueAt), zone.value.first)
        return t.toLocalDate() to t.toLocalTime().withSecond(0).withNano(0)
    }

    private fun act(block: suspend () -> Unit) {
        quietLine.value = null
        viewModelScope.launch {
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                com.clarity.life.CrashLog.record(context, e)
                quietLine.value = "That did not save (${e.javaClass.simpleName}). Details are in Settings, at the bottom."
            }
        }
    }

    companion object {
        private const val UNDO_WINDOW_MS = 24 * 60 * 60 * 1000L
    }
}
