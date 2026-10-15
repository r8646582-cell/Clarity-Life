package com.clarity.life.core

import java.time.Instant

/**
 * What the reflection job sends to the model, built from rows without touching Android, so it is tested on
 * the JVM (ReflectionInputTest).
 *
 * The rules it pins down:
 *  - only his messages after `reflected_up_to_message_id` are new; when there are none, there is no job
 *    and no API call;
 *  - the transcript marks the already-reflected part with the exact line reflection.md expects;
 *  - his words and the coach's are escaped like every other context text (contracts/context.md), so a
 *    pasted text can never fake a header or a card;
 *  - the quotes the model may cite from earlier conversations are exactly the ones it was shown.
 */
object ReflectionInput {

    const val ALREADY_REFLECTED = "--- ALREADY REFLECTED ABOVE THIS LINE ---"

    /** At most this many already-reflected messages are sent again as context. */
    const val CONTEXT_MESSAGES = 40

    const val RECENT_EVENTS = 12
    const val EARLIER_QUOTES = 20

    data class Msg(val id: Long, val role: String, val text: String, val createdAt: Long)

    data class Selection(
        /** The newest message id the reflection covers; stored as reflected_up_to_message_id on success. */
        val upTo: Long,
        /** The messages sent, oldest first: some already reflected (context only) and the new ones. */
        val messages: List<Msg>,
        /** His new messages: the only text a quote in the result may come from. */
        val newHis: List<String>,
    )

    /**
     * [messages] are the conversation's stored messages, in id order, with empty coach rows already removed.
     * Returns null when he has said nothing new since the last reflection.
     */
    fun select(messages: List<Msg>, reflectedUpTo: Long): Selection? {
        val newHis = messages.filter { it.role == "user" && it.id > reflectedUpTo }
        if (newHis.isEmpty()) return null
        val old = messages.filter { it.id <= reflectedUpTo }.takeLast(CONTEXT_MESSAGES)
        val new = messages.filter { it.id > reflectedUpTo }
        return Selection(messages.last().id, old + new, newHis.map { it.text })
    }

    /** A failed reflection rests this long before another round of attempts. */
    const val RETRY_AFTER_HOURS = 6L

    /** Rounds of attempts in all (each [ATTEMPTS_PER_ROUND] long), so a result that never passes stops costing. */
    const val MAX_ROUNDS = 3
    const val ATTEMPTS_PER_ROUND = 3

    /**
     * Whether a failed reflection may run again: at once within a round of [ATTEMPTS_PER_ROUND]; after a spent
     * round only [RETRY_AFTER_HOURS] after its last failure, and never after [MAX_ROUNDS] rounds.
     * [finishedAt] null (never recorded) waits like a fresh failure would.
     */
    fun mayRun(attempts: Int, finishedAt: Long?, now: Long): Boolean = when {
        attempts >= MAX_ROUNDS * ATTEMPTS_PER_ROUND -> false
        attempts % ATTEMPTS_PER_ROUND != 0 -> true
        else -> finishedAt != null && now - finishedAt >= RETRY_AFTER_HOURS * 3_600_000L
    }

    /** reflection:<conversation>:<last message id>. A replay of the same work finds the same key. */
    fun jobKey(conversationId: Long, upTo: Long) = "reflection:$conversationId:$upTo"

    fun transcript(selection: Selection, reflectedUpTo: Long, tz: String): String {
        val sb = StringBuilder()
        var marked = false
        for (m in selection.messages) {
            if (!marked && m.id > reflectedUpTo) {
                if (sb.isNotEmpty()) sb.append(ALREADY_REFLECTED).append("\n\n")
                marked = true
            }
            val text = ContextBuilder.escape(m.text)
            if (m.role == "user") {
                sb.append('[').append(TimeFacts.stamp(Instant.ofEpochMilli(m.createdAt), tz)).append("] User: ").append(text)
            } else {
                sb.append("Coach: ").append(text)
            }
            sb.append("\n\n")
        }
        return sb.toString().trimEnd()
    }

    data class ProfileLine(val key: String, val value: String, val confirmed: Boolean)

    data class PersonLine(val name: String, val relation: String, val notes: String)

    data class EvidenceLine(val kind: String, val text: String, val day: String)

    data class NoteLine(
        val id: Long,
        val kind: String,
        val confidence: String,
        val timesSeen: Int,
        val firstSeen: String,
        val lastSeen: String,
        val text: String,
        val evidence: List<EvidenceLine>,
    )

    data class EventLine(val day: String, val situation: String, val action: String, val outcome: String)

    /** An earlier conversation summary or event found in the whole history; [label] is written by code. */
    data class EarlierLine(val label: String, val text: String)

    /** The heading of the block of [EarlierLine]s in CONTEXT. */
    const val EARLIER_HEADING = "EARLIER CONVERSATIONS AND EVENTS LIKE THIS ONE (found by the app in the whole history, oldest first)"

    /**
     * The CONTEXT placeholder: profile, people, active notes with ids and their evidence, recent events, the
     * earlier conversations and events most like this one ([earlier], Max plan Phase 3: a pattern months apart
     * is in front of the job) and his earlier words. Every piece of it is his data, so all of it is escaped.
     */
    fun context(
        profile: List<ProfileLine>,
        people: List<PersonLine>,
        notes: List<NoteLine>,
        events: List<EventLine>,
        earlierQuotes: List<String>,
        earlier: List<EarlierLine> = emptyList(),
    ): String {
        val e = ContextBuilder::escape
        val parts = mutableListOf<String>()
        if (profile.isNotEmpty()) {
            parts += "PROFILE\n" + profile.joinToString("\n") {
                "- ${e(it.key)}: ${e(it.value)}" + if (it.confirmed) " (confirmed by them)" else ""
            }
        }
        if (people.isNotEmpty()) {
            parts += "PEOPLE\n" + people.joinToString("\n") { p ->
                "- ${e(p.name)}" + (if (p.relation.isNotBlank()) " (${e(p.relation)})" else "") +
                    (if (p.notes.isNotBlank()) ": ${e(p.notes)}" else "")
            }
        }
        if (notes.isNotEmpty()) {
            parts += "ACTIVE NOTES (update them by id)\n" + notes.joinToString("\n") { n ->
                val seen = if (n.timesSeen == 1) "seen once" else "seen ${n.timesSeen} times"
                val head = "- id ${n.id}, ${n.kind}, ${n.confidence}, $seen, ${n.firstSeen} to ${n.lastSeen}: ${e(n.text)}"
                (listOf(head) + n.evidence.map { "    evidence (${it.kind}, ${it.day}): ${e(it.text)}" })
                    .joinToString("\n")
            }
        }
        if (events.isNotEmpty()) {
            parts += "RECENT EVENTS\n" + events.joinToString("\n") { ev ->
                "- ${ev.day}: " + listOf(ev.situation, ev.action, ev.outcome).filter { it.isNotBlank() }
                    .joinToString("; ") { e(it) }
            }
        }
        if (earlier.isNotEmpty()) {
            parts += EARLIER_HEADING + "\n" + earlier.joinToString("\n") { "- ${e(it.label)}: ${e(it.text)}" }
        }
        if (earlierQuotes.isNotEmpty()) {
            parts += "THEIR WORDS FROM EARLIER CONVERSATIONS\n" + earlierQuotes.joinToString("\n") { "- \"${e(it)}\"" }
        }
        return if (parts.isEmpty()) "(nothing yet)" else parts.joinToString("\n\n")
    }

    /** DELETED_NOTES: one per line, or a plain "none". */
    fun deleted(texts: List<String>): String =
        if (texts.isEmpty()) "none" else texts.joinToString("\n") { "- " + ContextBuilder.escape(it) }

    /**
     * The quotes the result may cite as earlier words: those shown under THEIR WORDS and as note evidence.
     * Nothing the model was not shown can count as his.
     */
    fun earlierQuotesShown(notes: List<NoteLine>, earlierQuotes: List<String>): List<String> =
        earlierQuotes + notes.flatMap { n -> n.evidence.filter { it.kind == "quote" }.map { it.text } }
}
