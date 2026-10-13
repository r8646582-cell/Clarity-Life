package com.clarity.life.ui.talk

import org.junit.Assert.assertEquals
import org.junit.Test

class GreetingTest {

    @Test
    fun everyHourHasTheRightGreeting() {
        // 12:30 am said "Good evening." before the night greeting existed.
        for (hour in 0..4) assertEquals("hour $hour", "Hello, night owl.", greeting(hour))
        for (hour in 5..11) assertEquals("hour $hour", "Good morning.", greeting(hour))
        for (hour in 12..16) assertEquals("hour $hour", "Good afternoon.", greeting(hour))
        for (hour in 17..23) assertEquals("hour $hour", "Good evening.", greeting(hour))
    }
}
