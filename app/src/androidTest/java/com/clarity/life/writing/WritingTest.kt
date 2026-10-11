package com.clarity.life.writing

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.clarity.life.ai.AnthropicClient
import com.clarity.life.ai.OpenAiCompatibleClient
import com.clarity.life.chat.ChatRepository
import com.clarity.life.core.ReviewFacts
import com.clarity.life.core.ReviewTimes
import com.clarity.life.data.ClarityDatabase
import com.clarity.life.data.entity.LetterRow
import com.clarity.life.planner.PlannerRepository
import com.clarity.life.prompts.PromptStore
import com.clarity.life.security.SecretStore
import com.clarity.life.settings.SettingsRepository
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Reviews and letters on a real database, without a model: what is written, once, and what is never written. */
@RunWith(AndroidJUnit4::class)
class WritingTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: ClarityDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var planner: PlannerRepository
    private lateinit var reviews: ReviewWriter
    private lateinit var letters: LetterWriter
    private lateinit var chat: ChatRepository
    private val now = System.currentTimeMillis()
    private val monday = LocalDate.parse("2026-10-05")

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(context, ClarityDatabase::class.java).build()
        settings = SettingsRepository(db.settings(), SecretStore(context))
        planner = PlannerRepository(db, settings)
        val http = OkHttpClient()
        val prompts = PromptStore(context, db.promptOverrides())
        val call = BackgroundCall(db, OpenAiCompatibleClient(http), AnthropicClient(http))
        reviews = ReviewWriter(db, settings, prompts, call, JobClaims(db), planner)
        letters = LetterWriter(context, db, settings, prompts, call, JobClaims(db), planner, reviews)
        chat = ChatRepository(
            db = db, settings = settings, prompts = prompts, mainClient = OpenAiCompatibleClient(http),
            anthropicClient = AnthropicClient(http),
            search = com.clarity.life.memory.SearchIndex(db, com.clarity.life.memory.Embedder(context)),
            planner = planner,
        )
    }

    @After
    fun close() = db.close()

    @Test
    fun aWeekHasOneReviewWithItsFactsCountedInCode() = runBlocking {
        val g = planner.createGoal("Lectures", "studies", "custom", null, now)
        val t = planner.addTarget(g, null, "Lectures", "lectures", 1.0, 2.0, "daily", monday, null)
        planner.checkIn(t, monday, monday, "target", 2.0, null, null, now)
        planner.checkIn(t, monday.plusDays(1), monday.plusDays(1), "not", 0.0, "tired", null, now)
        val first = reviews.ensure("week", monday, monday.plusDays(6), now)!!
        val again = reviews.ensure("week", monday, monday.plusDays(6), now)!!
        assertEquals(first.id, again.id)
        assertEquals(ReviewTimes.weekKey(monday), first.key)
        val facts = ReviewFacts.fromJson(first.factsJson)!!
        assertEquals(2, facts.answered)
        assertTrue(facts.lines.toString(), facts.lines.contains("T$t \"Lectures\": answered 2 of 7 scheduled days, done on 1, Not on 1."))
        // no API key: no call is made, and the review stays with its numbers
        assertEquals(ReviewWriter.Outcome.NothingToDo, reviews.writeNote(first.id, now))
        assertEquals("", db.writing().review(first.id)!!.narrative)
    }

    @Test
    fun aWeekWithNoAnswersMakesNoCall() = runBlocking {
        val today = planner.today(now)
        val thisWeek = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        val lastWeek = thisWeek.minusWeeks(1)
        val g = planner.createGoal("Walk", "health", "custom", null, now)
        planner.addTarget(g, null, "Walk", "", 0.0, 1.0, "daily", lastWeek, null)
        // A week whose last day has ended: nothing to say, settled for good.
        val done = reviews.ensure("week", lastWeek, lastWeek.plusDays(6), now)!!
        assertEquals(ReviewWriter.Outcome.NothingToDo, reviews.writeNote(done.id, now))
        assertEquals("skipped", db.jobs().byKey("review_week:$lastWeek")!!.status)
        // The week still running: no call yet, but not settled (a late check-in may still come).
        val open = reviews.ensure("week", thisWeek, thisWeek.plusDays(6), now)!!
        assertEquals(ReviewWriter.Outcome.NothingToDo, reviews.writeNote(open.id, now))
        assertEquals(null, db.jobs().byKey("review_week:$thisWeek"))
    }

    @Test
    fun aDeletedLetterIsNeverWrittenAgain() = runBlocking {
        val key = ReviewTimes.letterKey(monday)
        val id = db.writing().insertLetter(
            LetterRow(periodStart = monday.toString(), periodEnd = monday.plusDays(6).toString(), title = "T", body = "B", readyAt = now, key = key),
        )
        letters.delete(id, now)
        val row = db.writing().letterByKey(key)!!
        assertEquals("", row.title)
        assertEquals("", row.body)
        assertEquals(LetterWriter.Outcome.NothingToDo, letters.write(monday, now))
    }

    @Test
    fun talkAboutThisLetterPutsTheLetterInFrontOfTheCoach() = runBlocking {
        val id = db.writing().insertLetter(
            LetterRow(
                periodStart = monday.toString(), periodEnd = monday.plusDays(6).toString(), title = "The evening kept winning",
                body = "This week was about the hour.", readyAt = now, key = ReviewTimes.letterKey(monday),
            ),
        )
        val conv = chat.startConversation("free", null, now, "letter", id)
        val packet = chat.buildPacket(conv, "about the letter", now)
        assertTrue(packet.prefix.contains("## LETTER\nTitle: The evening kept winning\nThis week was about the hour."))
        // an ordinary conversation has no LETTER block
        val other = chat.startConversation("free", null, now)
        assertTrue(!chat.buildPacket(other, "hi", now).prefix.contains("## LETTER"))
    }
}
