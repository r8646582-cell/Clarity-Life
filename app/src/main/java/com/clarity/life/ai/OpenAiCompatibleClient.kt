package com.clarity.life.ai

import com.clarity.life.settings.DefaultSettings
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The streaming client for DeepSeek and any other OpenAI-compatible provider.
 *
 * The rules here were learned the hard way (CLAUDE.md, AI client rules):
 * - read whole UTF-8 lines with a buffered reader; never decode raw byte chunks
 * - a thinking chunk is a heartbeat: it resets the gap timer, so a long think is not a timeout
 * - handle finish_reason "length" and provider error objects instead of pretending the reply ended
 * - connect 30 s, no overall call timeout, read timeout = the gap timeout for chat
 *
 * Every request ends in a finished reply, an interrupted one, or a failure. There is no third way to
 * leave a spinner turning.
 */
@Singleton
class OpenAiCompatibleClient @Inject constructor(
    private val http: OkHttpClient,
) : AiClient {

    override val name: String = "openai-compatible"

    override fun stream(endpoint: Endpoint, request: ChatRequest): Flow<ChatChunk> = flow {
        val body = OpenAiWire.requestBody(endpoint, request).toRequestBody(JSON)
        val call = http.newBuilder()
            .readTimeout(DefaultSettings.CHAT_GAP_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
            .newCall(
                Request.Builder()
                    .url(OpenAiWire.chatUrl(endpoint.baseUrl))
                    .header("Authorization", "Bearer ${endpoint.apiKey}")
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
                    // 408, 409, 425, 429 and the 5xx family are worth another try; the rest are not.
                    retryable = response.code == 408 || response.code == 409 || response.code == 425 ||
                        response.code == 429 || response.code >= 500,
                )
            }
            val source = response.body?.source()
                ?: throw AiFailure("empty_body", "the provider sent nothing back")
            var usage: Usage? = null
            var finish: String? = null
            var ended = false
            while (true) {
                val line = try {
                    source.readUtf8Line()
                } catch (e: InterruptedIOException) {
                    throw AiFailure("stream_gap", "the provider stopped sending", retryable = true, cause = e)
                } catch (e: IOException) {
                    throw AiFailure("stream_broken", "the connection dropped", retryable = true, cause = e)
                } ?: break
                when (val event = OpenAiWire.parseLine(line)) {
                    is OpenAiWire.Event.Delta -> {
                        event.thinking?.let { emit(ChatChunk.Thinking(it)) }
                        event.text?.let { emit(ChatChunk.Text(it)) }
                        event.finishReason?.let { finish = it }
                        event.usage?.let { usage = merge(usage, it) }
                    }

                    is OpenAiWire.Event.Usage ->
                        usage = merge(usage, event)

                    is OpenAiWire.Event.Failure ->
                        throw AiFailure(event.code, event.message, retryable = true)

                    OpenAiWire.Event.Done -> ended = true
                    OpenAiWire.Event.Ignore -> Unit
                }
            }
            // A stream that just stops (no end marker, no finish reason) is a broken reply, not a finished one.
            if (!ended && finish == null) throw AiFailure("stream_broken", "the reply stopped before its end", retryable = true)
            emit(ChatChunk.Finished(finish, usage))
        }
    }.flowOn(Dispatchers.IO) // call.execute() blocks; on the main thread Android kills the app

    override suspend fun test(endpoint: Endpoint): TestResult = try {
        var text = ""
        var thought = false
        stream(endpoint.copy(maxOutputTokens = minOf(endpoint.maxOutputTokens, TEST_MAX_TOKENS)), OpenAiWire.testRequest(endpoint))
            .collect { chunk ->
                when (chunk) {
                    is ChatChunk.Text -> text += chunk.text
                    is ChatChunk.Thinking -> thought = true
                    is ChatChunk.Finished -> Unit
                }
            }
        if (text.isBlank()) {
            TestResult(false, "The provider answered, but with nothing in it. Check the model name.")
        } else {
            TestResult(
                true,
                if (thought) "Connected. The model answered and streamed its thinking." else "Connected.",
            )
        }
    } catch (e: AiFailure) {
        TestResult(false, friendly(e))
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: IllegalArgumentException) {
        TestResult(false, "The base URL does not look right. It should start with https://")
    } catch (e: Exception) {
        TestResult(false, "The test could not run (${e.javaClass.simpleName}).")
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** Thinking counts toward the cap; a small one leaves a thinking model no room to answer the test. */
        private const val TEST_MAX_TOKENS = 2048

        /** One quiet, honest line. It never carries a key or a word of the conversation. */
        fun friendly(e: AiFailure): String = when {
            e.code == "http_401" || e.code == "http_403" -> "The provider refused the key. Check the API key."
            e.code == "http_400" || e.code == "http_422" -> "The provider rejected the request. Check the model name."
            e.code == "http_402" -> "The provider says the account has no balance left."
            e.code == "http_404" -> "That address or model name was not found. Check the base URL and the model."
            e.code == "http_429" -> "The provider is rate limiting. Try again in a moment."
            e.code.startsWith("http_5") -> "The provider is having trouble. Try again in a moment."
            e.code == "network" -> "No answer from the provider. Check the connection and the base URL."
            e.code == "stream_gap" -> "The provider went quiet in the middle of the reply."
            e.code == "stream_broken" -> "The connection dropped in the middle of the reply."
            else -> "The provider could not complete the reply (${e.code})."
        }
    }
}

/** Shared OkHttp client: one connection pool for the whole app, no logging of bodies, ever. */
@Singleton
class HttpClients @Inject constructor() {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(DefaultSettings.CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(DefaultSettings.CHAT_GAP_TIMEOUT_S, TimeUnit.SECONDS)
        .writeTimeout(DefaultSettings.CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        // No call timeout: a reply with thinking and no length cap may legitimately take minutes.
        .callTimeout(0, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
}

/** Kept so a cancelled reply really stops the HTTP call (adapted from the old repo's CancellableCall). */
fun Call.cancelQuietly() {
    try {
        cancel()
    } catch (e: Exception) {
        // Cancelling can never be the thing that breaks a reply.
    }
}

/** Usage can arrive in pieces (Anthropic: input at the start, output at the end); keep the largest of each. */
internal fun merge(current: Usage?, event: OpenAiWire.Event.Usage): Usage = Usage(
    tokensIn = maxOf(current?.tokensIn ?: 0, event.tokensIn),
    tokensOut = maxOf(current?.tokensOut ?: 0, event.tokensOut),
    tokensCached = maxOf(current?.tokensCached ?: 0, event.tokensCached),
)
