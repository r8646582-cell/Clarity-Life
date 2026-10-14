package com.clarity.life.memory

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.clarity.life.data.ClarityDatabase
import com.clarity.life.data.entity.ConversationRow
import com.clarity.life.data.entity.MessageRow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The stage B promise, on a real SQLite with FTS4: what he said in one conversation is found from the next
 * one (RELEVANT_PAST), never from the same one, is gone after Forget, and comes back from a rebuild.
 */
@RunWith(AndroidJUnit4::class)
class SearchIndexTest {

    private lateinit var db: ClarityDatabase
    private lateinit var search: SearchIndex
    private val now = 1_791_648_000_000L
    private val tz = "Asia/Karachi"

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, ClarityDatabase::class.java)
            .build()
        search = SearchIndex(db, Embedder(InstrumentationRegistry.getInstrumentation().targetContext))
    }

    @After
    fun close() = db.close()

    private suspend fun said(conversation: Long, text: String): Long {
        val id = db.messages().insert(MessageRow(conversationId = conversation, role = "user", content = text, createdAt = now))
        search.indexHisMessage(id, text)
        return id
    }

    @Test
    fun aFactFromChatOneIsFoundFromChatTwo() = runBlocking {
        val one = db.conversations().insert(ConversationRow(startedAt = now, title = "The exam"))
        val two = db.conversations().insert(ConversationRow(startedAt = now + 1, title = ""))
        said(one, "my sister Hina helps me revise chemistry on sundays")

        val fromTwo = search.relevantPast(two, "should I ask Hina about chemistry again?", tz)
        assertEquals(1, fromTwo.items.size)
        assertTrue(fromTwo.items[0].text, fromTwo.items[0].text.contains("in \"The exam\", they wrote: my sister Hina"))

        val fromOne = search.relevantPast(one, "should I ask Hina about chemistry again?", tz)
        assertTrue("its own conversation is already in the history", fromOne.items.isEmpty())

        val weak = search.relevantPast(two, "chemistry is boring", tz)
        assertTrue("one shared word out of two is not strong enough", weak.items.isEmpty())
    }

    @Test
    fun shortWordsAndOtherFormsAreFoundAndTheOpenChatDoesNotCrowdThemOut() = runBlocking {
        val one = db.conversations().insert(ConversationRow(startedAt = now, title = "Power cut"))
        val two = db.conversations().insert(ConversationRow(startedAt = now + 1, title = ""))
        said(one, "I take a nap now and do one CAF lecture at 11")
        // The open conversation says the same words a hundred and fifty times: none of that may push chat one
        // out of the candidates.
        repeat(150) { said(two, "caf lectures and naps again") }

        val found = search.relevantPast(two, "how did the CAF lectures go after my naps?", tz)
        assertEquals(found.items.toString(), 1, found.items.size)
        assertTrue(found.items[0].text, found.items[0].text.contains("in \"Power cut\", they wrote: I take a nap"))
    }

    @Test
    fun quotesEventsAndRetiredNotesAreFoundToo() = runBlocking {
        val one = db.conversations().insert(ConversationRow(startedAt = now, title = "Work"))
        val two = db.conversations().insert(ConversationRow(startedAt = now + 1))
        val quote = "i'd rather be bad at something than invisible at the office"
        val quoteId = db.memory().insertQuote(com.clarity.life.data.entity.QuoteRow(conversationId = one, text = quote, saidDay = "2026-10-01"))
        search.indexQuote(quoteId, quote)
        val noteId = db.memory().insertNote(
            com.clarity.life.data.entity.NoteRow(
                kind = "pattern", text = "Your manager Orlo leaves you the late surveying work", status = "retired",
                firstSeen = "2026-09-01", lastSeen = "2026-09-20", createdAt = now,
            ),
        )
        search.indexRetiredNote(noteId, "Your manager Orlo leaves you the late surveying work")

        val past = search.relevantPast(two, "why does my manager leave the surveying work to me?", tz)
        assertTrue("a retired note is found: ${past.items}", past.items.any { it.text.contains("An older note") })
        val words = search.relevantPast(two, "would I rather be invisible at the office?", tz)
        assertTrue("a quote is found: ${words.items}", words.items.any { it.text.contains("words of theirs worth keeping") })
        assertTrue("never from its own conversation", search.relevantPast(one, "invisible at the office", tz).items.isEmpty())

        search.removeNote(noteId)
        assertTrue(search.relevantPast(two, "manager surveying work", tz).items.none { it.text.contains("Orlo") })
    }

    @Test
    fun forgetRemovesItAndRebuildRestoresWhatExists() = runBlocking {
        val one = db.conversations().insert(ConversationRow(startedAt = now, title = "One"))
        val two = db.conversations().insert(ConversationRow(startedAt = now, title = "Two"))
        said(one, "the internship decision keeps me awake")
        search.indexSummary(one, "He talked about the internship decision.")
        said(two, "nothing about that here")

        search.forget(one)
        db.conversations().delete(one)
        assertTrue(search.relevantPast(two, "internship decision", tz).items.isEmpty())

        db.search().clear()
        assertEquals("only conversation two is left to index", 1, search.rebuild())
        search.rebuildIfEmpty()
        assertEquals(1, db.search().count())
    }

    private fun at(day: String, hour: Int = 12): Long =
        java.time.LocalDate.parse(day).atTime(hour, 0).atZone(java.time.ZoneId.of(tz)).toInstant().toEpochMilli()

    @Test
    fun lettersAndCheckinsAreSearchableAndADeletedLetterLeavesAtOnce() = runBlocking {
        val two = db.conversations().insert(ConversationRow(startedAt = now))
        val letter = db.writing().insertLetter(
            com.clarity.life.data.entity.LetterRow(
                periodStart = "2026-09-28", periodEnd = "2026-10-04", title = "A box that keeps the router on",
                body = "The UPS changed the evenings: two lectures during a cut.", readyAt = now, key = "letter:week:2026-09-28",
            ),
        )
        search.syncRecords(force = true)
        val found = search.relevantPast(two, "which letter talked about the UPS?", tz, 0, now)
        assertTrue(found.items.toString(), found.items.any { it.text.contains("their weekly letter") && it.text.contains("router") })

        db.writing().deleteLetter(letter, now)
        search.syncRecords(force = true)
        val after = search.relevantPast(two, "which letter talked about the UPS?", tz, 0, now)
        assertTrue("a deleted letter is never sent again: ${after.items}", after.items.none { it.text.contains("router") })
    }

    @Test
    fun promisesInAMonthComeBackWithTheBrokenOnesFirst() = runBlocking {
        val two = db.conversations().insert(ConversationRow(startedAt = now))
        fun promise(text: String, day: String, status: String, reason: String? = null) =
            com.clarity.life.data.entity.PromiseRow(text = text, dueAt = at(day), dueText = day, status = status, reason = reason, createdAt = at(day))
        db.promises().insert(promise("Submit the exam form", "2026-08-21", "not_done", "power cut"))
        db.promises().insert(promise("Walk after Asr", "2026-08-10", "kept"))
        db.promises().insert(promise("Buy the router UPS", "2026-09-25", "kept"))
        search.rebuild()

        // No word of the question is in these promises: the month and the outcome are read in code.
        val got = search.relevantPast(two, "Which promises did I break in August?", tz, 0, now)
        val texts = got.items.map { it.text }
        // (The broken one first among them is pinned by contracts/retrieval_vectors.json.)
        assertTrue(texts.toString(), texts.any { it.contains("Submit the exam form") && it.contains("not done") })
        assertTrue(texts.toString(), texts.any { it.contains("Walk after Asr") })
    }

    @Test
    fun aVagueFollowUpIsSearchedWithTheConversation() = runBlocking {
        val one = db.conversations().insert(ConversationRow(startedAt = now - 86_400_000L * 20, title = "The UPS"))
        val two = db.conversations().insert(ConversationRow(startedAt = now))
        said(one, "Got the router UPS for 9,500, it keeps the internet on for three hours")

        val withContext = search.relevantPast(
            two, "How much was it again?", tz, 0, now, earlier = listOf("I bought the router UPS last month."),
        )
        assertTrue(withContext.items.toString(), withContext.items.any { it.text.contains("9,500") })
    }
}
