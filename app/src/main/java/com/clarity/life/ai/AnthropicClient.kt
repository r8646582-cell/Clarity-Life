package com.clarity.life.ai

import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Used when he picks the Anthropic preset as his provider. Same rules as the OpenAI-compatible client, different wire format. */
@Singleton
class AnthropicClient @Inject constructor(
    private val http: OkHttpClient,
) : AiClient {

    override val name: String = "anthropic"

    override fun stream(endpoint: Endpoint, request: ChatRequest): Flow<ChatChunk> = flow {
        val body = AnthropicWire.requestBody(endpoint, request).toRequestBody(JSON)
        val call = http.newBuilder()
            .readTimeout(endpoint.readTimeoutS, TimeUnit.SECONDS)
            // Only Test connection sets one, so it always answers, even while the provider is busy.
            .callTimeout(endpoint.callTimeoutS, TimeUnit.SECONDS)
            .build()
            .newCall(
                Request.Builder()
                    .url(AnthropicWire.messagesUrl(endpoint.baseUrl))
                    .header("x-api-key", endpoint.apiKey)
                    .header(AnthropicWire.VERSION_HEADER, AnthropicWire.VERSION)
                    .header("Accept", "text/event-stream")
                    .post(body)
                    .build(),
            )

        val response = try {
            // A cancelled reply closes its connection at once instead of waiting out the read timeout.
            kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.invokeOnCompletion { call.cancelQuietly() }
            call.execute()
        } catch (e: IOException) {
            throw AiFailure("network", "could not reach the provider", retryable = true, cause = e)
        }

        response.use {
            if (!response.isSuccessful) {
                val failure = OpenAiWire.parseErrorBody(response.body?.string().orEmpty())
                throw AiFailure(
                    code = "http_${response.code}",
                    message = failure.message,
                    retryable = response.code == 429 || response.code >= 500,
                )
            }
            val source = response.body?.source()
                ?: throw AiFailure("empty_body", "the provider sent nothing back")
            var usage: Usage? = null
            var finish: String? = null
            var ended = false
            val quiet = QuietTimer(endpoint.readTimeoutS * 1000, System.currentTimeMillis())
            while (true) {
                val line = try {
                    source.readUtf8Line()
                } catch (e: InterruptedIOException) {
                    throw AiFailure("stream_gap", "the provider stopped sending", retryable = true, cause = e)
                } catch (e: IOException) {
                    throw AiFailure("stream_broken", "the connection dropped", retryable = true, cause = e)
                } ?: break
                val event = AnthropicWire.parseLine(line)
                if (event == OpenAiWire.Event.Ignore) {
                    // A keep-alive, a blank line or a ping: not a piece of the reply (see QuietTimer).
                    if (quiet.quietTooLong(System.currentTimeMillis())) throw QuietTimer.busy()
                } else {
                    quiet.piece(System.currentTimeMillis())
                }
                when (event) {
                    is OpenAiWire.Event.Delta -> {
                        event.thinking?.let { emit(ChatChunk.Thinking(it)) }
                        event.text?.let { emit(ChatChunk.Text(it)) }
                        event.finishReason?.let { finish = it }
                        event.usage?.let { usage = merge(usage, it) }
                    }

                    is OpenAiWire.Event.Usage ->
                        usage = merge(usage, event)

                    is OpenAiWire.Event.Failure -> throw AiFailure(event.code, event.message, retryable = true)
                    OpenAiWire.Event.Done -> ended = true
                    OpenAiWire.Event.Ignore -> Unit
                }
            }
            // A stream that just stops (no end marker, no finish reason) is a broken reply, not a finished one.
            if (!ended && finish == null) throw AiFailure("stream_broken", "the reply stopped before its end", retryable = true)
            emit(ChatChunk.Finished(finish, usage))
        }
    }.flowOn(Dispatchers.IO) // call.execute() blocks; on the main thread Android kills the app

    override suspend fun test(endpoint: Endpoint): TestResult {
        val started = System.currentTimeMillis()
        return try {
            testOnce(endpoint)
        } catch (e: AiFailure) {
            TestResult(false, OpenAiCompatibleClient.testLine(e, started))
        }
    }

    private suspend fun testOnce(endpoint: Endpoint): TestResult = try {
        var text = ""
        stream(
            endpoint.copy(
                maxOutputTokens = 2048,
                thinking = false,
                callTimeoutS = com.clarity.life.settings.DefaultSettings.TEST_TIMEOUT_S,
                readTimeoutS = minOf(endpoint.readTimeoutS, com.clarity.life.settings.DefaultSettings.TEST_TIMEOUT_S),
            ),
            OpenAiWire.testRequest(endpoint),
        ).collect { chunk -> if (chunk is ChatChunk.Text) text += chunk.text }
        if (text.isBlank()) {
            TestResult(false, "The provider answered, but with nothing in it. Check the model name.")
        } else {
            TestResult(true, "Connected.")
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: AiFailure) {
        throw e
    } catch (e: IllegalArgumentException) {
        TestResult(false, "The base URL does not look right. It should start with https://")
    } catch (e: Exception) {
        TestResult(false, e.message?.ifBlank { null } ?: "The test could not run (${e.javaClass.simpleName}).")
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
