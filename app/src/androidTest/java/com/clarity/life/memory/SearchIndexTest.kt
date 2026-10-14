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
}
