package com.clarity.life.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardTalkTest {

    @Test
    fun wordsThatPointAtAButtonAreNoticed() {
        assertTrue(CardTalk.pointsAtAButton("If you want, we can switch. You choose by tapping below."))
        assertTrue(CardTalk.pointsAtAButton("I put it below with a reminder at 5."))
        assertTrue(CardTalk.pointsAtAButton("The plan is below. Read it first."))
        assertFalse(CardTalk.pointsAtAButton("That sounds like a hard evening. What happened?"))
        assertFalse(CardTalk.pointsAtAButton("You tapped Not now earlier, and that is fine."))
    }

    @Test
    fun theCoachIsToldWhyACardWasThrownAway() {
        val line = CardTalk.dropLine("review_date is not one of the review options")
        assertTrue(line.contains("Reason: review_date is not one of the review options."))
        assertTrue(line.contains("Review options in PLAN_STATE"))
        assertTrue(CardTalk.dropLine("too long title").contains("check that field"))
    }

    @Test
    fun aLineIsShownOnlyWhenNoButtonArrived() {
        assertNull(CardTalk.missingLine("Tap it below.", offered = 1, dropped = 0))
        assertNull(CardTalk.missingLine("How was today?", offered = 0, dropped = 0))
        assertNotNull(CardTalk.missingLine("Tap it below.", offered = 0, dropped = 0))
        assertEquals(
            CardTalk.missingLine("anything", offered = 0, dropped = 2),
            CardTalk.missingLine("Tap it below.", offered = 0, dropped = 1),
        )
    }
}
