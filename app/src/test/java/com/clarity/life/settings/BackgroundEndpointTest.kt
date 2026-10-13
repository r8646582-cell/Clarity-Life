package com.clarity.life.settings

import com.clarity.life.ai.Endpoint
import org.junit.Assert.assertEquals
import org.junit.Test

/** CLAUDE.md, AI client rules: chat waits 120 s for the next piece of a reply, background calls 300 s. */
class BackgroundEndpointTest {

    private val chat = Endpoint(
        provider = "deepseek",
        baseUrl = "https://example.invalid",
        apiKey = "k",
        model = "chat-model",
        maxOutputTokens = 16000,
        effort = "low",
    )

    @Test
    fun chatWaitsTheGapTimeout() {
        assertEquals(120L, chat.readTimeoutS)
        assertEquals(DefaultSettings.CHAT_GAP_TIMEOUT_S, chat.readTimeoutS)
    }

    @Test
    fun everyBackgroundJobWaitsTheBackgroundTimeout() {
        for (json in listOf(true, false)) {
            val e = SettingsRepository.background(chat, " bg-model ", json)
            assertEquals(300L, e.readTimeoutS)
            assertEquals("bg-model", e.model)
        }
    }

    @Test
    fun theLetterWaitsTheBackgroundTimeout() {
        val e = SettingsRepository.letter(chat, "letter-model", "high")
        assertEquals(DefaultSettings.BACKGROUND_READ_TIMEOUT_S, e.readTimeoutS)
        assertEquals("high", e.effort)
    }

    @Test
    fun jsonJobsStillDoNotThink() {
        val e = SettingsRepository.background(chat, "bg-model", json = true)
        assertEquals(false, e.thinking)
        assertEquals(DefaultSettings.BACKGROUND_TEMPERATURE, e.temperature)
    }
}
