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
        assertTrue(CardTalk.pointsAtAButton("Here is your card."))
        assertTrue(CardTalk.pointsAtAButton("Here’s the plan card with the review on Saturday."))
        assertTrue(CardTalk.pointsAtAButton("I've added a card for it."))
        assertTrue(CardTalk.pointsAtAButton("If that matches, tap Save plan."))
        assertTrue(CardTalk.pointsAtAButton("Tap Set it if you want the reminder."))
        assertTrue(CardTalk.pointsAtAButton("Right, my mistake. Here it is. Tap it and we will build the plan from your numbers."))
        assertTrue(CardTalk.pointsAtAButton("If yes, tap below and we start."))
        assertFalse(CardTalk.pointsAtAButton("That sounds like a hard evening. What happened?"))
        assertFalse(CardTalk.pointsAtAButton("Here is what I noticed: you studied best after dinner."))
        assertFalse(CardTalk.pointsAtAButton("You tapped Not now earlier, and that is fine."))
        // Ordinary coaching words are not a button (false alarms seen on 13 Oct 2026).
        assertFalse(CardTalk.pointsAtAButton("Your sleep is below seven hours most nights."))
        assertFalse(CardTalk.pointsAtAButton("Here it is, plainly: you did more than you think."))
        assertFalse(CardTalk.pointsAtAButton("I made a list of the flash cards you still need."))
        assertFalse(CardTalk.pointsAtAButton("Hit the books, then press on and keep it going."))
        assertFalse(CardTalk.pointsAtAButton("Your flash cards are ready for tomorrow."))
    }

    @Test
    fun theCoachIsToldWhyACardWasThrownAway() {
        val line = CardTalk.dropLine("review_date is not one of the review options")
        assertTrue(line.contains("Reason: review_date is not one of the review options."))
        assertTrue(line.contains("Review options in PLAN_STATE"))
        assertTrue(CardTalk.dropLine("too long rationale").contains("much shorter"))
        assertTrue(CardTalk.dropLine("bad note kind").contains("check that field"))
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
