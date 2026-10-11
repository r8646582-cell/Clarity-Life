package com.clarity.life.core

import com.clarity.life.core.Retrieval.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RetrievalTest {

    @Test
    fun termsSkipShortAndCommonWordsAndKeepTheLongestFirst() {
        assertEquals(
            listOf("chemistry", "lectures", "evening"),
            Retrieval.terms("I just can't do the chemistry lectures in the evening, you know?"),
        )
        assertEquals(emptyList<String>(), Retrieval.terms("ok yes it is what it is"))
        assertNull(Retrieval.matchQuery(emptyList()))
        assertEquals(
            "body:chemistry OR title:chemistry OR body:lectures OR title:lectures",
            Retrieval.matchQuery(listOf("chemistry", "lectures")),
        )
    }

    @Test
    fun aQuotedTermCannotBreakTheQuery() {
        // Only letters and digits survive, so no quote mark or operator reaches FTS4.
        val q = Retrieval.matchQuery(Retrieval.terms("\"hina\" OR NEAR(*) sister's"))!!
        assertTrue(q, q.split(" OR ").all { it.matches(Regex("(body|title):[a-z0-9]+")) })
    }

    @Test
    fun onlyStrongMatchesAreSentBestFirst() {
        val terms = listOf("chemistry", "lectures", "evening")
        val hits = Retrieval.choose(
            listOf(
                Candidate("the evening was fine", 1, "a"),
                Candidate("chemistry lectures in the evening are dead for me", 2, "b"),
                Candidate("two chemistry lectures today", 3, "c"),
                Candidate("Two  chemistry lectures today", 4, "d"),
            ),
            terms,
        )
        assertEquals(listOf("b", "c"), hits.map { it.candidate.label })
        val block = Retrieval.block(hits)
        assertEquals("b: chemistry lectures in the evening are dead for me", block.items[0].text)
        assertTrue("the weakest goes first", block.items[1].drop > block.items[0].drop)
    }

    @Test
    fun aOneWordQuestionNeedsThatWord() {
        val hits = Retrieval.choose(listOf(Candidate("Hina came over", 1, "x")), listOf("hina"))
        assertEquals(1, hits.size)
    }

    @Test
    fun longTextIsClipped() {
        val hit = Retrieval.Hit(Candidate("a".repeat(400), 1, "l"), 2)
        assertEquals("l: " + "a".repeat(Retrieval.SNIPPET_CHARS) + "...", Retrieval.block(listOf(hit)).items[0].text)
    }
}
