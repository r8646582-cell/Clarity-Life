package com.clarity.life.core

import com.clarity.life.core.Retrieval.Candidate
import org.junit.Assert.assertEquals
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
        assertEquals("body:chemistr* OR title:chemistr*", Retrieval.termQuery("chemistry"))
        assertEquals("a word is searched in all its forms", "body:lectur* OR title:lectur*", Retrieval.termQuery("lectures"))
        assertEquals("body:stud* OR title:stud*", Retrieval.termQuery("studied"))
    }

    @Test
    fun threeLetterWordsAreSearchedUnlessTheyAreCommon() {
        // The owner's subject and the nap of 14 Oct 2026 were never searched before: they have 3 letters.
        assertEquals(listOf("lectures", "caf", "nap"), Retrieval.terms("did the CAF lectures after my nap, you see"))
        assertEquals(emptyList<String>(), Retrieval.terms("and the way you can get out"))
    }

    @Test
    fun theFormsOfAWordMeet() {
        val lectur = listOf("lecture", "lectures", "lecturing", "lectured").map(Retrieval::stem).toSet()
        assertEquals(setOf("lectur"), lectur)
        assertEquals(setOf("nap"), listOf("nap", "naps", "napping").map(Retrieval::stem).toSet())
        assertEquals(setOf("study"), listOf("study", "studies", "studied", "studying").map(Retrieval::stem).toSet())
        assertEquals("class", Retrieval.stem("class"))
        assertEquals(2, Retrieval.score("Two CAF lectures done before the nap", listOf("lecture", "naps")))
    }

    @Test
    fun aQuotedTermCannotBreakTheQuery() {
        // Only letters and digits survive, so no quote mark or operator reaches FTS4.
        for (t in Retrieval.terms("\"hina\" OR NEAR(*) sister's")) {
            val q = Retrieval.termQuery(t)
            assertTrue(q, q.split(" OR ").all { it.matches(Regex("(body|title):[a-z0-9]+\\*")) })
        }
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
