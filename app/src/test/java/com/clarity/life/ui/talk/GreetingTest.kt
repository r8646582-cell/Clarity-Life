package com.clarity.life.ui.talk

import org.junit.Assert.assertEquals
import org.junit.Test

class GreetingTest {

    @Test
    fun theGreetingUsesHisNameWhenThereIsOne() {
        assertEquals("Hello, Sam.", greeting("Sam"))
        assertEquals("Hello, Sam.", greeting("  Sam "))
        assertEquals("Hello.", greeting(""))
        assertEquals("Hello.", greeting("   "))
    }
}
