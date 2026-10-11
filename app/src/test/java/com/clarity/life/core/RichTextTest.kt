package com.clarity.life.core

import org.junit.Assert.assertEquals
import org.junit.Test

class RichTextTest {

    @Test
    fun plainTextIsLeftAlone() {
        val r = RichText.parse("Hello there.\nHow was it?")
        assertEquals("Hello there.\nHow was it?", r.text)
        assertEquals(emptyList<IntRange>(), r.bold)
    }

    @Test
    fun boldMarksAreRemovedAndRemembered() {
        val r = RichText.parse("a **b** c")
        assertEquals("a b c", r.text)
        assertEquals(listOf(2..2), r.bold)
    }

    @Test
    fun aHeadingIsBoldAndAListLineGetsADot() {
        val r = RichText.parse("## Plan\n- one\nplain")
        assertEquals("Plan\n•  one\nplain", r.text)
        assertEquals(listOf(0..3), r.bold)
    }

    @Test
    fun anUnfinishedMarkIsShownAsWritten() {
        assertEquals("a **b", RichText.parse("a **b").text)
        assertEquals("a **** b", RichText.parse("a **** b").text)
    }
}
