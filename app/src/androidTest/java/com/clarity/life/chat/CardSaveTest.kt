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

            [[suggest: {"type":"promise","text":"One lecture, phone in the other room","when":"tomorrow 15:00","remind":true}]]
            [[suggest: {"type":"note","kind":"what_helps","text":"Mornings feel lighter for studying"}]]
            [[suggest: {"type":"focus","name":"Driving again","why":"He wants to drive on his own"}]]
        """.trimIndent()
        val outcome = chat.closeCoachReply(conv, reply, raw, "free", KnownIds(), "stop", null, now)
        assertEquals(3, outcome.cardsOffered)

        // The reply and its cards are stored. Nothing else is.
        assertEquals(0, count("promise"))
        assertEquals(0, count("note"))
        assertEquals(0, count("focus"))
        val cards = db.cards().recentOfConversation(conv, 9).sortedBy { it.id }
        assertTrue(cards.all { it.status == "offered" && it.savedRef == null })

        // Set it: one promise, at the moment he saw, and the card names the row it wrote.
        val promiseCard = cards.first { it.type == "promise" }
        val at = Instant.ofEpochMilli(now + 86_400_000L)
        val saved = saver.savePromise(promiseCard.id, at, now)
        assertTrue(saved is CardSaver.Result.Saved)
        val ref = (saved as CardSaver.Result.Saved).ref
        assertEquals(1, count("promise"))
        val row = db.promises().get(ref.substringAfter(':').toLong())!!
        assertEquals("One lecture, phone in the other room", row.text)
        assertEquals(at.toEpochMilli(), row.dueAt)
        assertEquals(1, row.remind)
        assertEquals("open", row.status)
        assertEquals(conv, row.sourceConversationId)
        val after = db.cards().get(promiseCard.id)!!
        assertEquals("saved", after.status)
        assertEquals(ref, after.savedRef)
        assertTrue(saver.receipt(after)!!.startsWith("One lecture, phone in the other room · "))

        // A second tap saves nothing more.
        assertTrue(saver.savePromise(promiseCard.id, at, now) is CardSaver.Result.NotSaved)
        assertEquals(1, count("promise"))

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

        // Forgetting the conversation keeps the promise and the note he chose to keep.
        chat.forgetConversation(conv)
        assertEquals(1, count("promise"))
        assertEquals(1, count("note"))
    }

    @Test
    fun aPromiseIsNeverSavedInThePast() = runBlocking {
        val conv = db.conversations().insert(ConversationRow(startedAt = now))
        val reply = chat.openCoachReply(conv, now, null)
        chat.closeCoachReply(
            conv, reply,
            "Ok.\n[[suggest: {\"type\":\"promise\",\"text\":\"Walk 20 minutes\",\"when\":\"today 07:00\"}]]",
            "free", KnownIds(), "stop", null, now,
        )
        val card = db.cards().recentOfConversation(conv, 9).single()
        val result = saver.savePromise(card.id, Instant.ofEpochMilli(now - 60_000), now)
        assertEquals(CardSaver.Result.NotSaved("past"), result)
        assertEquals(0, count("promise"))
        assertEquals("offered", db.cards().get(card.id)!!.status)
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
    fun aPlanCardSavesExactlyWhatItShowedAndOnlyOnTap() = runBlocking {
        val planner = com.clarity.life.planner.PlannerRepository(db, SettingsRepository(db.settings(), SecretStore(InstrumentationRegistry.getInstrumentation().targetContext)))
        val today = planner.today(now)
        val goal = planner.createGoal("Clear the lectures", "studies", "custom", today.plusDays(40), now)
        val conv = chat.startConversation("plan", null, now)
        chat.addHisMessage(conv, "help me plan the lectures", now)
        val packet = chat.buildPacket(conv, "help me plan the lectures", now)
        val turn = packet.request.messages.last().content
        assertTrue(turn, turn.contains("## PLAN_STATE") && turn.contains("Goal G$goal \"Clear the lectures\""))
        assertTrue(packet.known.goals == setOf("G$goal"))
        val review = packet.known.reviewOptions.first()

        val reply = chat.openCoachReply(conv, now, null)
        val raw = "Here is a first plan.\n" +
            "[[suggest: {\"type\":\"plan\",\"title\":\"Clear the lectures\",\"goal\":\"G$goal\",\"rationale\":\"Two a day on good days\"," +
            "\"targets\":[{\"name\":\"Lectures\",\"unit\":\"lectures\",\"min\":1,\"target\":2,\"schedule\":\"daily\"}]," +
            "\"backlog\":[{\"name\":\"Lectures\",\"total\":40,\"unit\":\"lectures\",\"deadline\":\"${today.plusDays(40)}\"}]," +
            "\"review_date\":\"$review\"}]]"
        assertEquals(1, chat.closeCoachReply(conv, reply, raw, "plan", packet.known, "stop", null, now).cardsOffered)
        assertEquals(0, count("target"))
        assertEquals(0, count("plan"))

        val card = db.cards().recentOfConversation(conv, 9).single()
        val preview = saver.planPreview(card, now) as CardSaver.Preview.Ready
        assertTrue(preview.lines.toString(), preview.lines.any { it.startsWith("New backlog: Lectures, 40 lectures by") })
        assertTrue(preview.lines.toString(), preview.lines.any { it.startsWith("New target: Lectures, minimum 1, target 2 lectures, daily") })

        val saved = saver.savePlanCard(card.id, now) as CardSaver.Result.Saved
        assertTrue(saved.ref.startsWith("plan:"))
        assertEquals(1, count("plan"))
        assertEquals(1, count("target"))
        assertEquals(1, count("backlog"))
        assertTrue(saver.receipt(db.cards().get(card.id)!!)!!.startsWith("Plan v1 for Clear the lectures"))
        // A second tap writes nothing more.
        assertTrue(saver.savePlanCard(card.id, now) is CardSaver.Result.NotSaved)
        assertEquals(1, count("target"))

        // The next message already sees the saved plan.
        val next = chat.buildPacket(conv, "ok", now).request.messages.last().content
        assertTrue(next, next.contains("Target T") && next.contains("\"Lectures\" (G$goal): minimum 1, target 2, daily."))
    }

    @Test
    fun aPlanCardForAGoalArchivedSinceSavesNothing() = runBlocking {
        val planner = com.clarity.life.planner.PlannerRepository(db, SettingsRepository(db.settings(), SecretStore(InstrumentationRegistry.getInstrumentation().targetContext)))
        val goal = planner.createGoal("Walk more", "health", "custom", null, now)
        val conv = chat.startConversation("plan", null, now)
        val packet = chat.buildPacket(conv, "plan", now)
        val reply = chat.openCoachReply(conv, now, null)
        val raw = "Ok.\n[[suggest: {\"type\":\"plan\",\"title\":\"Walk\",\"goal\":\"G$goal\",\"rationale\":\"r\"," +
            "\"targets\":[{\"name\":\"Walk\",\"target\":1,\"schedule\":\"daily\"}],\"review_date\":\"${packet.known.reviewOptions.first()}\"}]]"
        chat.closeCoachReply(conv, reply, raw, "plan", packet.known, "stop", null, now)
        planner.setGoalStatus(goal, "archived")
        val card = db.cards().recentOfConversation(conv, 9).single()
        assertTrue(saver.savePlanCard(card.id, now) is CardSaver.Result.NotSaved)
        assertEquals(0, count("target"))
        assertEquals("offered", db.cards().get(card.id)!!.status)
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

