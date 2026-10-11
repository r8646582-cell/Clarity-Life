package com.clarity.life.ai

import kotlinx.coroutines.flow.Flow

/**
 * The one interface to an AI provider. Nothing else in the app knows which provider is in use, and no call
 * site ever names a model: the name, the base URL and the key are settings (CLAUDE.md rule 9).
 *
 * Nothing leaves the phone except these calls (rule 6).
 */

/** Where to send a request. Built from the settings and the key in the Keystore, never hard-coded. */
data class Endpoint(
    val provider: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val maxOutputTokens: Int,
    val temperature: Double? = null,
    /** Thinking on for chat; background JSON jobs do not need it. */
    val thinking: Boolean = true,
    /**
     * How hard to think: off, low, high or max ([com.clarity.life.core.Thinking]). Null sends nothing, so the
     * provider's own default applies. Only the DeepSeek preset sends it; another OpenAI-style server may
     * reject fields it does not know.
     */
    val effort: String? = null,
) {
    /** What a coach message records as its model, for the Details sheet. */
    val label: String
        get() = com.clarity.life.core.Thinking.label(model, effort ?: if (thinking) "on" else "off")
}

data class ChatMessage(val role: String, val content: String) {
    companion object {
        const val USER = "user"
        const val ASSISTANT = "assistant"
    }
}

data class ChatRequest(val system: String, val messages: List<ChatMessage>)

data class Usage(val tokensIn: Long = 0, val tokensOut: Long = 0, val tokensCached: Long = 0)

/** What a streaming reply produces. Thinking text is never shown; it only proves the model is alive. */
sealed class ChatChunk {
    data class Thinking(val text: String) : ChatChunk()

    data class Text(val text: String) : ChatChunk()

    /**
     * [reason] is the provider's finish reason. "length" means the model was cut off, and the app says so
     * instead of pretending the reply ended.
     */
    data class Finished(val reason: String?, val usage: Usage?) : ChatChunk()
}

/**
 * A failure the app can show as one quiet, honest line. [code] never contains content: it is what goes in
 * the error log (http_429, stream_gap, bad_json ...).
 */
class AiFailure(
    val code: String,
    message: String,
    val retryable: Boolean = true,
    cause: Throwable? = null,
) : Exception(message, cause)

data class TestResult(val ok: Boolean, val detail: String)

interface AiClient {
    /** deepseek, compat or anthropic: only used for the error log and the Settings line. */
    val name: String

    /**
     * Streams one reply. The flow ends with [ChatChunk.Finished] or throws [AiFailure]; it never hangs,
     * because a gap of more than the gap timeout between chunks is a failure (thinking counts as a chunk).
     */
    fun stream(endpoint: Endpoint, request: ChatRequest): Flow<ChatChunk>

    /** Settings, Test connection: one tiny streamed request, so he sees a real answer and not a guess. */
    suspend fun test(endpoint: Endpoint): TestResult
}
