package com.clarity.life.chat

import com.clarity.life.core.Block
import com.clarity.life.core.BlockItem
import com.clarity.life.core.CardTalk
import com.clarity.life.core.Chapters
import com.clarity.life.core.FocusBlock
import com.clarity.life.core.MemoryBlocks
import com.clarity.life.core.Planner
import com.clarity.life.core.PromiseText
import com.clarity.life.core.ReviewFacts
import com.clarity.life.core.Spans
import com.clarity.life.core.TimeFacts
import com.clarity.life.data.ClarityDatabase
import com.clarity.life.data.dao.CardDao
import com.clarity.life.data.dao.MessageDao
import com.clarity.life.data.entity.CardRow
import com.clarity.life.data.entity.ConversationRow
import com.clarity.life.data.entity.MessageRow
import com.clarity.life.data.entity.PromiseRow
import com.clarity.life.promise.PromiseRepository
import com.clarity.life.settings.SettingsRepository
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Builds the frozen system prefix blocks and dynamic packet blocks (memory, focus, letters,
 * reviews, open promises, and card results) for chat turns.
 */
class ChatPacketBuilder(
    private val db: ClarityDatabase,
    private val settings: SettingsRepository,
    private val cards: CardDao,
    private val messages: MessageDao,
    private val json: Json,
) {
    /** The parser's reasons for the cards it threw away, by coach message id. In memory only; rule names, no content. */
    private val lastDrops = ConcurrentHashMap<Long, List<String>>()

    fun recordDrops(messageId: Long, dropped: List<String>) {
        if (dropped.isNotEmpty()) {
            lastDrops[messageId] = dropped.distinct().take(3)
        } else {
            lastDrops.remove(messageId)
        }
    }

    /** FOCUS, when the conversation belongs to one (contracts/context.md); frozen with the prefix. */
    suspend fun focusBlocks(conversation: ConversationRow): List<Block> {
        val id = conversation.focusId ?: return emptyList()
        val m = db.memory()
        val f = m.focus(id) ?: return emptyList()
        fun step(p: PromiseRow) =
            FocusBlock.Step(p.text, p.dueText, p.status, p.reason)
        return listOf(
            FocusBlock.block(
                FocusBlock.Focus(f.id, f.name, f.why, f.status, f.summary),
                m.focusCurrentStep(id)?.let(::step),
                m.focusLastSteps(id, FocusBlock.MAX_RESULTS).map(::step),
            ),
        )
    }

    /**
     * PROFILE, PEOPLE, NOTES, RECENT_SESSIONS and IDEAS_RECENT: one shared memory for every conversation
     * (spec section 3). Read when the prefix is built, so it is frozen for the conversation's day.
     */
    suspend fun memoryBlocks(conversationId: Long, text: String): List<Block> {
        val m = db.memory()
        val tz = settings.timeZone()
        val boundary = settings.dayBoundaryHour()
        val today = Planner.dayKey(Instant.now().toString(), tz, boundary)
        fun ago(day: String) = runCatching { TimeFacts.ago(LocalDate.parse(day), today) }.getOrNull()
        val evidence = Spans.span("frozen: all evidence read") { m.allEvidence() }.groupBy { it.noteId }
        val notes = Spans.span("frozen: active notes read") { m.activeNotes() }.map { n ->
            MemoryBlocks.Note(
                n.id, n.kind, n.confidence, n.timesSeen, n.firstSeen, n.lastSeen, n.text,
                (evidence[n.id] ?: emptyList()).map { MemoryBlocks.Evidence(it.kind, it.text, it.day, ago(it.day)) },
            )
        }
        // Dated by the last message, with the clock times: "today" alone left a plan made at 9 am and a message
        // at 11 am in no order the coach could see.
        val sessions = Spans.span("frozen: recent sessions read") { m.recentSessions(conversationId, MemoryBlocks.RECENT_SESSIONS) }.map { c ->
            val day = Planner.dayKey(Instant.ofEpochMilli(c.lastAt).toString(), tz, boundary).toString()
            val span = TimeFacts.span(Instant.ofEpochMilli(c.firstAt), Instant.ofEpochMilli(c.lastAt), tz)
            MemoryBlocks.Session(day, c.title, c.summary, ago(day), span)
        }
        return listOf(
            MemoryBlocks.profile(
                m.profile().let { entries ->
                    // The newest replaced value of each key (profile_past, schema 6).
                    val past = m.profilePast().groupBy { it.key }.mapValues { (_, rows) -> rows.maxBy { it.id } }
                    entries.map {
                        val before = past[it.key]
                        MemoryBlocks.Profile(it.key, it.value, it.confirmed == 1, it.sinceDay, before?.value, before?.untilDay)
                    }
                },
            ),
            MemoryBlocks.people(m.people().map { MemoryBlocks.Person(it.name, it.relation, it.notes, it.updatedAt) }),
            // Notes about what this conversation is about are the last to go when memory is large.
            Spans.span("frozen: NOTES chosen") { MemoryBlocks.notes(notes, MemoryBlocks.relevantNotes(notes, text)) },
            // The last two quarterly chapters: what the coach carries forward for years (spec section 8).
            Chapters.block(
                db.writing().latestChapters(Chapters.IN_CONTEXT).map {
                    Chapters.Chapter(it.periodStart, it.periodEnd, it.title, it.body)
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
    suspend fun openedFromBlocks(c: ConversationRow): List<Block> {
        val id = c.openedFromId ?: return emptyList()
        fun block(name: String, lines: List<String>) =
            Block(name, lines.filter { it.isNotBlank() }.map { BlockItem(it, 0) })
        return when (c.openedFrom) {
            "letter" -> {
                val letter = db.writing().letter(id)?.takeIf { it.deletedAt == null } ?: return emptyList()
                listOf(block("LETTER", listOf("Title: ${letter.title}", letter.body)))
            }
            "review" -> {
                val review = db.writing().review(id) ?: return emptyList()
                val facts = ReviewFacts.fromJson(review.factsJson)?.lines.orEmpty()
                val note = review.narrative.takeIf { it.isNotBlank() }?.let { "Coach's note: $it" }.orEmpty()
                listOf(block("REVIEW", facts + note))
            }
            else -> emptyList()
        }
    }

    /** OPEN_PROMISES: every open promise with its due phrase, then the ones he answered in the last two weeks. */
    suspend fun openPromises(now: Long, tz: String): Block {
        val p = db.promises()
        val since = now - Duration.ofDays(PromiseText.RECENT_DAYS).toMillis()
        return PromiseText.block(
            open = p.open().map { PromiseRepository.model(it) },
            answered = p.answeredSince(since).map { PromiseRepository.model(it) },
            now = Instant.ofEpochMilli(now),
            tz = tz,
            // How his promises usually go, counted by code (Max plan Phase 2 step 6).
            longer = p.answeredSince(now - Duration.ofDays(PromiseText.TALLY_DAYS).toMillis())
                .map { PromiseRepository.model(it) },
        )
    }

    /**
     * What he did with the cards under the coach's last reply (contracts/context.md), and only those: older
     * cards were reported with the turn after them, and repeating them every turn only costs tokens.
     */
    suspend fun cardResultLines(conversationId: Long): List<String> {
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
    suspend fun earlierCardLines(conversationId: Long, last: MessageRow, before: MessageRow?): List<String> {
        val since = before?.createdAt ?: return emptyList()
        return cards.ofConversation(conversationId)
            .filter { it.messageId < last.id && it.status != "offered" && (it.decidedAt ?: 0L) > since }
            .map { Blocks.earlierCardResultLine(it.type, cardLabel(it), it.status) }
    }

    suspend fun lastReplyCardLines(last: MessageRow): List<String> {
        val rows = cards.ofMessage(last.id)
        val drops = lastDrops[last.id].orEmpty().map { CardTalk.dropLine(it) }
        if (drops.isNotEmpty()) {
            return drops + rows.map { row -> Blocks.cardResultLine(row.type, cardLabel(row), row.status) }
        }
        // The coach spoke of a button and none was shown: tell it, so it can send the card instead of guessing.
        if (rows.isEmpty() && CardTalk.pointsAtAButton(last.content)) {
            return listOf("No button was shown under your last reply, though your words pointed at one. If you still mean to offer it, send the card now as the last line.")
        }
        return rows.map { row ->
            val text = cardLabel(row)
            Blocks.cardResultLine(row.type, text, row.status)
        }
    }

    fun cardLabel(row: CardRow): String {
        val obj = try {
            json.parseToJsonElement(row.payloadJson).jsonObject
        } catch (e: Exception) {
            return row.type
        }
        for (key in listOf("text", "title", "name", "target", "promise")) {
            (obj[key] as? JsonPrimitive)?.contentOrNull?.let { return it }
        }
        return row.type
    }

    fun readPrefix(stored: String): String? = try {
        (json.parseToJsonElement(stored) as? JsonObject)
            ?.get("system")?.let { (it as? JsonPrimitive)?.contentOrNull }
    } catch (e: Exception) {
        null
    }

    fun writePrefix(system: String): String =
        JsonObject(mapOf("system" to JsonPrimitive(system))).toString()

    /** The usage counter is grouped by his local month, not by UTC. */
    fun monthKey(now: Long, tz: String): String =
        TimeFacts.local(Instant.ofEpochMilli(now), tz).toLocalDate().toString().take(7)
}
