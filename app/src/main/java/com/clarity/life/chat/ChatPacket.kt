package com.clarity.life.chat

import com.clarity.life.ai.ChatMessage
import com.clarity.life.ai.ChatRequest
import com.clarity.life.core.Block
import com.clarity.life.core.BlockItem
import com.clarity.life.core.ContextBuilder
import com.clarity.life.core.KnownIds

/**
 * What one chat request is made of, kept apart from the database and the network so it can be read and
 * tested on its own. The layout is contracts/context.md: a frozen system message, the history window, and
 * the volatile blocks in the final user turn.
 */
data class ChatPacket(
    val request: ChatRequest,
    /** The system message as it was stored in conversation.prefix_json, byte for byte. */
    val prefix: String,
    /** The day key the prefix was built on; a later day rebuilds it, so stale notes are never sent. */
    val prefixDay: String,
    val historyStartId: Long,
    val known: KnownIds,
    val droppedItems: Int,
    val overBudget: Boolean,
    /**
     * The history window jumped forward with this request. Older messages are now only in EARLIER (the
     * conversation's summary), so the conversation should be reflected now to bring that summary up to date.
     */
    val windowMoved: Boolean = false,
)

/**
 * The chat blocks written here. The memory blocks (PROFILE, PEOPLE, NOTES, RECENT_SESSIONS, IDEAS_RECENT)
 * are in [com.clarity.life.core.MemoryBlocks], OPEN_PROMISES in [com.clarity.life.core.PromiseText]; PLAN_STATE
 * and CHECKIN_FACTS arrive with the planner. A block with nothing to say is omitted, never sent empty.
 */
object Blocks {

    /** FLAGS: never trimmed, and the one block that is always present. */
    fun flags(toughLove: String, listenOnly: Boolean, chatType: String, offTheRecord: Boolean): Block = Block(
        "FLAGS",
        listOf(
            BlockItem("tough_love: $toughLove", 0),
            BlockItem("listen_only: $listenOnly", 0),
            BlockItem("chat_type: $chatType", 0),
            BlockItem("off_the_record: $offTheRecord", 0),
        ),
    )

    /** NOW: the code-written sentence, then the day count to each date he mentioned ([com.clarity.life.core.DayCounts]). */
    fun now(sentence: String, dayCounts: List<String> = emptyList()): Block =
        Block("NOW", (listOf(sentence) + dayCounts).map { BlockItem(it, 0) })

    /**
     * CARD_RESULTS: what he did with each card under the coach's last reply, so the coach never offers the
     * same thing twice and never claims something was saved.
     */
    fun cardResults(lines: List<String>): Block = Block("CARD_RESULTS", lines.map { BlockItem(it, 0) })

    /** EARLIER: the running summary of the part of this conversation that left the history window. */
    fun earlier(summary: String): Block =
        if (summary.isBlank()) Block("EARLIER", emptyList()) else Block("EARLIER", listOf(BlockItem(summary, 0)))

    fun cardResultLine(type: String, text: String, status: String): String {
        val what = when (status) {
            "saved" -> "saved"
            "dismissed" -> "not now"
            else -> "no answer yet"
        }
        return "$type \"$text\": $what"
    }

    /** The system message is Part 1 + Part 2 (contracts/context.md); only Part 2 can be trimmed. */
    fun systemMessage(
        persona: String,
        examples: String,
        cards: String,
        mode: String?,
        part2: List<Block>,
    ) = ContextBuilder.buildPrefix(persona, examples, cards, mode, part2)

    fun userTurn(part3: List<Block>, text: String) = ContextBuilder.buildUserTurn(part3, text)

    fun history(messages: List<Pair<String, String>>): List<ChatMessage> = messages.map { (role, content) ->
        ChatMessage(if (role == "user") ChatMessage.USER else ChatMessage.ASSISTANT, content)
    }
}
