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

    @Test
    fun perChatTypeThinkingDefaults() {
        assertEquals("low", DefaultSettings.CHAT_THINKING_FREE)
        assertEquals("high", DefaultSettings.CHAT_THINKING_UNTANGLE)
        assertEquals("high", DefaultSettings.CHAT_THINKING_DECIDE)
        assertEquals("high", DefaultSettings.CHAT_THINKING_PLAN)
    }

    @Test
    fun fallbackEndpointUsesChatModelAndSettings() {
        val bgEndpoint = SettingsRepository.background(chat, "cheaper-model", json = true)
        val fallback = SettingsRepository.background(chat, chat.model, json = true)
        assertEquals("cheaper-model", bgEndpoint.model)
        assertEquals("chat-model", fallback.model)
        assertEquals(chat.provider, fallback.provider)
        assertEquals(chat.apiKey, fallback.apiKey)
        assertEquals(DefaultSettings.BACKGROUND_READ_TIMEOUT_S, fallback.readTimeoutS)
    }
}
