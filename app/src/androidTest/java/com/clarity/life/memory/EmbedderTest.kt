package com.clarity.life.memory

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.clarity.life.core.VectorMath
import com.clarity.life.data.ClarityDatabase
import com.clarity.life.data.entity.ConversationRow
import com.clarity.life.data.entity.MessageRow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The bundled model on a real Android runtime: the vectors must match the reference ones made by
 * tools/make_embedding_vectors.py (contracts/embedding_vectors.json), and meaning search must find a message
 * that shares no searched keyword with the question.
 */
@RunWith(AndroidJUnit4::class)
class EmbedderTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theVectorsMatchTheReference() {
        val embedder = Embedder(context)
        val cases = Json.parseToJsonElement(
            context.assets.open("contracts/embedding_vectors.json").bufferedReader().readText(),
        ).jsonObject.getValue("embeddings").jsonArray.map { it.jsonObject }
        assertEquals(4, cases.size)
        for (c in cases) {
            val expected = c.getValue("vector").jsonArray.map { it.jsonPrimitive.float }.toFloatArray()
            val got = embedder.embed(c.getValue("text").jsonPrimitive.content)
            assertNotNull("meaning search is off: ${embedder.problem}", got)
            val cosine = VectorMath.dot(got!!, expected)
            assertTrue("cosine $cosine for ${c.getValue("text")}", cosine >= Embedder.MIN_REFERENCE_COSINE)
        }
        assertNull(embedder.problem)
    }

    @Test
    fun meaningSearchFindsWhatKeywordsMiss() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, ClarityDatabase::class.java).build()
        try {
            val embedder = Embedder(context)
            val search = SearchIndex(db, embedder)
            val now = 1_791_648_000_000L
            val one = db.conversations().insert(ConversationRow(startedAt = now, title = "Revision"))
            val two = db.conversations().insert(ConversationRow(startedAt = now + 1))
            val id = db.messages().insert(
                MessageRow(conversationId = one, role = "user", content = "my sister Hina helps me revise on sundays", createdAt = now),
            )
            search.indexHisMessage(id, "my sister Hina helps me revise on sundays")
            db.messages().insert(MessageRow(conversationId = one, role = "user", content = "my car needs new tyres soon", createdAt = now))
            assertEquals(2, EmbeddingIndexer(context, db, embedder).indexMissing(now))

            val block = search.relevantPast(two, "who helps me study at the weekend?", "Asia/Karachi")
            assertEquals(listOf(true), block.items.map { it.text.contains("my sister Hina") })
        } finally {
            db.close()
        }
    }
}
