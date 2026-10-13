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
 * The Anthropic Messages wire format, used when he picks the Anthropic preset. Pure functions,
 * same shape as [OpenAiWire], so both are unit-tested on the JVM.
 */
object AnthropicWire {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    const val VERSION_HEADER = "anthropic-version"
    const val VERSION = "2023-06-01"

    /**
     * The app's thinking level as Anthropic's effort word. Current Claude models (checked 13 Oct 2026) only
     * think adaptively: a fixed `budget_tokens` and `thinking: disabled` are both refused with an error, and
     * so is a non-default temperature. So "off" becomes the lowest effort, the closest thing they allow.
     */
    fun effort(endpoint: Endpoint): String = when (endpoint.effort ?: if (endpoint.thinking) "high" else "off") {
        "max" -> "max"
        "high" -> "high"
        else -> "low"
    }

    fun messagesUrl(baseUrl: String): String = baseUrl.trimEnd('/') + "/v1/messages"

    fun requestBody(endpoint: Endpoint, request: ChatRequest): String = buildJsonObject {
        put("model", endpoint.model)
        put("stream", true)
        put("max_tokens", endpoint.maxOutputTokens)
        // Prompt caching (contracts/context.md): Anthropic caches only up to a marked block. The system message
        // (persona, examples, frozen context) is marked, and so is the last history message before the final
        // turn, which holds the volatile blocks. Prefixes shorter than the provider's minimum are not cached.
        if (request.system.isNotEmpty()) {
            put("system", buildJsonArray { add(textBlock(request.system, cache = true)) })
        }
        // Adaptive thinking with a readable summary, so a long think still streams something (the gap timer).
        putJsonObject("thinking") {
            put("type", "adaptive")
            put("display", "summarized")
        }
        putJsonObject("output_config") { put("effort", effort(endpoint)) }
        put(
            "messages",
            buildJsonArray {
                val marked = if (request.system.isNotEmpty() && request.messages.size >= 2) request.messages.size - 2 else -1
                request.messages.forEachIndexed { i, m ->
                    add(
                        buildJsonObject {
                            put("role", m.role)
                            if (i == marked) {
                                put("content", buildJsonArray { add(textBlock(m.content, cache = true)) })
                            } else {
                                put("content", m.content)
                            }
                        },
                    )
                }
            },
        )
    }.toString()

    private fun textBlock(text: String, cache: Boolean): JsonObject = buildJsonObject {
        put("type", "text")
        put("text", text)
        if (cache) putJsonObject("cache_control") { put("type", "ephemeral") }
    }

    /**
     * One SSE line. Anthropic sends "event: <name>" lines and "data: {...}" lines; everything the app needs
     * is in the data lines, so the event names are ignored.
     */
    fun parseLine(line: String): OpenAiWire.Event {
        val trimmed = line.trim()
        if (!trimmed.startsWith("data:")) return OpenAiWire.Event.Ignore
        val payload = trimmed.removePrefix("data:").trim()
        if (payload.isEmpty()) return OpenAiWire.Event.Ignore
        val root = try {
            json.parseToJsonElement(payload).jsonObject
        } catch (e: Exception) {
            return OpenAiWire.Event.Failure("bad_json", "the provider sent something that is not JSON")
        }
        return when (root["type"]?.jsonPrimitive?.contentOrNull) {
            "error" -> {
                val error = root["error"] as? JsonObject
                OpenAiWire.Event.Failure(
                    error?.get("type")?.jsonPrimitive?.contentOrNull ?: "provider_error",
                    error?.get("message")?.jsonPrimitive?.contentOrNull ?: "the provider reported an error",
                )
            }

            "content_block_delta" -> {
                val delta = root["delta"] as? JsonObject
                when (delta?.get("type")?.jsonPrimitive?.contentOrNull) {
                    "thinking_delta" -> OpenAiWire.Event.Delta(
                        null,
                        delta["thinking"]?.jsonPrimitive?.contentOrNull,
                        null,
                    )

                    "text_delta" -> OpenAiWire.Event.Delta(
                        delta["text"]?.jsonPrimitive?.contentOrNull,
                        null,
                        null,
                    )

                    else -> OpenAiWire.Event.Ignore
                }
            }

            "message_start" -> {
                val usage = (root["message"] as? JsonObject)?.get("usage") as? JsonObject
                if (usage == null) {
                    OpenAiWire.Event.Ignore
                } else {
                    // Anthropic counts cached and newly cached tokens apart from input_tokens; the app's tokensIn
                    // is every input token, as the OpenAI shape's prompt_tokens is.
                    val cached = usage.long("cache_read_input_tokens")
                    OpenAiWire.Event.Usage(
                        tokensIn = usage.long("input_tokens") + usage.long("cache_creation_input_tokens") + cached,
                        tokensOut = usage.long("output_tokens"),
                        tokensCached = cached,
                    )
                }
            }

            "message_delta" -> {
                val stop = (root["delta"] as? JsonObject)?.get("stop_reason")?.jsonPrimitive?.contentOrNull
                // Anthropic says max_tokens where the OpenAI shape says length; the app has one word for it.
                val finish = if (stop == "max_tokens") "length" else stop
                // The real output count comes here; message_start only knew the first token or so.
                val out = (root["usage"] as? JsonObject)?.long("output_tokens")
                val usage = out?.let { OpenAiWire.Event.Usage(tokensIn = 0, tokensOut = it, tokensCached = 0) }
                if (finish == null) usage ?: OpenAiWire.Event.Ignore else OpenAiWire.Event.Delta(null, null, finish, usage)
            }

            "message_stop" -> OpenAiWire.Event.Done
            else -> OpenAiWire.Event.Ignore
        }
    }

    private fun JsonObject.long(key: String): Long = (this[key] as? JsonPrimitive)?.longOrNull ?: 0
}
