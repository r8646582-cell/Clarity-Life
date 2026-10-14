package com.clarity.life.memory

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.clarity.life.core.ReflectionApplier
import com.clarity.life.core.ReflectionContext
import com.clarity.life.data.ClarityDatabase
import com.clarity.life.data.entity.ConversationRow
import com.clarity.life.data.entity.JobRow
import com.clarity.life.data.entity.MessageRow
import com.clarity.life.data.entity.NoteEvidenceRow
import com.clarity.life.data.entity.NoteRow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A validated reflection really lands in the database the way the rules say, in one transaction:
 * a new note starts as a guess with its evidence, his edited note keeps its text, a replay changes nothing,
 * and forgetting the conversation takes away what was learned only from it.
 */
@RunWith(AndroidJUnit4::class)
class ReflectionApplyTest {

    private lateinit var db: ClarityDatabase
    private lateinit var memory: MemoryRepository
    private val now = 1_791_648_000_000L
    private val day = "2026-10-10"
    private val said = "honestly i only study well in the morning, evenings are dead for me"

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
        memory = MemoryRepository(db, SearchIndex(db, Embedder(context)))
    }

    @After
    fun close() = db.close()

    private suspend fun claim(key: String): JobRow {
        db.jobs().insertIfNew(JobRow(key = key, type = "reflection", createdAt = now))
        val row = db.jobs().byKey(key)!!.copy(status = "running", attempts = 1, startedAt = now)
        db.jobs().setState(row.id, row.status, row.attempts, null, row.startedAt, null)
        return row
    }

    @Test
    fun aReflectionIsAppliedOnceAndForgetTakesItBack() = runBlocking {
        val conv = db.conversations().insert(ConversationRow(startedAt = now, title = said.take(40)))
        val msg = db.messages().insert(
            MessageRow(conversationId = conv, role = "user", content = said, createdAt = now),
        )
        // A note he edited earlier: a job may add evidence to it but never touch its text.
        val his = db.memory().insertNote(
            NoteRow(
                kind = "what_helps", text = "Mornings work for you", confidence = "confirmed",
                firstSeen = "2026-10-01", lastSeen = "2026-10-01", editedByUser = 1, createdAt = now,
            ),
        )
        db.memory().insertEvidence(NoteEvidenceRow(noteId = his, kind = "quote", text = "mornings are best", day = "2026-10-01"))

        val raw = """
            {"title": "Mornings and the exam", "summary": "He said evenings do not work.", "significance": 3,
             "tone": "tired",
             "notes": {
               "add": [{"kind": "pattern", "text": "Evenings do not work for studying", "confidence": "likely",
                        "evidence": [{"kind": "quote", "text": "evenings are dead for me"}]}],
               "update": [{"id": $his, "text": "Rewritten by the model", "confidence": "confirmed",
                           "evidence": [{"kind": "quote", "text": "i only study well in the morning"}]}]
             },
             "his_words": ["evenings are dead for me"],
             "ideas_used": ["if-then plan"]}
        """.trimIndent()
        val notes = db.memory().allNotes().associate {
            it.id to com.clarity.life.core.NoteFacts(it.kind, it.confidence, it.status, it.editedByUser == 1, it.text, it.timesSeen)
        }
        val result = ReflectionApplier.apply(raw, ReflectionContext(hisMessages = listOf(said), notes = notes))
        assertEquals(true, result.ok)

        // Another open conversation, with its memory frozen before this summary existed; and this one's own.
        val other = db.conversations().insert(ConversationRow(startedAt = now))
        db.conversations().setPrefix(other, "{\"system\":\"before\"}", day)
        db.conversations().setPrefix(conv, "{\"system\":\"mine\"}", day)

        val job = claim("reflection:$conv:$msg")
        val applied = memory.applyReflection(
            conv, msg, job, result.ops, listOf(MemoryRepository.Said(said, day)), day, now,
        )
        assertNotNull(applied)

        val added = db.memory().allNotes().single { it.id != his }
        assertEquals("guess", added.confidence)
        assertEquals(day, added.firstSeen)
        assertEquals(listOf("evenings are dead for me"), db.memory().evidenceOf(added.id).map { it.text })
        val kept = db.memory().note(his)!!
        assertEquals("Mornings work for you", kept.text)
        assertEquals("confirmed", kept.confidence)
        assertEquals(2, db.memory().evidenceOf(his).size)
        assertEquals(1, db.memory().quoteCount("evenings are dead for me"))
        assertEquals("if-then plan", db.memory().recentItems("idea", 20).single().text)
        val c = db.conversations().get(conv)!!
        assertEquals(msg, c.reflectedUpToMessageId)
        assertEquals("the placeholder title is replaced", "Mornings and the exam", c.title)
        assertEquals("succeeded", db.jobs().byKey("reflection:$conv:$msg")!!.status)
        assertNull("the other conversation builds its memory again, with the new summary", db.conversations().get(other)!!.prefixJson)
        assertNotNull("the reflected one keeps its cache: its messages are in its own history", c.prefixJson)

        // The same work again changes nothing.
        assertNull(memory.applyReflection(conv, msg, job, result.ops, emptyList(), day, now))
        assertEquals(2, db.memory().allNotes().size)

        // Forget: the conversation goes, the note learned only from it goes, his own note stays.
        val chatForget = db.memory().notesWithEvidenceFrom(conv)
        db.conversations().delete(conv)
        db.memory().deleteNotesLeftWithoutEvidence(chatForget)
        assertEquals(listOf(his), db.memory().allNotes().map { it.id })
        assertEquals(0, db.memory().quoteCount("evenings are dead for me"))
        assertEquals(0, db.memory().recentItems("idea", 20).size)
    }

    @Test
    fun aPlaceholderIsTheOnlyTitleAReflectionReplaces() = runBlocking {
        val conv = db.conversations().insert(ConversationRow(startedAt = now, title = "My exam talk"))
        val msg = db.messages().insert(
            MessageRow(conversationId = conv, role = "user", content = said, createdAt = now),
        )
        val result = ReflectionApplier.apply(
            """{"title": "Mornings", "summary": "Short.", "significance": 1, "tone": "calm"}""",
            ReflectionContext(hisMessages = listOf(said)),
        )
        memory.applyReflection(conv, msg, claim("reflection:$conv:$msg"), result.ops, emptyList(), day, now)
        assertEquals("a title he typed stays", "My exam talk", db.conversations().get(conv)!!.title)
    }
}
