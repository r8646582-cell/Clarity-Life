package com.clarity.life.lifetime

import androidx.sqlite.db.SupportSQLiteDatabase
import com.clarity.life.core.VectorMath
import com.clarity.life.memory.Embedder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.random.Random

/**
 * A synthetic ten years of heavy use (docs/MAX-PLAN.md, Phase 0 step 4), written straight into a database with
 * the app's schema, so the timing test can measure the app's own code on a lifetime of data. Invented text only,
 * deterministic for a seed. Raw SQL with prepared statements, in one transaction, so it fills in minutes.
 *
 * Per year by default: 730 conversations of 10 messages (5 his, 5 the coach's), so 73,000 messages in ten
 * years; a summary and a meaning vector for each conversation and each of his messages (as the indexer makes
 * them, random unit vectors here: only their number and size matter for timing); three targets checked in on
 * most days; about 150 promises, 40 notes, 300 quotes, 200 events, 52 letters, 64 reviews and 4 chapters.
 */
class TenYears(
    private val years: Int = 10,
    private val conversationsPerDay: Int = 2,
    private val seed: Int = 2026,
) {
    data class Counts(
        val conversations: Int,
        val messages: Int,
        val searchDocs: Int,
        val vectors: Int,
        val checkins: Int,
        val promises: Int,
        val notes: Int,
    )

    private val rnd = Random(seed)

    fun fill(db: SupportSQLiteDatabase, now: Instant): Counts {
        val zone = ZoneOffset.UTC
        val lastDay = now.atZone(zone).toLocalDate().minusDays(1)
        val firstDay = lastDay.minusYears(years.toLong())
        var conversations = 0
        var messages = 0
        var docs = 0
        var vectors = 0
        var checkins = 0
        var promises = 0
        var notes = 0

        db.beginTransaction()
        try {
            val conv = db.compileStatement(
                "INSERT INTO conversation (title, chat_type, started_at, ended_at, summary, reflected_up_to_message_id, off_the_record) " +
                    "VALUES (?, ?, ?, ?, ?, ?, 0)",
            )
            val msg = db.compileStatement(
                "INSERT INTO message (conversation_id, role, content, created_at, status, model) VALUES (?, ?, ?, ?, 'complete', ?)",
            )
            val doc = db.compileStatement("INSERT INTO search_doc (kind, ref_id, title, body) VALUES (?, ?, '', ?)")
            val vec = db.compileStatement(
                "INSERT INTO embedding_row (doc_kind, doc_id, model, dim, vector) VALUES (?, ?, '${Embedder.MODEL}', ${Embedder.DIM}, ?)",
            )
            val quote = db.compileStatement("INSERT INTO quote (conversation_id, text, said_day) VALUES (?, ?, ?)")
            val event = db.compileStatement(
                "INSERT INTO event (conversation_id, when_text, situation, feeling_before, action, payoff, outcome, created_at) " +
                    "VALUES (?, ?, ?, ?, ?, '', ?, ?)",
            )
            val promise = db.compileStatement(
                "INSERT INTO promise (text, why, due_at, due_text, remind, status, reason, source_conversation_id, created_at, resolved_at) " +
                    "VALUES (?, '', ?, ?, 0, ?, ?, ?, ?, ?)",
            )

            var day = firstDay
            while (!day.isAfter(lastDay)) {
                val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
                repeat(conversationsPerDay) { c ->
                    val start = dayStart + (8 + c * 9) * HOUR + rnd.nextLong(0, HOUR)
                    val topic = TOPICS[rnd.nextInt(TOPICS.size)]
                    conv.bindString(1, title(topic))
                    conv.bindString(2, if (rnd.nextInt(10) == 0) "plan" else "free")
                    conv.bindLong(3, start)
                    conv.bindLong(4, start + 40 * MINUTE)
                    conv.bindString(5, summary(topic, day))
                    conv.bindLong(6, 0)
                    val cid = conv.executeInsert()
                    conversations++
                    var lastMessage = 0L
                    for (i in 0 until 10) {
                        val his = i % 2 == 0
                        val text = if (his) said(topic) else coach(topic)
                        msg.bindLong(1, cid)
                        msg.bindString(2, if (his) "user" else "coach")
                        msg.bindString(3, text)
                        msg.bindLong(4, start + i * 4 * MINUTE)
                        if (his) msg.bindNull(5) else msg.bindString(5, "deepseek-v4-flash, thinking high")
                        val mid = msg.executeInsert()
                        messages++
                        lastMessage = mid
                        if (his) {
                            doc.bindString(1, "message"); doc.bindString(2, mid.toString()); doc.bindString(3, text)
                            doc.executeInsert(); docs++
                            vec.bindString(1, "message"); vec.bindLong(2, mid); vec.bindBlob(3, unitVector())
                            vec.executeInsert(); vectors++
                        }
                    }
                    db.execSQL("UPDATE conversation SET reflected_up_to_message_id = ? WHERE id = ?", arrayOf<Any>(lastMessage, cid))
                    doc.bindString(1, "summary"); doc.bindString(2, cid.toString()); doc.bindString(3, summary(topic, day))
                    doc.executeInsert(); docs++
                    vec.bindString(1, "summary"); vec.bindLong(2, cid); vec.bindBlob(3, unitVector())
                    vec.executeInsert(); vectors++

                    if (rnd.nextInt(5) == 0) {
                        val words = said(topic)
                        quote.bindLong(1, cid); quote.bindString(2, words); quote.bindString(3, day.toString())
                        val qid = quote.executeInsert()
                        doc.bindString(1, "quote"); doc.bindString(2, qid.toString()); doc.bindString(3, words)
                        doc.executeInsert(); docs++
                    }
                    if (rnd.nextInt(7) == 0) {
                        event.bindLong(1, cid); event.bindString(2, "that evening"); event.bindString(3, "$topic got in the way")
                        event.bindString(4, FEELINGS[rnd.nextInt(FEELINGS.size)]); event.bindString(5, "kept going")
                        event.bindString(6, "one block done"); event.bindLong(7, start)
                        val eid = event.executeInsert()
                        doc.bindString(1, "event"); doc.bindString(2, eid.toString())
                        doc.bindString(3, "that evening; $topic got in the way; kept going; one block done")
                        doc.executeInsert(); docs++
                    }
                    if (rnd.nextInt(5) == 0) {
                        val due = start + DAY
                        val status = if (due < now.toEpochMilli()) OUTCOMES[rnd.nextInt(OUTCOMES.size)] else "open"
                        val text = "${VERBS[rnd.nextInt(VERBS.size)]} $topic for ${15 + rnd.nextInt(4) * 15} minutes"
                        promise.bindString(1, text); promise.bindLong(2, due); promise.bindString(3, "tomorrow")
                        promise.bindString(4, status)
                        if (status == "not_done") promise.bindString(5, REASONS[rnd.nextInt(REASONS.size)]) else promise.bindNull(5)
                        promise.bindLong(6, cid); promise.bindLong(7, start)
                        if (status == "open") promise.bindNull(8) else promise.bindLong(8, due)
                        val pid = promise.executeInsert()
                        promises++
                        doc.bindString(1, "promise"); doc.bindString(2, pid.toString()); doc.bindString(3, "$text ($status)")
                        doc.executeInsert(); docs++
                    }
                }
                day = day.plusDays(1)
            }

            // The planner: two goals, three targets running the whole ten years, checked in on most days.
            db.execSQL("INSERT INTO goal (title, area, horizon, deadline, status, created_at) VALUES ('Finish the qualification', 'studies', 'custom', ?, 'active', ?)",
                arrayOf<Any>(lastDay.plusMonths(2).toString(), firstDay.atStartOfDay(zone).toInstant().toEpochMilli()))
            db.execSQL("INSERT INTO goal (title, area, horizon, deadline, status, created_at) VALUES ('Stay well', 'health', 'custom', NULL, 'active', ?)",
                arrayOf<Any>(firstDay.atStartOfDay(zone).toInstant().toEpochMilli()))
            db.execSQL("INSERT INTO backlog (goal_id, name, total, unit, start_done, deadline, status, created_at, start_day) VALUES (1, 'Lectures', 20000, 'lectures', 0, ?, 'active', ?, ?)",
                arrayOf<Any>(lastDay.plusMonths(2).toString(), firstDay.atStartOfDay(zone).toInstant().toEpochMilli(), firstDay.toString()))
            db.execSQL("INSERT INTO target (goal_id, backlog_id, name, unit, minimum, target, schedule, start_day, status) VALUES (1, 1, 'Lectures', 'lectures', 1, 2, 'Mon-Sat', ?, 'active')", arrayOf<Any>(firstDay.toString()))
            db.execSQL("INSERT INTO target (goal_id, name, unit, minimum, target, schedule, start_day, status) VALUES (2, 'Walk', 'minutes', 10, 20, 'daily', ?, 'active')", arrayOf<Any>(firstDay.toString()))
            db.execSQL("INSERT INTO target (goal_id, name, unit, minimum, target, schedule, start_day, status) VALUES (2, 'Sleep by midnight', '', 0, 1, 'daily', ?, 'active')", arrayOf<Any>(firstDay.toString()))
            val ck = db.compileStatement(
                "INSERT INTO checkin (target_id, day_key, level, amount, reason, note, entered_at) VALUES (?, ?, ?, ?, ?, '', ?)",
            )
            day = firstDay
            while (!day.isAfter(lastDay)) {
                for (t in 1L..3L) {
                    if (t == 1L && day.dayOfWeek.value == 7) continue
                    if (rnd.nextInt(10) == 0) continue // no answer: never a miss
                    val r = rnd.nextInt(10)
                    val level = when { r < 6 -> "target"; r < 8 -> "minimum"; else -> "not" }
                    ck.bindLong(1, t); ck.bindString(2, day.toString()); ck.bindString(3, level)
                    ck.bindDouble(4, when (level) { "target" -> if (t == 2L) 20.0 else if (t == 1L) 2.0 else 1.0; "minimum" -> if (t == 2L) 10.0 else 1.0; else -> 0.0 })
                    if (level == "target") ck.bindNull(5) else ck.bindString(5, REASONS[rnd.nextInt(REASONS.size)])
                    ck.bindLong(6, day.atStartOfDay(zone).toInstant().toEpochMilli() + 20 * HOUR)
                    ck.executeInsert()
                    checkins++
                }
                day = day.plusDays(1)
            }

            // What reflection and the writing jobs leave behind.
            val note = db.compileStatement(
                "INSERT INTO note (kind, text, confidence, status, times_seen, first_seen, last_seen, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            )
            val ev = db.compileStatement("INSERT INTO note_evidence (note_id, kind, text, day) VALUES (?, 'quote', ?, ?)")
            repeat(40 * years) { i ->
                val topic = TOPICS[rnd.nextInt(TOPICS.size)]
                val status = when { i % 10 == 0 -> "retired"; i % 25 == 0 -> "deleted_by_user"; else -> "active" }
                val first = firstDay.plusDays(rnd.nextLong(0, 365L * years))
                note.bindString(1, NOTE_KINDS[rnd.nextInt(NOTE_KINDS.size)]); note.bindString(2, "When $topic comes up, ${said(topic).lowercase()}")
                note.bindString(3, listOf("guess", "likely", "confirmed")[rnd.nextInt(3)]); note.bindString(4, status)
                note.bindLong(5, 1L + rnd.nextInt(9)); note.bindString(6, first.toString()); note.bindString(7, minOf(first.plusDays(90), lastDay).toString())
                note.bindLong(8, first.atStartOfDay(zone).toInstant().toEpochMilli())
                val nid = note.executeInsert()
                notes++
                repeat(3) { ev.bindLong(1, nid); ev.bindString(2, said(topic)); ev.bindString(3, first.toString()); ev.executeInsert() }
                if (status == "retired") {
                    doc.bindString(1, "note"); doc.bindString(2, nid.toString()); doc.bindString(3, "When $topic comes up")
                    doc.executeInsert(); docs++
                }
            }
            repeat(30) { i ->
                db.execSQL("INSERT INTO profile_entry (key, value, confirmed, updated_at) VALUES (?, ?, ?, ?)",
                    arrayOf<Any>("fact_$i", "A fact about life number $i, written in a sentence of ordinary length.", i % 2, now.toEpochMilli()))
            }
            repeat(60) { i ->
                db.execSQL("INSERT INTO person (name, relation, notes, updated_at) VALUES (?, ?, ?, ?)",
                    arrayOf<Any>("Person $i", "friend", "Met through ${TOPICS[i % TOPICS.size]}; talks about it often.", now.toEpochMilli() - i * DAY))
            }
            var week = firstDay
            while (week.isBefore(lastDay)) {
                val end = week.plusDays(6)
                db.execSQL("INSERT INTO letter (period_start, period_end, title, body, ready_at, key) VALUES (?, ?, ?, ?, ?, ?)",
                    arrayOf<Any>(week.toString(), end.toString(), "A week of ${TOPICS[rnd.nextInt(TOPICS.size)]}", LETTER, millis(end), "letter:week:$week"))
                db.execSQL("INSERT INTO review (kind, period_start, period_end, facts_json, narrative, key, created_at) VALUES ('week', ?, ?, '{}', '', ?, ?)",
                    arrayOf<Any>(week.toString(), end.toString(), "review:week:$week", millis(end)))
                week = week.plusDays(7)
            }
            var month = firstDay.withDayOfMonth(1)
            while (month.isBefore(lastDay)) {
                db.execSQL("INSERT INTO review (kind, period_start, period_end, facts_json, narrative, key, created_at) VALUES ('month', ?, ?, '{}', '', ?, ?)",
                    arrayOf<Any>(month.toString(), month.plusMonths(1).minusDays(1).toString(), "review:month:$month", millis(month.plusMonths(1))))
                if (month.monthValue % 3 == 1) {
                    db.execSQL("INSERT INTO chapter (period_start, period_end, title, body, created_at) VALUES (?, ?, ?, ?, ?)",
                        arrayOf<Any>(month.toString(), month.plusMonths(3).minusDays(1).toString(), "A season", LETTER, millis(month.plusMonths(3))))
                }
                month = month.plusMonths(1)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return Counts(conversations, messages, docs, vectors, checkins, promises, notes)
    }

    private fun millis(d: LocalDate) = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun unitVector(): ByteArray {
        val v = FloatArray(Embedder.DIM) { (rnd.nextDouble() * 2 - 1).toFloat() }
        val n = kotlin.math.sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        for (i in v.indices) v[i] /= n
        return VectorMath.toBytes(v)
    }

    private fun title(topic: String) = "${topic.replaceFirstChar { it.uppercase() }} ${TITLE_WORDS[rnd.nextInt(TITLE_WORDS.size)]}"

    private fun summary(topic: String, day: LocalDate) =
        "They talked about $topic on ${day.dayOfWeek.name.lowercase()}: ${said(topic).lowercase()} They agreed on one small step."

    private fun said(topic: String): String {
        val a = OPENERS[rnd.nextInt(OPENERS.size)]
        val b = MIDDLES[rnd.nextInt(MIDDLES.size)]
        val c = TOPICS[rnd.nextInt(TOPICS.size)]
        return "$a $topic $b $c."
    }

    private fun coach(topic: String) =
        "That makes sense. When $topic gets heavy, it helps to pick the smallest next step and do only that. " +
            "What would make the first ten minutes easy? You said before that mornings work better for you, so maybe " +
            "start there tomorrow and see how it feels. I'm here if it doesn't go to plan."

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
        val TOPICS = listOf(
            "tax lectures", "sleep", "the gym", "my knee", "money", "Ammi", "Hina", "work", "the exam", "past papers",
            "the router", "power cuts", "my phone", "the roof room", "Omar", "the library", "breakfast", "walking",
            "family visits", "the wedding", "savings", "the bus", "cooking", "reading", "prayer", "cricket", "my cousin",
            "the market", "headaches", "motivation", "the deadline", "the syllabus", "revision notes", "the tutor",
        )
        val OPENERS = listOf(
            "Today I kept thinking about", "Honestly I'm tired of", "I finally did something about", "I keep avoiding",
            "It went better than expected with", "I'm worried about", "Small win with", "I messed up again with",
            "Not sure what to do about", "Spent the evening on",
        )
        val MIDDLES = listOf(
            "and it reminded me of", "while I was stuck on", "even though I planned", "right after", "because of",
            "instead of", "and then", "before",
        )
        val TITLE_WORDS = listOf("again", "today", "plan", "check-in", "thoughts", "evening", "morning", "worries")
        val FEELINGS = listOf("tired", "anxious", "flat", "hopeful", "annoyed", "")
        val VERBS = listOf("Do", "Spend time on", "Finish", "Start", "Plan")
        val OUTCOMES = listOf("kept", "kept", "kept", "not_done", "moved", "let_go")
        val REASONS = listOf("tired", "power cut", "family", "forgot", "too hard", "ill")
        val NOTE_KINDS = listOf("pattern", "what_helps", "what_doesnt", "strength", "value", "thread")
        const val LETTER = "A steady week. The evenings were the hard part, and mornings carried most of the work. " +
            "The numbers below are the app's own; the words in quotes are yours."
    }
}
