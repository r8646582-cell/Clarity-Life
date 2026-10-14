package com.clarity.life.core

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Kotlin WordPiece must give exactly the reference tokenizer's ids (contracts/embedding_vectors.json). */
class TokenizerTest {

    private val tokenizer by lazy {
        WordPiece(Vectors.readText("app/src/main/assets/models/mdbr-leaf-mt/vocab.txt").split("\n").map { it.trimEnd('\r') })
    }

    @Test
    fun everyReferenceCaseGivesTheSameIds() {
        val cases = Vectors.load("contracts/embedding_vectors.json").getValue("tokenizer").jsonArray.map { it.jsonObject }
        assertTrue(cases.size >= 20)
        val failures = mutableListOf<String>()
        for (c in cases) {
            val text = c.getValue("text").jsonPrimitive.content
            val expected = c.getValue("ids").jsonArray.map { it.jsonPrimitive.int }
            val got = tokenizer.encode(text).toList()
            if (got != expected) failures.add("${text.take(50)}\n   got      ${got.take(30)}\n   expected ${expected.take(30)}")
        }
        assertTrue("${failures.size} tokenizer cases differ:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun theVocabularyIsTheModelsOwn() {
        assertEquals(101, tokenizer.encode("").first())
        assertEquals(102, tokenizer.encode("").last())
    }
}
