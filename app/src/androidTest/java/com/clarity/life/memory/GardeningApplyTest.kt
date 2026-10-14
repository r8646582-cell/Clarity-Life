package com.clarity.life.memory

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.clarity.life.core.GardeningApplier
import com.clarity.life.core.GardeningContext
import com.clarity.life.core.NoteFacts
import com.clarity.life.data.ClarityDatabase
import com.clarity.life.data.entity.JobRow
import com.clarity.life.data.entity.NoteEvidenceRow
import com.clarity.life.data.entity.NoteRow
import com.clarity.life.data.entity.PersonRow
import com.clarity.life.data.entity.ProfileEntryRow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A validated gardening result lands the way gardening.md and rule 5 say, in one transaction: a merge keeps
 * the lowest id with every piece of evidence and retires the rest, a note he edited meanwhile is left alone,
 * nothing comes back close to a note he deleted, people merge under one name, and a replay changes nothing.
 */
@RunWith(AndroidJUnit4::class)
class GardeningApplyTest {

    private lateinit var db: ClarityDatabase
    private lateinit var memory: MemoryRepository
    private val now = 1_791_648_000_000L

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
        val embedder = Embedder(context)
        memory = MemoryRepository(db, SearchIndex(db, embedder), NoteVectors(embedder))
    }

    @After
    fun close() = db.close()

    private suspend fun claim(key: String): JobRow {
        db.jobs().insertIfNew(JobRow(key = key, type = "gardening", createdAt = now))
        val row = db.jobs().byKey(key)!!.copy(status = "running", attempts = 1, startedAt = now)
        db.jobs().setState(row.id, row.status, row.attempts, null, row.startedAt, null)
        return row
    }

    private suspend fun note(text: String, seen: Int, first: String, last: String, confidence: String = "guess") =
        db.memory().insertNote(
            NoteRow(
                kind = "pattern", text = text, confidence = confidence, timesSeen = seen,
                firstSeen = first, lastSeen = last, createdAt = now,
            ),
        )

    private suspend fun context(): GardeningContext = GardeningContext(
        notes = db.memory().allNotes().associate {
            it.id to NoteFacts(it.kind, it.confidence, it.status, it.editedByUser == 1, it.text, it.timesSeen)
        },
        people = db.memory().people().map { it.id }.toSet(),
        profile = db.memory().profile().associate { it.key to (it.confirmed == 1) },
    )

    @Test
    fun aMergeKeepsEveryPieceOfEvidenceAndAReplayChangesNothing() = runBlocking {
        val a = note("You open your phone first thing", 2, "2026-07-01", "2026-08-01")
        val b = note("Your phone comes first in the morning", 3, "2026-06-01", "2026-09-01", "likely")
        db.memory().insertEvidence(NoteEvidenceRow(noteId = a, kind = "quote", text = "phone first again", day = "2026-08-01"))
        db.memory().insertEvidence(NoteEvidenceRow(noteId = b, kind = "quote", text = "scrolled in bed", day = "2026-09-01"))
        val father = db.memory().insertPerson(PersonRow(name = "Father", relation = "father", updatedAt = now))
        val dad = db.memory().insertPerson(PersonRow(name = "Dad", updatedAt = now))
        db.memory().putProfile(ProfileEntryRow("study_place", "library", confirmed = 1, updatedAt = now))

        val raw = """
            {"merge": [{"ids": [$a, $b], "text": "You reach for your phone first thing in the morning",
                        "kind": "pattern", "confidence": "likely"}],
             "merge_people": [{"ids": [$father, $dad], "name": "Father", "relation": "father", "notes": ""}],
             "rewrite_profile": [{"key": "study_place", "value": "home"}]}
        """.trimIndent()
        val result = GardeningApplier.apply(raw, context())
        assertTrue(result.ok)
        val job = claim("gardening:2026-10")
        val applied = memory.applyGardening(job, result.ops, now)
        assertNotNull(applied)

        val survivor = db.memory().note(a)!!
        assertEquals("You reach for your phone first thing in the morning", survivor.text)
        assertEquals("likely", survivor.confidence)
        assertEquals(5, survivor.timesSeen)
        assertEquals("2026-06-01", survivor.firstSeen)
        assertEquals("2026-09-01", survivor.lastSeen)
        assertEquals(setOf("phone first again", "scrolled in bed"), db.memory().evidenceOf(a).map { it.text }.toSet())
        assertEquals("retired", db.memory().note(b)!!.status)
        assertEquals(listOf("Father"), db.memory().people().map { it.name })
        // A confirmed profile entry is his: gardening never rewrites it.
        assertEquals("library", db.memory().profileEntry("study_place")!!.value)
        assertEquals("succeeded", db.jobs().byKey("gardening:2026-10")!!.status)

        // The same result again (a second run that read the job before it finished) changes nothing.
        assertNull(memory.applyGardening(job, result.ops, now))
    }

    @Test
    fun hisEditsAndDeletionsWinOverAJobThatStartedBefore() = runBlocking {
        val a = note("You study best after a walk", 1, "2026-09-01", "2026-09-01")
        val b = note("Walking first helps you study", 1, "2026-09-02", "2026-09-02")
        val c = note("You skip lunch on exam days", 1, "2026-09-03", "2026-09-03")
        val gone = note("You hate your cousin", 1, "2026-09-04", "2026-09-04")
        val ctx = context()
        // While the model was writing, he edited one note and deleted another.
        memory.editNote(b, "A short walk helps me study")
        memory.deleteNote(gone)

        val raw = """
            {"merge": [{"ids": [$a, $b], "text": "A walk before studying helps you", "kind": "pattern",
                        "confidence": "guess"}],
             "rewrite": [{"id": $c, "text": "You hate your cousin"}]}
        """.trimIndent()
        val result = GardeningApplier.apply(raw, ctx)
        assertTrue(result.ok)
        val applied = memory.applyGardening(claim("gardening:2026-10"), result.ops, now)!!
        assertEquals(0, applied.applied)
        assertEquals("You study best after a walk", db.memory().note(a)!!.text)
        assertEquals("active", db.memory().note(a)!!.status)
        assertEquals("A short walk helps me study", db.memory().note(b)!!.text)
        // The rewrite would have brought back the note he deleted, so it is refused.
        assertEquals("You skip lunch on exam days", db.memory().note(c)!!.text)
    }
}
