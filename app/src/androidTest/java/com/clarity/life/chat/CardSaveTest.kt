package com.clarity.life.chat

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.clarity.life.ai.AnthropicClient
import com.clarity.life.ai.OpenAiCompatibleClient
import com.clarity.life.core.KnownIds
import com.clarity.life.data.ClarityDatabase
import com.clarity.life.data.entity.ConversationRow
import com.clarity.life.prompts.PromptStore
import com.clarity.life.security.SecretStore
import com.clarity.life.settings.SettingsRepository
import java.time.Instant
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stage C's promise: nothing saves without a tap. A reply full of cards is stored, and the promise, note
 * and focus tables stay empty; only a SaveCard command (what Set it, Keep it and Start it call) writes, once,
 * and the card's Saved receipt comes from the row it wrote.
 */
@RunWith(AndroidJUnit4::class)
class CardSaveTest {

    private lateinit var db: ClarityDatabase
    private lateinit var chat: ChatRepository
    private lateinit var saver: CardSaver
    private val now = System.currentTimeMillis()

    @Before
    fun open() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, ClarityDatabase::class.java)
            .addCallback(
                object : RoomDatabase.Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        db.execSQL("PRAGMA foreign_keys = ON")
                    }
                },
            )
            .build()
        val secrets = SecretStore(context)
        val settings = SettingsRepository(db.settings(), secrets)
        val http = OkHttpClient()
        val search = com.clarity.life.memory.SearchIndex(db, com.clarity.life.memory.TestEmbedder.shared)
        chat = ChatRepository(
            db = db,
            settings = settings,
            prompts = PromptStore(context, db.promptOverrides()),
            mainClient = OpenAiCompatibleClient(http),
            anthropicClient = AnthropicClient(http),
            search = search,
            planner = com.clarity.life.planner.PlannerRepository(db, settings),
        )
        saver = CardSaver(db, settings, search, com.clarity.life.planner.PlannerRepository(db, settings), com.clarity.life.promise.Reminders(context, db))
    }

    @After
    fun close() = db.close()

    private suspend fun count(table: String): Int =
        db.query("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getInt(0) }

    @Test
    fun cardsAreOnlyOffersUntilHeTaps() = runBlocking {
        val conv = db.conversations().insert(ConversationRow(startedAt = now))
        val reply = chat.openCoachReply(conv, now, null)
        val raw = """
            Then let's stop trusting tomorrow-night you with it.

            [[suggest: {"type":"mode","to":"decide"}]]
            [[suggest: {"type":"note","kind":"what_helps","text":"Mornings feel lighter for studying"}]]
            [[suggest: {"type":"focus","name":"Driving again","why":"He wants to drive on his own"}]]
        """.trimIndent()
        val outcome = chat.closeCoachReply(conv, reply, raw, "free", KnownIds(), "stop", null, now)
        assertEquals(3, outcome.cardsOffered)

        // The reply and its cards are stored. Nothing else is.
        assertEquals(0, count("note"))
        assertEquals(0, count("focus"))
        val cards = db.cards().recentOfConversation(conv, 9).sortedBy { it.id }
        assertTrue(cards.all { it.status == "offered" && it.savedRef == null })

        // Keep it: the note is his from the start, with no evidence row.
        val noteCard = cards.first { it.type == "note" }
        val note = saver.saveNote(noteCard.id, now) as CardSaver.Result.Saved
        val noteRow = db.memory().note(note.ref.substringAfter(':').toLong())!!
        assertEquals("confirmed", noteRow.confidence)
        assertEquals("what_helps", noteRow.kind)
        assertEquals(0, db.memory().evidenceOf(noteRow.id).size)

        // Not now on the focus card: dismissed, and nothing written; a tap after that saves nothing.
        val focusCard = cards.first { it.type == "focus" }
        assertTrue(saver.dismiss(focusCard.id, now))
        assertTrue(saver.saveFocus(focusCard.id, now) is CardSaver.Result.NotSaved)
        assertEquals(0, count("focus"))
        assertEquals("dismissed", db.cards().get(focusCard.id)!!.status)

        // Forgetting the conversation keeps the note he chose to keep.
        chat.forgetConversation(conv)
        assertEquals(1, count("note"))
    }

    @Test
    fun aModeCardSwitchesTheConversationOnlyOnTap() = runBlocking {
        val conv = db.conversations().insert(ConversationRow(startedAt = now, chatType = "free"))
        val reply = chat.openCoachReply(conv, now, null)
        chat.closeCoachReply(
            conv, reply, "Ok.\n[[suggest: {\"type\":\"mode\",\"to\":\"plan\"}]]", "free", KnownIds(), "stop", null, now,
        )
        assertEquals("free", db.conversations().get(conv)!!.chatType)
        val card = db.cards().recentOfConversation(conv, 9).single()
        assertEquals(CardSaver.Result.Saved("mode:plan"), saver.switchMode(card.id, now))
        assertEquals("plan", db.conversations().get(conv)!!.chatType)
    }

    @Test
    fun aCardAnsweredUnderAnOlderReplyIsReportedToTheCoach() = runBlocking {
        val conv = db.conversations().insert(ConversationRow(startedAt = now))
        val first = chat.openCoachReply(conv, now, null)
        val raw = "Worth keeping.\n\n[[suggest: {\"type\":\"note\",\"kind\":\"what_helps\",\"text\":\"Mornings feel lighter\"}]]"
        chat.closeCoachReply(conv, first, raw, "free", KnownIds(), "stop", null, now)
        chat.addHisMessage(conv, "ok", now + 500)
        val second = chat.openCoachReply(conv, now + 1_000, null)
        chat.closeCoachReply(conv, second, "Good.", "free", KnownIds(), "stop", null, now + 1_000)

        // He scrolls up and taps Keep it on the first reply's card, after the second reply came.
        val card = db.cards().recentOfConversation(conv, 9).single()
        saver.saveNote(card.id, now + 2_000)

        chat.addHisMessage(conv, "what next", now + 3_000)
        val packet = chat.buildPacket(conv, "what next", now + 3_000)
        val turn = packet.request.messages.last().content
        assertTrue(turn, turn.contains("note \"Mornings feel lighter\": saved (a card under one of your earlier replies, answered since)"))
    }
}

