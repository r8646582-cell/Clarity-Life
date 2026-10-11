package com.clarity.life.core

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveTextTest {

    @Test
    fun plainTextIsShownAsItIs() {
        assertEquals("Hello there.\nHow was it?", LiveText.visible("Hello there.\nHow was it?"))
        assertEquals("", LiveText.visible(""))
    }

    @Test
    fun cardLinesAreHeldBackWhileStreaming() {
        val raw = "Sounds good.\n\n[[suggest: {\"type\":\"promise\"}]]"
        assertEquals("Sounds good.", LiveText.visible(raw))
        assertEquals("Sounds good.", LiveText.visible("Sounds good.\n[[sugg"))
        assertEquals("Sounds good.", LiveText.visible("Sounds good.\n["))
    }

    @Test
    fun aBracketInsideProseStays() {
        assertEquals("A [note] here", LiveText.visible("A [note] here"))
        assertEquals("Line one\n[a]", LiveText.visible("Line one\n[a]"))
    }

    @Test
    fun stepCatchesUpEvenly() {
        assertEquals(0, LiveText.step(0))
        assertEquals(1, LiveText.step(1))
        assertEquals(1, LiveText.step(6))
        assertEquals(2, LiveText.step(7))
        assertEquals(17, LiveText.step(100))
    }

    @Test
    fun thinkingLevelsParseAndLabel() {
        assertEquals("low", Thinking.parse(" LOW ", Thinking.HIGH))
        assertEquals("high", Thinking.parse("medium", Thinking.HIGH))
        assertEquals("high", Thinking.parse(null, Thinking.HIGH))
        assertEquals("deepseek-flash, thinking low", Thinking.label("deepseek-flash", "low"))
    }
}
