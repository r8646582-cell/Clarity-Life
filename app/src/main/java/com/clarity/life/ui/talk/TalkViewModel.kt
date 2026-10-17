package com.clarity.life.ui.talk

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clarity.life.chat.ChatRepository
import com.clarity.life.chat.ReplyBus
import com.clarity.life.chat.ReplyService
import com.clarity.life.core.CardParser
import com.clarity.life.data.entity.CardRow
import com.clarity.life.data.entity.ConversationRow
import com.clarity.life.data.entity.MessageRow
import com.clarity.life.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/**
 * What the Talk screen shows and the four things it can do: open or start a conversation, send a message,
 * ask again for a reply that was interrupted, and answer a card. It never writes to the database itself; it
 * calls a command on [ChatRepository].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TalkViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val chat: ChatRepository,
    private val settings: SettingsRepository,
    private val bus: ReplyBus,
    private val reflections: com.clarity.life.memory.ReflectionScheduler,
    private val indexer: com.clarity.life.memory.EmbeddingIndexer,
    private val saver: com.clarity.life.chat.CardSaver,
    private val reminders: com.clarity.life.promise.Reminders,
    private val requests: com.clarity.life.chat.TalkRequests,
) : ViewModel() {

    data class State(
        val conversationId: Long = 0,
        val chatType: String = "free",
        val messages: List<MessageRow> = emptyList(),
        val cards: Map<Long, List<CardRow>> = emptyMap(),
        /** Card id to its Saved receipt, read from the row the tap wrote. */
        val receipts: Map<Long, String> = emptyMap(),
        /** Card id to a quiet line when a tap could not save ("that time has passed"). */
        val cardLines: Map<Long, String> = emptyMap(),
        /** Plan, target change and backlog cards: exactly what a tap would write now, or why it cannot. */
        val previews: Map<Long, com.clarity.life.chat.CardSaver.Preview> = emptyMap(),
        val live: ReplyBus.Live? = null,
        val conversations: List<ConversationRow> = emptyList(),
        val quietLine: String? = null,
        val hasKey: Boolean = true,
        /** The focus this conversation belongs to, if any, and the active focuses the dropdown lists. */
        val focusId: Long? = null,
        val focuses: List<com.clarity.life.data.entity.FocusRow> = emptyList(),
        val targetNames: Map<String, String> = emptyMap(),
        val promiseTexts: Map<String, String> = emptyMap(),
        /**
         * False only for the empty starting value, before the database has answered. Talk home waits for it,
         * so it never flashes the first-visit welcome to someone who has conversations.
         */
        val loaded: Boolean = false,
    )

    private val conversationId = MutableStateFlow(0L)
    private val quietLine = MutableStateFlow<String?>(null)
    private val hasKey = MutableStateFlow(true)
    private val chatType = MutableStateFlow("free")

    /** What he typed in the drawer's search box. */
    private val drawerQuery = MutableStateFlow("")

    /** Declared before init, which starts the card refreshes that use it. */
    private val cardsLock = kotlinx.coroutines.sync.Mutex()

    private val messages: StateFlow<List<MessageRow>> =
        conversationId.flatMapLatest { id ->
            if (id == 0L) flowOf(emptyList()) else chat.watchMessages(id)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private data class CardsView(
        val byMessage: Map<Long, List<CardRow>> = emptyMap(),
        val receipts: Map<Long, String> = emptyMap(),
        val lines: Map<Long, String> = emptyMap(),
        val previews: Map<Long, com.clarity.life.chat.CardSaver.Preview> = emptyMap(),
        val targetNames: Map<String, String> = emptyMap(),
        val promiseTexts: Map<String, String> = emptyMap(),
    )

    private val cardsByMessage = MutableStateFlow(CardsView())

    /** His time zone and day boundary, kept up to date from Settings; a promise card resolves its "when" with them. */
    @Volatile private var timeZone: String = java.util.TimeZone.getDefault().id

    @Volatile private var boundaryHour: Int = com.clarity.life.settings.DefaultSettings.DAY_BOUNDARY_HOUR

    /**
     * The reply being streamed, letter by letter. Only the bubble that shows it reads this, so a new piece of
     * text redraws that one bubble, not the whole of Talk.
     */
    val live: StateFlow<ReplyBus.Live?> = bus.live

    val state: StateFlow<State> = combine(
        conversationId,
        messages,
        cardsByMessage,
        // Without the text: State changes when a reply starts, stops thinking or ends, not on every piece.
        bus.live.map { it?.copy(text = "") }.distinctUntilChanged(),
        combine(
            drawerQuery.flatMapLatest { chat.watchConversations(it) },
            combine(quietLine, bus.failure) { a, b -> a ?: b },
            hasKey,
            chatType,
            combine(
                conversationId.flatMapLatest { id -> if (id == 0L) flowOf(null) else chat.conversations.watch(id) },
                chat.watchActiveFocuses(),
            ) { row, list -> (row?.focusId) to list },
        ) { list, line, key, type, focus ->
            Quad(list, line, key, type, focus.first, focus.second)
        },
    ) { id, msgs, cards, live, extra ->
        State(
            conversationId = id,
            chatType = extra.type,
            messages = msgs,
            cards = cards.byMessage,
            receipts = cards.receipts,
            cardLines = cards.lines,
            previews = cards.previews,
            live = live,
            conversations = extra.conversations,
            quietLine = extra.line,
            hasKey = extra.hasKey,
            focusId = extra.focusId,
            focuses = extra.focuses,
            targetNames = cards.targetNames,
            promiseTexts = cards.promiseTexts,
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), State())

    private data class Quad(
        val conversations: List<ConversationRow>,
        val line: String?,
        val hasKey: Boolean,
        val type: String,
        val focusId: Long?,
        val focuses: List<com.clarity.life.data.entity.FocusRow>,
    )

    /** Done once the first conversation is on show, so a doorway request cannot race the opening one. */
    private val opened = kotlinx.coroutines.CompletableDeferred<Unit>()

    /**
     * A request from another tab to start a conversation here ("Plan with coach", "Talk about this letter",
     * "Talk about it"). The Talk screen that is on show takes it, not this class by itself: after the app
     * lock has covered the app, an older copy of this class can still be alive with no screen, and if it took
     * the request the new conversation was created where nobody could see it, so Talk stayed on Free talk.
     */
    val doorway: StateFlow<com.clarity.life.chat.TalkRequests.Start?> = requests.requests

    init {
        viewModelScope.launch {
            try {
                timeZone = settings.timeZone()
                boundaryHour = settings.dayBoundaryHour()
                hasKey.value = settings.chatEndpoint() != null
                // Talk opens on its home (spec section 2.1): no conversation is open until he starts or picks
                // one, and the home is never blank. The newest conversation is one tap away there.
            } finally {
                opened.complete(Unit)
            }
        }
        viewModelScope.launch {
            // Live, so changing "when your day ends" in Settings reaches Talk at once, not after a restart.
            settings.watchDay().collect { d ->
                timeZone = d.timeZone
                boundaryHour = d.boundaryHour
            }
        }
        viewModelScope.launch {
            // Cards change when a reply ends or a card is decided, not with every second of streamed text.
            messages.map { rows -> rows.filter { it.role == "coach" }.map { it.id to it.status } }
                .distinctUntilChanged()
                .collect { refreshCards() }
        }
    }

    /** The Talk screen saw a doorway request: start that conversation, with its chat type, and show it. */
    fun takeDoorway() {
        viewModelScope.launch {
            opened.await()
            requests.take()?.let { r ->
                start(r.chatType, r.openedFrom, r.openedFromId, r.focusId)
                // A message typed for him (the goal page's "Add the ramp"): put in the box, never sent.
                if (!r.draft.isNullOrBlank()) draft.value = r.draft
            }
        }
    }

    /** Text the screen should put in the message box (never sent by itself); taken once. */
    val draft = MutableStateFlow<String?>(null)

    fun takeDraft(): String? = draft.value.also { draft.value = null }

    /** Back to Talk home: the conversation stays where it is, in the drawer. */
    fun home() {
        conversationId.value = 0L
        chatType.value = "free"
    }

    /** A way to start from Talk home: a new conversation of [type], inside [focusId] when set. */
    fun startFromHome(type: String, focusId: Long?) {
        viewModelScope.launch { start(type, focusId = focusId) }
    }

    fun open(id: Long) {
        conversationId.value = id
        viewModelScope.launch {
            chat.conversations.get(id)?.let { if (conversationId.value == id) chatType.value = it.chatType }
        }
    }

    fun newConversation(type: String, openedFrom: String? = null, openedFromId: Long? = null) {
        viewModelScope.launch { start(type, openedFrom, openedFromId) }
    }

    private suspend fun start(type: String, openedFrom: String? = null, openedFromId: Long? = null, focusId: Long? = null) {
        val id = chat.startConversation(type, focusId, System.currentTimeMillis(), openedFrom, openedFromId)
        chatType.value = type
        conversationId.value = id
        // He moved on, so the conversations he left are reflected now instead of after the idle time.
        try {
            reflections.reflectLeftBehind(exceptConversationId = id)
        } catch (e: Exception) {
            com.clarity.life.CrashLog.record(context, e)
        }
    }

    fun switchChatType(type: String) {
        val id = conversationId.value
        if (id == 0L) {
            newConversation(type)
            return
        }
        viewModelScope.launch {
            chat.switchChatType(id, type)
            chatType.value = type
        }
    }

    /** One of his focuses, from the dropdown: this conversation joins it (a new one if there is none yet). */
    fun switchToFocus(focusId: Long) {
        viewModelScope.launch {
            var id = conversationId.value
            if (id == 0L) {
                id = chat.startConversation(chatType.value, focusId, System.currentTimeMillis())
                conversationId.value = id
            } else if (!chat.switchIntoFocus(id, focusId)) {
                quietLine.value = "That focus is no longer active."
            }
        }
    }

    /** The name a mode card into a focus shows ("Switch to your focus Driving again?"). */
    fun focusName(id: String?): String? {
        val n = id?.removePrefix("F")?.toLongOrNull() ?: return null
        return state.value.focuses.firstOrNull { it.id == n }?.name
    }

    /** His own title; an empty one is ignored, so a conversation always has something to show. */
    fun rename(id: Long, title: String) = viewModelScope.launch {
        val clean = title.trim().take(MAX_TITLE_CHARS)
        if (clean.isNotEmpty()) chat.rename(id, clean)
    }

    fun searchConversations(text: String) {
        drawerQuery.value = text
    }

    /** The drawer's date heading for a conversation, on his calendar day (day boundary included). */
    fun drawerDay(startedAt: Long): String {
        val day = com.clarity.life.core.Planner.dayKey(java.time.Instant.ofEpochMilli(startedAt).toString(), timeZone, boundaryHour)
        val today = com.clarity.life.core.Planner.dayKey(java.time.Instant.now().toString(), timeZone, boundaryHour)
        return com.clarity.life.core.KnowText.drawerDay(day, today)
    }

    /**
     * Read again each time Talk comes back on screen, so the setup card goes as soon as a key is pasted and
     * the greeting uses a name as soon as it is saved.
     */
    fun refreshKey() {
        viewModelScope.launch {
            hasKey.value = settings.chatEndpoint() != null
            _userName.value = settings.userName()
            _provider.value = settings.get(
                com.clarity.life.settings.SettingsRepository.Keys.PROVIDER,
                com.clarity.life.settings.DefaultSettings.PROVIDER,
            )
        }
    }

    /**
     * His name from Settings, Coach, for the greeting on Talk home; empty until he sets one. Null until it has
     * been read, so the greeting does not show "Hello." and then change to "Hello, Sam.".
     */
    private val _userName = MutableStateFlow<String?>(null)
    val userName: StateFlow<String?> = _userName

    /** The chosen provider, so the setup card names the right service and key page. */
    private val _provider = MutableStateFlow(com.clarity.life.settings.DefaultSettings.PROVIDER)
    val provider: StateFlow<String> = _provider

    fun forget(id: Long) = viewModelScope.launch {
        chat.forgetConversation(id)
        if (conversationId.value == id) home()
    }

    /** True while a reply is being written; a second one would only be refused, so Send waits. */
    private fun replying(): Boolean =
        bus.live.value != null || messages.value.any { it.status == "streaming" }

    /** Send. His message is stored at once; the reply runs in the foreground service. False when nothing was sent. */
    fun send(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        val current = conversationId.value
        if (replying()) {
            quietLine.value = "Wait for this reply to finish, then send."
            return false
        }
        quietLine.value = null
        bus.fail(null)
        guarded {
            val endpoint = chat.endpointForChat(chatType.value)
            if (endpoint == null) {
                hasKey.value = false
                quietLine.value = "Add your API key in Settings, Coach, and this will send."
                return@guarded
            }
            // Typed on Talk home: it starts a free-talk conversation (spec section 2.1).
            val id = if (current != 0L) current else {
                start("free")
                conversationId.value
            }
            val now = System.currentTimeMillis()
            chat.addHisMessage(id, trimmed, now)
            // His message gets its meaning vector in the background, on the phone.
            indexer.schedule()
            if (chat.conversations.get(id)?.title.isNullOrBlank()) {
                // A placeholder; the reflection replaces it with a real title (MemoryRepository).
                chat.rename(id, trimmed.take(com.clarity.life.memory.MemoryRepository.PLACEHOLDER_TITLE_CHARS))
            }
            val coachId = chat.openCoachReply(id, now, endpoint.model)
            startReply(id, coachId)
        }
        return true
    }

    /** A service Android refuses to start (he left the app that instant) must not leave a reply streaming. */
    private suspend fun startReply(conversationId: Long, coachId: Long) {
        try {
            ReplyService.start(context, conversationId, coachId)
        } catch (e: Exception) {
            chat.failCoachReply(coachId, com.clarity.life.ai.AiFailure("service_start", "the reply could not start", retryable = true), System.currentTimeMillis())
            throw e
        }
    }

    /** Runs a tap's work; an error becomes a quiet line and a crash-log entry, never a closed app. */
    private fun guarded(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                com.clarity.life.CrashLog.record(context, e)
                quietLine.value = "That did not send (${e.javaClass.simpleName}). Details are in Settings, at the bottom."
            }
        }
    }

    /** Try again, on a reply the process died in the middle of, or one that failed. */
    fun tryAgain(messageId: Long) {
        val id = conversationId.value
        if (replying()) {
            quietLine.value = "Wait for this reply to finish, then try again."
            return
        }
        bus.fail(null)
        guarded {
            // The reply answers the message just before it, not his newest one.
            val rows = chat.messages.ofConversation(id)
            if (rows.none { it.role == "user" && it.id < messageId }) return@guarded
            chat.reopenReply(messageId)
            startReply(id, messageId)
        }
    }

    /** Not now. A real tap, so the card really is dismissed and the coach is told. */
    fun dismissCard(cardId: Long) = tap(cardId) {
        saver.dismiss(cardId, System.currentTimeMillis())
        null
    }

    /**
     * Set it, on a promise card. [date] and [time] are what the card showed him, or what he chose in the
     * time picker; nothing else can reach the promise table.
     */
    fun setPromise(cardId: Long, date: java.time.LocalDate, time: java.time.LocalTime) = tap(cardId) {
        val at = com.clarity.life.core.PromiseTimes.toInstant(date, time, timeZone)
        val result = saver.savePromise(cardId, at, System.currentTimeMillis())
        // The reminder is set from the saved row, so it rings only for what really was saved.
        if (result is com.clarity.life.chat.CardSaver.Result.Saved) {
            reminders.sync(result.ref.substringAfter(':').toLong())
        }
        result
    }

    /** Android 13 and later ask before an app may show a notification; a reminder needs that. */
    fun notificationsAllowed(): Boolean = reminders.notificationsAllowed()

    /** Keep it, on a note card. */
    fun keepNote(cardId: Long) = tap(cardId) { saver.saveNote(cardId, System.currentTimeMillis()) }

    /** Start it, on a focus card. */
    fun startFocus(cardId: Long) = tap(cardId) { saver.saveFocus(cardId, System.currentTimeMillis()) }

    /** Save plan, Change it or Save, on a plan, target change or backlog card: the steps the card showed. */
    fun savePlanCard(cardId: Long) = tap(cardId) { saver.savePlanCard(cardId, System.currentTimeMillis()) }

    /** Switch, on a mode card: the next reply follows the new chat type. */
    fun switchFromCard(cardId: Long) = tap(cardId) {
        val result = saver.switchMode(cardId, System.currentTimeMillis())
        // Into a focus the chat type stays; the dropdown shows the focus from the conversation row.
        if (result is com.clarity.life.chat.CardSaver.Result.Saved) {
            if (!result.ref.startsWith("mode:focus:")) {
                val to = result.ref.substringAfter(':')
                chatType.value = to
                val label = CHAT_TYPES.firstOrNull { it.first == to }?.second ?: to.replaceFirstChar { it.uppercase() }
                send("Let's switch to $label.")
            } else {
                val focusName = result.ref.substringAfter("mode:focus:")
                send("Let's focus on $focusName.")
            }
        }
        result
    }

    /** Log it, on a checkin card ("Log: Tax lectures, 3 today"). */
    fun logCheckin(cardId: Long) = tap(cardId) { saver.saveCheckin(cardId, System.currentTimeMillis()) }

    /** Done or Not done, on a promise_status card. */
    fun updatePromiseStatus(cardId: Long, status: String) = tap(cardId) {
        val result = saver.savePromiseStatus(cardId, status, System.currentTimeMillis())
        if (result is com.clarity.life.chat.CardSaver.Result.Saved) {
            val pid = result.ref.substringAfter(':').toLongOrNull()
            if (pid != null) reminders.sync(pid)
        }
        result
    }

    /**
     * What a promise card's "when" means, resolved in code against the moment the coach offered it (so
     * "tomorrow" keeps meaning the day after that reply, however late he taps).
     */
    fun promiseTime(row: CardRow, phrase: String): com.clarity.life.core.PromiseTimes.Resolution =
        com.clarity.life.core.PromiseTimes.resolve(
            phrase,
            java.time.Instant.ofEpochMilli(row.createdAt),
            timeZone,
            boundaryHour,
        )

    /** The way the card shows a moment before he taps: "Sat 10 Oct, 3:00 pm". */
    fun cardTime(date: java.time.LocalDate, time: java.time.LocalTime): String =
        com.clarity.life.core.TimeFacts.cardTime(
            com.clarity.life.core.PromiseTimes.toInstant(date, time, timeZone),
            timeZone,
        )

    /** His calendar day now, the first day the time picker offers. */
    fun today(): java.time.LocalDate = java.time.LocalDate.now(java.time.ZoneId.of(timeZone))

    /** True when the moment is still ahead, so the picker never offers to save a promise in the past. */
    fun isAhead(date: java.time.LocalDate, time: java.time.LocalTime): Boolean =
        com.clarity.life.core.PromiseTimes.toInstant(date, time, timeZone).toEpochMilli() > System.currentTimeMillis()

    /** Runs a card tap; a refusal becomes a quiet line on that card, an error a crash-log entry. */
    private fun tap(cardId: Long, block: suspend () -> com.clarity.life.chat.CardSaver.Result?) {
        viewModelScope.launch {
            val line = try {
                when (val r = block()) {
                    is com.clarity.life.chat.CardSaver.Result.NotSaved -> notSavedLine(r.why)
                    else -> null
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                com.clarity.life.CrashLog.record(context, e)
                "That did not save (${e.javaClass.simpleName}). Details are in Settings, at the bottom."
            }
            refreshCards(cardId to line)
        }
    }

    private fun notSavedLine(why: String): String = when (why) {
        "past" -> "That time has passed. Pick a new one."
        "invalid", "unsupported" -> "This suggestion cannot be saved, so nothing was changed."
        else -> blockedLine(why)
    }

    companion object {
        /** A title he types: long enough for a sentence, short enough for one line in the drawer. */
        const val MAX_TITLE_CHARS = 80

        /** Why a plan, target or backlog card cannot be saved, in plain words. Nothing was changed. */
        fun blockedLine(why: String): String = when (why) {
            "date" -> "A date on this card could not be read exactly, so nothing was saved. Ask the coach to write it as a date, like 20 Nov."
            "review passed", "review not after today", "review too far ahead" ->
                "The review date on this card has passed. Ask the coach for the plan again."
            "end before start" -> "A target on this card ends before it starts, so nothing was saved."
            "goal gone", "target gone", "backlog gone", "invalid" ->
                "Something this card names has been archived or changed since, so nothing was saved."
            "min above target" -> "The minimum would be above the target, so nothing was saved."
            "changes nothing" -> "This would change nothing; it is already that way."
            "total below done" -> "The new total is below what is already done, so nothing was saved."
            "refused", "target ended" -> "That could not be saved as it is, so nothing was changed."
            else -> "Nothing was saved."
        }
    }

    /** One at a time, so a tap's quiet line is never overwritten by a refresh that started before it. */
    private suspend fun refreshCards(line: Pair<Long, String?>? = null) = cardsLock.withLock {
        val rows = messages.value.filter { it.role == "coach" }
        val map = HashMap<Long, List<CardRow>>()
        val receipts = HashMap<Long, String>()
        val previews = HashMap<Long, com.clarity.life.chat.CardSaver.Preview>()
        val targetNames = HashMap<String, String>()
        val promiseTexts = HashMap<String, String>()
        val now = System.currentTimeMillis()
        val all = rows.firstOrNull()?.let { chat.cards.ofConversation(it.conversationId) }.orEmpty().groupBy { it.messageId }
        for (row in rows) {
            val cards = all[row.id].orEmpty()
            map[row.id] = cards
            for (card in cards) {
                saver.receipt(card)?.let { receipts[card.id] = it }
                if (card.status == "offered") {
                    if (card.type in com.clarity.life.chat.CardSaver.PLAN_TYPES) {
                        previews[card.id] = saver.planPreview(card, now)
                    } else if (card.type == "checkin") {
                        val tid = cardFields(card)["target"]
                        val tidNum = tid?.removePrefix("T")?.toLongOrNull()
                        if (tid != null && tidNum != null) {
                            saver.targetName(tidNum)?.let { targetNames[tid] = it }
                        }
                    } else if (card.type == "promise_status") {
                        val pid = cardFields(card)["promise"]
                        val pidNum = pid?.removePrefix("P")?.toLongOrNull()
                        if (pid != null && pidNum != null) {
                            saver.promiseText(pidNum)?.let { promiseTexts[pid] = it }
                        }
                    }
                }
            }
        }
        val lines = HashMap(cardsByMessage.value.lines)
        if (line != null) {
            if (line.second == null) lines.remove(line.first) else lines[line.first] = line.second!!
        }
        cardsByMessage.value = CardsView(map, receipts, lines, previews, targetNames, promiseTexts)
    }

    fun cardFields(row: CardRow): Map<String, String> = try {
        val parsed = com.clarity.life.core.StrictJson.parse(row.payloadJson)
        parsed.asObj?.map?.mapValues { (_, v) ->
            v.asStr ?: (v as? com.clarity.life.core.Jv.Num)?.let {
                if (it.isInteger || kotlin.math.abs(it.value - it.value.toLong()) < 0.0001) it.value.toLong().toString() else it.value.toString()
            } ?: v.asNum?.toString() ?: v.asBool?.toString() ?: ""
        } ?: emptyMap()
    } catch (e: Exception) {
        emptyMap()
    }

    val cardTypes: List<String> get() = CardParser.CARD_TYPES
}
