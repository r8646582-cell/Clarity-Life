package com.clarity.life.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The OpenAI-compatible wire format (DeepSeek and anything else that speaks it), as pure functions:
 * building the request body and turning one SSE line into one event. Keeping it pure means the awkward
 * parts (thinking chunks, finish_reason length, error objects, cached-token counts) are unit-tested on the
 * JVM instead of being found on the phone. Adapted from the old repo's ai/OpenAiWire.kt.
 */
object OpenAiWire {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** One event from the stream. [Ignore] covers keep-alive lines and anything we do not need. */
    sealed class Event {
        data class Delta(
            val text: String?,
            val thinking: String?,
            val finishReason: String?,
            /** DeepSeek sends the last finish reason and the usage in one chunk; neither may be lost. */
            val usage: Usage? = null,
        ) : Event()

        data class Usage(val tokensIn: Long, val tokensOut: Long, val tokensCached: Long) : Event()

        data class Failure(val code: String, val message: String) : Event()

        object Done : Event()

        object Ignore : Event()
    }

    fun requestBody(endpoint: Endpoint, request: ChatRequest): String {
        val body = buildJsonObject {
            put("model", endpoint.model)
            put("stream", true)
            // Ask for the usage block at the end of the stream, so the cache counter is real, not guessed.
            putJsonObject("stream_options") { put("include_usage", true) }
            put("max_tokens", endpoint.maxOutputTokens)
            // JSON mode: the answer is always one valid JSON object. Only the DeepSeek preset sends it; another
            // OpenAI-style server may reject a field it does not know, and the parser still checks every card.
            if (request.jsonOutput && endpoint.provider == "deepseek") {
                putJsonObject("response_format") { put("type", "json_object") }
            }
            // DeepSeek thinks by default; thinking off is said out loud, and a level goes in reasoning_effort.
            // In thinking mode DeepSeek ignores temperature, so it is only sent with thinking off.
            val effort = if (endpoint.provider == "deepseek") endpoint.effort else null
            when {
                effort == null -> endpoint.temperature?.let { put("temperature", it) }
                effort == com.clarity.life.core.Thinking.OFF -> {
                    putJsonObject("thinking") { put("type", "disabled") }
                    endpoint.temperature?.let { put("temperature", it) }
                }
                else -> {
                    putJsonObject("thinking") { put("type", "enabled") }
                    put("reasoning_effort", effort)
                }
            }
            put(
                "messages",
                buildJsonArray {
                    if (request.system.isNotEmpty()) {
                        add(
                            buildJsonObject {
                                put("role", "system")
                                put("content", request.system)
                            },
                        )
                    }
                    for (m in request.messages) {
                        add(
                            buildJsonObject {
                                put("role", m.role)
                                put("content", m.content)
                            },
                        )
                    }
                },
            )
        }
        return body.toString()
    }

    /** The chat completions URL for a base URL that may end in /v1, /openai, /chat/completions, or a root. */
    fun chatUrl(baseUrl: String): String {
        val base = baseUrl.trimEnd('/')
        return when {
            base.endsWith("/chat/completions") -> base
            base.endsWith("/v1") || base.endsWith("/openai") -> "$base/chat/completions"
            else -> "$base/v1/chat/completions"
        }
    }

    /**
     * Turn one line of the stream into an event. The caller reads whole UTF-8 lines; never decode raw byte
     * chunks, or a multi-byte character split across chunks turns into rubbish.
     */
    fun parseLine(line: String): Event {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith(":")) return Event.Ignore
        if (!trimmed.startsWith("data:")) return Event.Ignore
        val payload = trimmed.removePrefix("data:").trim()
        if (payload == "[DONE]") return Event.Done
        val root = try {
            json.parseToJsonElement(payload).jsonObject
        } catch (e: Exception) {
            return Event.Failure("bad_json", "the provider sent something that is not JSON")
        }
        return parseObject(root)
    }

    /** A non-streamed body (an error page, or a test call) read as the same kinds of event. */
    fun parseObject(root: JsonObject): Event {
        root["error"]?.let { error ->
            val obj = error as? JsonObject
            val code = obj?.get("code")?.jsonPrimitive?.contentOrNull
                ?: obj?.get("type")?.jsonPrimitive?.contentOrNull
                ?: "provider_error"
            val message = obj?.get("message")?.jsonPrimitive?.contentOrNull ?: "the provider reported an error"
            return Event.Failure(code, message)
        }
        val usage = (root["usage"] as? JsonObject)?.takeIf { it.isNotEmpty() }?.let { u ->
            val details = u["prompt_tokens_details"] as? JsonObject
            Event.Usage(
                tokensIn = u.long("prompt_tokens"),
                tokensOut = u.long("completion_tokens"),
                // DeepSeek reports prompt_cache_hit_tokens; the OpenAI shape nests it in details.
                tokensCached = maxOf(u.long("prompt_cache_hit_tokens"), details?.long("cached_tokens") ?: 0),
            )
        }
        val choice = (root["choices"] as? kotlinx.serialization.json.JsonArray)?.firstOrNull() as? JsonObject
            ?: return usage ?: Event.Ignore
        val delta = choice["delta"]?.jsonObject ?: choice["message"]?.jsonObject
        val finish = choice["finish_reason"]?.jsonPrimitive?.contentOrNull
        val text = delta?.get("content")?.string()
        // DeepSeek calls it reasoning_content; some compatible servers call it reasoning.
        val thinking = delta?.get("reasoning_content")?.string() ?: delta?.get("reasoning")?.string()
        if (text == null && thinking == null && finish == null) return usage ?: Event.Ignore
        return Event.Delta(text, thinking, finish, usage)
    }

    /** What a finish reason means for the app. "length" is never hidden: the reply says it was cut off. */
    fun wasCutOff(finishReason: String?): Boolean = finishReason == "length"

    private fun JsonObject.long(key: String): Long =
        (this[key] as? JsonPrimitive)?.longOrNull ?: 0

    private fun kotlinx.serialization.json.JsonElement.string(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** The one small request Settings, Test connection sends. */
    fun testRequest(endpoint: Endpoint): ChatRequest = ChatRequest(
        system = "You are a connection test. Answer with the single word: ready.",
        messages = listOf(ChatMessage(ChatMessage.USER, "ready?")),
    )

    /** Reads a non-streamed error body, for the status codes that come back without a stream. */
    fun parseErrorBody(body: String): Event.Failure {
        val event = try {
            parseObject(json.parseToJsonElement(body).jsonObject)
        } catch (e: Exception) {
            null
        }
        return event as? Event.Failure ?: Event.Failure("provider_error", "the provider refused the request")
    }
}
