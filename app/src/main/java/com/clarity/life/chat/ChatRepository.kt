package com.clarity.life.chat

import com.clarity.life.ai.AiClient
import com.clarity.life.ai.AiFailure
import com.clarity.life.ai.ChatChunk
import com.clarity.life.ai.ChatRequest
import com.clarity.life.ai.Endpoint
import com.clarity.life.ai.AnthropicClient
import com.clarity.life.ai.OpenAiCompatibleClient
import com.clarity.life.core.CardParser
import com.clarity.life.core.ContextBuilder
import com.clarity.life.core.KnownIds
import com.clarity.life.core.LastChat
import com.clarity.life.core.LiveText
import com.clarity.life.core.MemoryBlocks
import com.clarity.life.core.PlanBlocks
import com.clarity.life.core.Planner
import com.clarity.life.core.TimeFacts
import androidx.room.withTransaction
import com.clarity.life.data.ClarityDatabase
import com.clarity.life.data.StateMachines
import com.clarity.life.data.Values
import com.clarity.life.data.entity.CardRow
import com.clarity.life.data.entity.ConversationRow
import com.clarity.life.data.entity.ErrorLogRow
import com.clarity.life.data.entity.MessageRow
import com.clarity.life.prompts.PromptStore
import com.clarity.life.settings.SettingsRepository
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * The one writer for a conversation (spec section 18, point 2). Every change to the database happens in one
 * of these named commands, each a single transaction; screens never write, and nothing the model produces
 * reaches the database except through [saveCard], which only ever runs because the owner tapped a button.
 */
@Singleton
class ChatRepository @Inject constructor(
    private val db: ClarityDatabase,
    private val settings: SettingsRepository,
    private val prompts: PromptStore,
    private val mainClient: OpenAiCompatibleClient,
    private val anthropicClient: AnthropicClient,
    private val search: com.clarity.life.memory.SearchIndex,
    private val planner: com.clarity.life.planner.PlannerRepository,
) {
    private val json = Json

    val conversations get() = db.conversations()
    val messages get() = db.messages()
    val cards get() = db.cards()

    fun watchRecent(limit: Int = 50) = conversations.watchRecent(limit)

    /** The drawer: every conversation (up to [limit]), or those whose title or summary has [query] in it. */
    fun watchConversations(query: String, limit: Int = DRAWER_LIMIT): Flow<List<ConversationRow>> {
        val escaped = query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return conversations.watchSearch(escaped, limit)
    }

    fun watchMessages(conversationId: Long): Flow<List<MessageRow>> = messages.watchConversation(conversationId)

    fun watchCards(messageId: Long): Flow<List<CardRow>> = cards.watchForMessage(messageId)

    // ---------- commands ----------

    /** StartConversation. Off the record means nothing is ever stored; that arrives with stage B. */
    suspend fun startConversation(
        chatType: String,
        focusId: Long?,
        now: Long,
        openedFrom: String? = null,
        openedFromId: Long? = null,
    ): Long {
        Values.requireValid("conversation.chat_type", chatType)
        Values.requireValid("conversation.opened_from", openedFrom)
        return conversations.insert(
            ConversationRow(
                chatType = chatType,
                focusId = focusId,
                startedAt = now,
                openedFrom = openedFrom,
                openedFromId = openedFromId,
            ),
        )
    }

    /**
     * SwitchChatType: the history stays, and the next reply follows the new type (spec section 2.1). Picking
     * one of the plain types also takes the conversation out of a focus it was in.
     */
    suspend fun switchChatType(conversationId: Long, chatType: String) {
        Values.requireValid("conversation.chat_type", chatType)
        val row = conversations.get(conversationId) ?: return
        conversations.update(row.copy(chatType = chatType, focusId = null))
        // The chat-type file and the allowed card list are part of the prefix, so it must be rebuilt.
        conversations.setPrefix(conversationId, null, null)
    }

    /**
     * SwitchIntoFocus: the conversation now belongs to the focus. Its chat type (and so the cards allowed)
     * stays what was selected (spec section 5, "Inside a Focus: the same as the type currently selected");
     * the focus file and the FOCUS block come with the rebuilt prefix. Returns false for a focus that is gone
     * or done.
     */
    suspend fun switchIntoFocus(conversationId: Long, focusId: Long): Boolean = db.withTransaction {
        val focus = db.memory().focus(focusId)
        val row = conversations.get(conversationId)
        if (focus == null || focus.status == "done" || row == null) return@withTransaction false
        conversations.update(row.copy(focusId = focusId))
        conversations.setPrefix(conversationId, null, null)
        true
    }

    suspend fun rename(conversationId: Long, title: String) = conversations.rename(conversationId, title)

    /**
     * ForgetConversation, one transaction: the conversation goes, and with it (by cascade) its messages,
     * cards, quotes, events, reflection items and the evidence taken from it; then every note learned only
     * from it (db/schema.sql). A note he edited or confirmed is his own and stays.
     */
    suspend fun forgetConversation(conversationId: Long) = db.withTransaction {
        val noteIds = db.memory().notesWithEvidenceFrom(conversationId)
        search.forget(conversationId)
        conversations.delete(conversationId)
        if (noteIds.isNotEmpty()) db.memory().deleteNotesLeftWithoutEvidence(noteIds)
        for (id in noteIds) if (db.memory().note(id) == null) search.removeNote(id)
        // Its summary and the notes it alone taught are frozen in other open conversations' prefixes: they
        // build them again, so nothing from it is sent after he forgot it.
        conversations.clearAllPrefixes()
        Unit
    }

    /** AddHisMessage: stored complete, because he wrote it; it is the one kind of evidence there is. */
    suspend fun addHisMessage(conversationId: Long, text: String, now: Long): Long = db.withTransaction {
        val id = messages.insert(
            MessageRow(
                conversationId = conversationId,
                role = "user",
                content = text,
                createdAt = now,
                status = "complete",
            ),
        )
        // Searchable at once, so the very next conversation can find it (RELEVANT_PAST), even before the
        // reflection has run. An off-the-record conversation is never indexed.
        if (conversations.get(conversationId)?.offTheRecord != 1) search.indexHisMessage(id, text)
        id
    }

    /** OpenCoachReply: an empty row in state streaming, which the repair sweep can always settle. */
    suspend fun openCoachReply(conversationId: Long, now: Long, model: String?): Long {
        StateMachines.requireStart(StateMachines.MESSAGE, "streaming")
        return messages.insert(
            MessageRow(
                conversationId = conversationId,
                role = "coach",
                content = "",
                createdAt = now,
                status = "streaming",
                model = model,
            ),
        )
    }

    /**
     * Called about once a second while a reply streams, so leaving the app never loses it. Card lines are held
     * back (they become cards only when the reply ends), so a cut-off reply never shows or resends raw markup.
     */
    suspend fun savePartial(messageId: Long, text: String) = messages.setContent(messageId, LiveText.visible(text))

    /**
     * CloseCoachReply: the end of every request. The prose is stored without any card line, the cards
     * become rows in state offered, and anything dropped is logged as a count with no content.
     */
    suspend fun closeCoachReply(
        conversationId: Long,
        messageId: Long,
        raw: String,
        chatType: String,
        known: KnownIds,
        finishReason: String?,
        usage: com.clarity.life.ai.Usage?,
        now: Long,
        /** The model and thinking level that wrote it ("deepseek-flash, thinking low"), shown in Details. */
        modelLabel: String? = null,
    ): CardParser.ParseOutcome {
        val parsed = CardParser.parseReply(raw, chatType, known)
        db.withTransaction {
        messages.finish(
            id = messageId,
            content = parsed.prose,
            status = StateMachines.move(StateMachines.MESSAGE, "streaming", "complete"),
            model = modelLabel,
            tokensIn = usage?.tokensIn,
            tokensOut = usage?.tokensOut,
        )
        for (card in parsed.cards) {
            Values.requireValid("card.type", card.type)
            cards.insert(
                CardRow(
                    messageId = messageId,
                    type = card.type,
                    payloadJson = CardParser.toJson(card),
                    status = "offered",
                    createdAt = now,
                ),
            )
        }
        // Kept for the next turn only (in memory, never stored): the coach is told why its card was thrown away.
        if (parsed.dropped.isNotEmpty()) lastDrops[messageId] = parsed.dropped.distinct().take(3) else lastDrops.remove(messageId)
        if (parsed.dropped.isNotEmpty()) {
            db.diagnostics().log(
                ErrorLogRow(at = now, area = "card", code = "card_dropped", detail = "n=${parsed.dropped.size}"),
            )
        }
        if (finishReason == "length") {
            db.diagnostics().log(ErrorLogRow(at = now, area = "chat", code = "cut_off", detail = ""))
        }
        usage?.let {
            db.diagnostics().addUsage(
                month = monthKey(now, settings.timeZone()),
                calls = 1,
                tokensIn = it.tokensIn,
                tokensOut = it.tokensOut,
                tokensCached = it.tokensCached,
            )
        }
        }
        return CardParser.ParseOutcome(parsed.prose, parsed.cards.size, parsed.dropped.size)
    }

    /** InterruptCoachReply: the process is going away, so what arrived so far is kept and marked. */
    suspend fun interruptCoachReply(messageId: Long, partial: String, now: Long) {
        messages.setContent(messageId, LiveText.visible(partial))
        messages.setStatus(messageId, StateMachines.move(StateMachines.MESSAGE, "streaming", "interrupted"))
        db.diagnostics().log(ErrorLogRow(at = now, area = "chat", code = "interrupted", detail = ""))
    }

    /** FailCoachReply: one quiet, honest line, and a content-free code in the log. */
    suspend fun failCoachReply(messageId: Long, failure: AiFailure, now: Long) {
        messages.setStatus(messageId, StateMachines.move(StateMachines.MESSAGE, "streaming", "failed"))
        db.diagnostics().log(ErrorLogRow(at = now, area = "chat", code = failure.code, detail = ""))
    }

    /**
     * The last resort when a reply ends in a way nothing else handled: a row still `streaming` becomes
     * interrupted (keeping [partial]) or failed, so Talk never shows "thinking" for ever nor holds Send.
     * A row that already ended is left alone. Returns the status the row has now, or null when it is gone.
     */
    suspend fun settleIfStreaming(messageId: Long, partial: String, failure: AiFailure, now: Long): String? {
        val row = messages.get(messageId) ?: return null
        if (row.status != "streaming") return row.status
        if (partial.isNotEmpty()) interruptCoachReply(messageId, partial, now) else failCoachReply(messageId, failure, now)
        return messages.get(messageId)?.status
    }

    /** TryAgain: an interrupted or failed reply may be asked for again, which is an allowed transition. */
    suspend fun reopenReply(messageId: Long) {
        val row = messages.get(messageId) ?: return
        messages.setStatus(messageId, StateMachines.move(StateMachines.MESSAGE, row.status, "streaming"))
    }

    // ---------- the context packet ----------

    /**
     * Builds the request for one new message. The system message is reused byte for byte while the
     * conversation is open, and only rebuilt when he switches chat type or comes back on a later day, so
     * the provider's prefix cache keeps working (contracts/context.md).
     */
    suspend fun buildPacket(conversationId: Long, replyId: Long, now: Long): ChatPacket {
        val rows = messages.ofConversation(conversationId)
        val his = rows.lastOrNull { it.role == "user" && it.id < replyId }
            ?: throw IllegalStateException("reply $replyId answers no message")
        return buildPacket(conversationId, his.content, now, rows.filter { it.id < his.id })
    }

    /**
     * [earlier] is the history before the message being answered; that message itself goes only in the final
     * turn (contracts/context.md), never twice. By default it is every stored message except a trailing copy
     * of [newText].
     */
    suspend fun buildPacket(
        conversationId: Long,
        newText: String,
        now: Long,
        earlier: List<MessageRow>? = null,
    ): ChatPacket {
        val conversation = conversations.get(conversationId)
            ?: throw IllegalStateException("conversation $conversationId is gone")
        val tz = settings.timeZone()
        val boundary = settings.dayBoundaryHour()
        val today = Planner.dayKey(Instant.ofEpochMilli(now).toString(), tz, boundary).toString()

        var prefix = conversation.prefixJson?.let { readPrefix(it) }
        if (prefix == null || conversation.prefixDay != today) {
            prefix = buildPrefix(conversation)
            conversations.setPrefix(conversationId, writePrefix(prefix), today)
        }

        val before = earlier ?: messages.ofConversation(conversationId).let { all ->
            val last = all.lastOrNull { it.role == "user" || it.content.isNotBlank() }
            if (last?.role == "user" && last.content == newText) all.filter { it.id < last.id } else all
        }
        // The cards each reply offered go back into the history as card lines (contracts/context.md), so the
        // coach sees that a reply which pointed at a button really carried one, and keeps sending them.
        val offered = cards.ofConversation(conversationId).groupBy({ it.messageId }, { it.payloadJson })
        val stored = before
            .filter { it.role == "user" || (it.role == "coach" && (it.content.isNotBlank() || offered[it.id] != null)) }
        val history = stored.map { row ->
            ContextBuilder.historyMessage(
                role = row.role,
                text = row.content,
                // The stamp says how long ago, counted here (persona: never work out a date).
                stamp = if (row.role == "user") {
                    val day = Planner.dayKey(Instant.ofEpochMilli(row.createdAt).toString(), tz, boundary)
                    "${TimeFacts.stamp(Instant.ofEpochMilli(row.createdAt), tz)} (${TimeFacts.ago(day, LocalDate.parse(today))})"
                } else {
                    null
                },
                cards = if (row.role == "coach") offered[row.id].orEmpty() else emptyList(),
            )
        }
        val counts = history.map { ContextBuilder.estimateTokens(it.content) }
        val firstIndex = stored.indexOfFirst { it.id >= conversation.historyStartId }.coerceAtLeast(0)
        val start = ContextBuilder.nextHistoryStart(counts, firstIndex)
        if (start > firstIndex && start < stored.size) {
            conversations.moveHistoryStartForward(conversationId, stored[start].id)
        }
        val window = history.drop(start).map { com.clarity.life.ai.ChatMessage(it.role, it.content) }

        // Read with every message, so a plan saved or a check-in answered a minute ago is already here.
        val dayKey = java.time.LocalDate.parse(today)
        val tracker = planner.tracker(dayKey)
        val tracking = tracker.goals.isNotEmpty() || tracker.targets.isNotEmpty() || tracker.backlogs.isNotEmpty()
        val part3 = buildList {
            // Day counts to the dates he mentioned, newest message first: the coach never counts days itself.
            val said = listOf(newText) + stored.filter { it.role == "user" }.takeLast(20).reversed().map { it.content }
            add(Blocks.now(TimeFacts.nowSentence(Instant.ofEpochMilli(now), tz, boundary), com.clarity.life.core.DayCounts.lines(said, dayKey)))
            // In a plan chat PLAN_STATE is always there, so even with nothing tracked yet the coach has the
            // review options a first plan card needs; elsewhere it comes only once something is tracked.
            if (tracking || conversation.chatType == "plan") add(PlanBlocks.planState(tracker, dayKey))
            add(openPromises(now, tz))
            add(PlanBlocks.checkinFacts(tracker, dayKey))
            val results = cardResultLines(conversationId)
            if (results.isNotEmpty()) add(Blocks.cardResults(results))
            if (start > 0) add(Blocks.earlier(conversation.summary))
            // The conversation he was just in, word for word, while it is fresh: its summary may not be written
            // yet, and this conversation's frozen memory would then never see it (contracts/context.md).
            db.memory().lastOtherConversation(conversationId)?.takeIf { LastChat.fresh(it.lastAt, now) }?.let { other ->
                val said = messages.ofConversation(other.id).filter {
                    it.role == "user" || it.status == "complete" || it.status == "interrupted"
                }.map { LastChat.Msg(it.role, it.content, it.createdAt) }
                add(LastChat.block(other.title, other.firstAt, other.lastAt, said, now, tz, boundary))
            }
            // Matches from his other conversations and the records he keeps, searched with the new message, the
            // period it names and his last few messages here (Max plan Phase 2). Retrieval can never be what
            // fails a reply.
            try {
                val earlierHis = stored.filter { it.role == "user" }.map { it.content }
                add(search.relevantPast(conversationId, newText, tz, boundary, now, earlierHis, conversation.title))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                db.diagnostics().log(ErrorLogRow(at = now, area = "search", code = "relevant_past_failed", detail = ""))
            }
        }
        val turn = Blocks.userTurn(part3, newText)

        return ChatPacket(
            request = ChatRequest(
                system = prefix,
                messages = window + com.clarity.life.ai.ChatMessage(
                    com.clarity.life.ai.ChatMessage.USER,
                    turn.content,
                ),
            ),
            prefix = prefix,
            prefixDay = today,
            historyStartId = conversation.historyStartId,
            known = PlanBlocks.known(tracker, dayKey, focusIds()),
            droppedItems = turn.dropped,
            overBudget = turn.overBudget,
            windowMoved = start > firstIndex && start < stored.size,
        )
    }

    private suspend fun buildPrefix(conversation: ConversationRow): String {
        val name = settings.promptName()
        val allowed = CardParser.CHAT_TYPES[conversation.chatType]?.joinToString(", ").orEmpty()
        val persona = prompts.fill(prompts.text(PromptStore.PERSONA), mapOf("USER_NAME" to name))
        val examples = prompts.fill(prompts.text(PromptStore.EXAMPLES), mapOf("USER_NAME" to name))
        val cardsPrompt = prompts.fill(
            prompts.text(PromptStore.CARDS),
            mapOf("USER_NAME" to name, "ALLOWED_CARDS" to allowed),
        )
        // Inside a focus the selected chat type still applies (spec section 2.1: the cards are "the same as the
        // type currently selected"), so its file is sent too, then the focus file. Before, the focus file
        // replaced it: a Plan and track chat inside a focus kept its plan cards but lost the planning method.
        val modeFiles = listOfNotNull(
            PromptStore.modeFile(conversation.chatType),
            if (conversation.focusId != null) PromptStore.modeFile("focus") else null,
        )
        val mode = modeFiles.map { prompts.text(it) }.joinToString("\n\n").ifEmpty { null }
        val part2 = listOf(
            Blocks.flags(
                toughLove = settings.get(SettingsRepository.Keys.TOUGH_LOVE, "balanced"),
                listenOnly = settings.getBool(SettingsRepository.Keys.LISTEN_ONLY, false),
                chatType = conversation.chatType,
                offTheRecord = conversation.offTheRecord == 1,
            ),
        ) + focusBlocks(conversation) + memoryBlocks(conversation.id) + openedFromBlocks(conversation)
        val built = Blocks.systemMessage(persona, examples, cardsPrompt, mode, part2)
        if (built.overBudget) {
            db.diagnostics().log(
                ErrorLogRow(at = 0, area = "chat", code = "context_over_budget", detail = ""),
            )
        }
        return built.system
    }

    /** FOCUS, when the conversation belongs to one (contracts/context.md); frozen with the prefix. */
    private suspend fun focusBlocks(conversation: ConversationRow): List<com.clarity.life.core.Block> {
        val id = conversation.focusId ?: return emptyList()
        val m = db.memory()
        val f = m.focus(id) ?: return emptyList()
        fun step(p: com.clarity.life.data.entity.PromiseRow) =
            com.clarity.life.core.FocusBlock.Step(p.text, p.dueText, p.status, p.reason)
        return listOf(
            com.clarity.life.core.FocusBlock.block(
                com.clarity.life.core.FocusBlock.Focus(f.id, f.name, f.why, f.status, f.summary),
                m.focusCurrentStep(id)?.let(::step),
                m.focusLastSteps(id, com.clarity.life.core.FocusBlock.MAX_RESULTS).map(::step),
            ),
        )
    }

    /** Talk's chat type dropdown: one entry per active focus (spec section 2.1). */
    fun watchActiveFocuses() = db.memory().watchActiveFocuses()

    /** The F ids a mode card may name: every focus that is not done. */
    suspend fun focusIds(): Set<String> = db.memory().openFocusIds().map { "F$it" }.toSet()

    /**
     * PROFILE, PEOPLE, NOTES, RECENT_SESSIONS and IDEAS_RECENT: one shared memory for every conversation
     * (spec section 3). Read when the prefix is built, so it is frozen for the conversation's day.
     */
    private suspend fun memoryBlocks(conversationId: Long): List<com.clarity.life.core.Block> {
        val m = db.memory()
        val tz = settings.timeZone()
        val boundary = settings.dayBoundaryHour()
        val today = Planner.dayKey(Instant.now().toString(), tz, boundary)
        fun ago(day: String) = runCatching { TimeFacts.ago(LocalDate.parse(day), today) }.getOrNull()
        val evidence = m.allEvidence().groupBy { it.noteId }
        val notes = m.activeNotes().map { n ->
            MemoryBlocks.Note(
                n.id, n.kind, n.confidence, n.timesSeen, n.firstSeen, n.lastSeen, n.text,
                (evidence[n.id] ?: emptyList()).map { MemoryBlocks.Evidence(it.kind, it.text, it.day, ago(it.day)) },
            )
        }
        // Dated by the last message, with the clock times: "today" alone left a plan made at 9 am and a message
        // at 11 am in no order the coach could see.
        val sessions = m.recentSessions(conversationId, MemoryBlocks.RECENT_SESSIONS).map { c ->
            val day = Planner.dayKey(Instant.ofEpochMilli(c.lastAt).toString(), tz, boundary).toString()
            val span = TimeFacts.span(Instant.ofEpochMilli(c.firstAt), Instant.ofEpochMilli(c.lastAt), tz)
            MemoryBlocks.Session(day, c.title, c.summary, ago(day), span)
        }
        return listOf(
            MemoryBlocks.profile(m.profile().map { MemoryBlocks.Profile(it.key, it.value, it.confirmed == 1) }),
            MemoryBlocks.people(m.people().map { MemoryBlocks.Person(it.name, it.relation, it.notes, it.updatedAt) }),
            MemoryBlocks.notes(notes),
            // The last two quarterly chapters: what the coach carries forward for years (spec section 8).
            com.clarity.life.core.Chapters.block(
                db.writing().latestChapters(com.clarity.life.core.Chapters.IN_CONTEXT).map {
                    com.clarity.life.core.Chapters.Chapter(it.periodStart, it.periodEnd, it.title, it.body)
                },
            ),
            MemoryBlocks.recentSessions(sessions),
            MemoryBlocks.ideas(m.recentItems("idea", MemoryBlocks.IDEAS).map { it.text }),
        )
    }

    /**
     * LETTER or REVIEW: only in a conversation opened with "Talk about this letter" or "Talk about it", so the
     * coach has in front of it exactly what he was reading (contracts/context.md, Part 2; never trimmed).
     */
    private suspend fun openedFromBlocks(c: ConversationRow): List<com.clarity.life.core.Block> {
        val id = c.openedFromId ?: return emptyList()
        fun block(name: String, lines: List<String>) =
            com.clarity.life.core.Block(name, lines.filter { it.isNotBlank() }.map { com.clarity.life.core.BlockItem(it, 0) })
        return when (c.openedFrom) {
            "letter" -> {
                val letter = db.writing().letter(id)?.takeIf { it.deletedAt == null } ?: return emptyList()
                listOf(block("LETTER", listOf("Title: ${letter.title}", letter.body)))
            }
            "review" -> {
                val review = db.writing().review(id) ?: return emptyList()
                val facts = com.clarity.life.core.ReviewFacts.fromJson(review.factsJson)?.lines.orEmpty()
                val note = review.narrative.takeIf { it.isNotBlank() }?.let { "Coach's note: $it" }.orEmpty()
                listOf(block("REVIEW", facts + note))
            }
            else -> emptyList()
        }
    }

    /** OPEN_PROMISES: every open promise with its due phrase, then the ones he answered in the last two weeks. */
    private suspend fun openPromises(now: Long, tz: String): com.clarity.life.core.Block {
        val p = db.promises()
        val since = now - java.time.Duration.ofDays(com.clarity.life.core.PromiseText.RECENT_DAYS).toMillis()
        return com.clarity.life.core.PromiseText.block(
            open = p.open().map { com.clarity.life.promise.PromiseRepository.model(it) },
            answered = p.answeredSince(since).map { com.clarity.life.promise.PromiseRepository.model(it) },
            now = Instant.ofEpochMilli(now),
            tz = tz,
        )
    }

    /**
     * What he did with the cards under the coach's last reply (contracts/context.md), and only those: older
     * cards were reported with the turn after them, and repeating them every turn only costs tokens.
     */
    /** The parser's reasons for the cards it threw away, by coach message id. In memory only; rule names, no content. */
    private val lastDrops = java.util.concurrent.ConcurrentHashMap<Long, List<String>>()

    private suspend fun cardResultLines(conversationId: Long): List<String> {
        val replies = messages.ofConversation(conversationId).filter { it.role == "coach" && it.status == "complete" }
        val last = replies.lastOrNull() ?: return emptyList()
        return lastReplyCardLines(last) + earlierCardLines(conversationId, last, replies.getOrNull(replies.size - 2))
    }

    /**
     * Cards under older replies that he answered after the reply before the last one began: no CARD_RESULTS
     * has told the coach about them yet (each request reports only the last reply's cards), so it could offer
     * one again or talk as if he never answered. Reported at most twice, then OPEN_PROMISES and PLAN_STATE
     * carry what was saved.
     */
    private suspend fun earlierCardLines(conversationId: Long, last: MessageRow, before: MessageRow?): List<String> {
        val since = before?.createdAt ?: return emptyList()
        return cards.ofConversation(conversationId)
            .filter { it.messageId < last.id && it.status != "offered" && (it.decidedAt ?: 0L) > since }
            .map { Blocks.earlierCardResultLine(it.type, cardLabel(it), it.status) }
    }

    private suspend fun lastReplyCardLines(last: MessageRow): List<String> {
        val rows = cards.ofMessage(last.id)
        val drops = lastDrops[last.id].orEmpty().map { com.clarity.life.core.CardTalk.dropLine(it) }
        if (drops.isNotEmpty()) {
            return drops + rows.map { row -> Blocks.cardResultLine(row.type, cardLabel(row), row.status) }
        }
        // The coach spoke of a button and none was shown: tell it, so it can send the card instead of guessing.
        if (rows.isEmpty() && com.clarity.life.core.CardTalk.pointsAtAButton(last.content)) {
            return listOf("No button was shown under your last reply, though your words pointed at one. If you still mean to offer it, send the card now as the last line.")
        }
        return rows.map { row ->
            val text = cardLabel(row)
            Blocks.cardResultLine(row.type, text, row.status)
        }
    }

    private fun cardLabel(row: CardRow): String {
        val obj = try {
            json.parseToJsonElement(row.payloadJson).jsonObject
        } catch (e: Exception) {
            return row.type
        }
        for (key in listOf("text", "title", "name")) {
            (obj[key] as? JsonPrimitive)?.contentOrNull?.let { return it }
        }
        return row.type
    }

    private fun readPrefix(stored: String): String? = try {
        (json.parseToJsonElement(stored) as? JsonObject)
            ?.get("system")?.let { (it as? JsonPrimitive)?.contentOrNull }
    } catch (e: Exception) {
        null
    }

    private fun writePrefix(system: String): String =
        JsonObject(mapOf("system" to JsonPrimitive(system))).toString()

    /** The usage counter is grouped by his local month, not by UTC. */
    private fun monthKey(now: Long, tz: String): String =
        TimeFacts.local(Instant.ofEpochMilli(now), tz).toLocalDate().toString().take(7)

    // ---------- providers ----------

    /** Only the provider he chose. When it is down the reply fails honestly; no other model is ever used. */
    suspend fun endpointForChat(): Endpoint? = settings.chatEndpoint()

    fun clientFor(endpoint: Endpoint): AiClient =
        if (endpoint.provider == "anthropic") anthropicClient else mainClient

    fun stream(endpoint: Endpoint, request: ChatRequest): Flow<ChatChunk> =
        clientFor(endpoint).stream(endpoint, request)

    /**
     * The card step (contracts/context.md): every finished reply that has no valid card is followed by one more
     * request, in the provider's JSON mode, for the cards that reply offered (prompts/card_followup.md), read
     * by the same strict parser. An empty list is the normal answer. Returns the raw reply to close with: the
     * merged one when a valid card came back, otherwise [raw] unchanged. A failed follow-up never fails the
     * reply; the quiet "no button" line then shows as before. Logged as codes, never content.
     */
    suspend fun withCardFollowUp(
        endpoint: Endpoint,
        request: ChatRequest,
        raw: String,
        chatType: String,
        known: KnownIds,
        finishReason: String?,
        now: Long,
    ): String {
        val parsed = CardParser.parseReply(raw, chatType, known)
        if (!com.clarity.life.core.CardFollowUp.needed(parsed, finishReason)) return raw
        return try {
            val ask = prompts.fill(
                prompts.text(PromptStore.CARD_FOLLOWUP),
                mapOf("REASON" to com.clarity.life.core.CardFollowUp.reason(parsed.dropped)),
            )
            // The same prefix and history, so the provider's cache covers almost all of it. No thinking: the
            // offer is already made in the reply, this only writes it down, in JSON mode so the shape is certain.
            val stepEndpoint = endpoint.copy(
                thinking = false,
                effort = if (endpoint.provider == "deepseek") com.clarity.life.core.Thinking.OFF else endpoint.effort,
                temperature = if (endpoint.provider == "deepseek") 0.0 else endpoint.temperature,
                maxOutputTokens = minOf(endpoint.maxOutputTokens, CARD_STEP_MAX_TOKENS),
            )
            val followUp = request.copy(
                jsonOutput = true,
                messages = request.messages +
                    com.clarity.life.ai.ChatMessage(
                        com.clarity.life.ai.ChatMessage.ASSISTANT,
                        ContextBuilder.historyMessage("coach", parsed.prose).content,
                    ) +
                    com.clarity.life.ai.ChatMessage(com.clarity.life.ai.ChatMessage.USER, ask),
            )
            val answer = StringBuilder()
            var usage: com.clarity.life.ai.Usage? = null
            stream(stepEndpoint, followUp).collect { chunk ->
                when (chunk) {
                    is ChatChunk.Text -> {
                        answer.append(chunk.text)
                    }

                    is ChatChunk.Finished -> {
                        usage = chunk.usage
                    }

                    else -> Unit
                }
            }
            usage?.let {
                db.diagnostics().addUsage(
                    month = monthKey(now, settings.timeZone()),
                    calls = 1,
                    tokensIn = it.tokensIn,
                    tokensOut = it.tokensOut,
                    tokensCached = it.tokensCached,
                )
            }
            val merged = com.clarity.life.core.CardFollowUp.merged(parsed.prose, answer.toString(), chatType, known)
            db.diagnostics().log(
                ErrorLogRow(at = now, area = "card", code = if (merged != null) "card_followup_ok" else "card_followup_none", detail = ""),
            )
            merged ?: raw
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            db.diagnostics().log(ErrorLogRow(at = now, area = "card", code = "card_followup_failed", detail = ""))
            raw
        }
    }

    companion object {
        /** Years of conversations fit; the list is lazy, so only the rows on screen are drawn. */
        const val DRAWER_LIMIT = 5000

        /** The card step writes at most three small JSON cards; this is ample and keeps a runaway answer short. */
        const val CARD_STEP_MAX_TOKENS = 2000
    }
}
