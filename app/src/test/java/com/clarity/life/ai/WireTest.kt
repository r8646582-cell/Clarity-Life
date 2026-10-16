package com.clarity.life.ai

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The awkward parts of streaming, tested on the JVM instead of on the phone: thinking chunks, a cut-off
 * reply, provider error objects, cached-token counts and the odd line shapes a stream really contains.
 */
class WireTest {

    @Test
    fun aTextDeltaIsReadAsText() {
        val event = OpenAiWire.parseLine("""data: {"choices":[{"delta":{"content":"Okay."}}]}""")
        assertEquals(OpenAiWire.Event.Delta("Okay.", null, null), event)
    }

    @Test
    fun aThinkingDeltaIsReadAsThinkingAndNotAsText() {
        val deepSeek = OpenAiWire.parseLine("""data: {"choices":[{"delta":{"reasoning_content":"hmm"}}]}""")
        assertEquals(OpenAiWire.Event.Delta(null, "hmm", null), deepSeek)
        val other = OpenAiWire.parseLine("""data: {"choices":[{"delta":{"reasoning":"hmm"}}]}""")
        assertEquals(OpenAiWire.Event.Delta(null, "hmm", null), other)
    }

    @Test
    fun keepAliveAndCommentLinesAreIgnored() {
        assertEquals(OpenAiWire.Event.Ignore, OpenAiWire.parseLine(""))
        assertEquals(OpenAiWire.Event.Ignore, OpenAiWire.parseLine(": keep-alive"))
        assertEquals(OpenAiWire.Event.Ignore, OpenAiWire.parseLine("event: ping"))
    }

    @Test
    fun keepAlivesDoNotKeepABusyProviderWaitingForever() {
        // DeepSeek sends ": keep-alive" every few seconds while busy, for up to 10 minutes.
        val quiet = QuietTimer(gapMs = 120_000, startMs = 0)
        var now = 0L
        while (!quiet.quietTooLong(now)) {
            now += 5_000
            assertEquals(OpenAiWire.Event.Ignore, OpenAiWire.parseLine(": keep-alive"))
        }
        assertEquals(125_000L, now)
    }

    @Test
    fun aRealPieceResetsTheQuietTimer() {
        val quiet = QuietTimer(gapMs = 120_000, startMs = 0)
        quiet.piece(100_000)
        assertFalse(quiet.quietTooLong(200_000))
        assertTrue(quiet.quietTooLong(220_001))
    }

    @Test
    fun anAnthropicPingIsNotAPieceOfTheReply() {
        assertEquals(OpenAiWire.Event.Ignore, AnthropicWire.parseLine("event: ping"))
        assertEquals(OpenAiWire.Event.Ignore, AnthropicWire.parseLine("data: {\"type\": \"ping\"}"))
    }

    @Test
    fun aBusyProviderGetsItsOwnQuietLine() {
        val line = OpenAiCompatibleClient.friendly(QuietTimer.busy())
        assertTrue(line.contains("busy"))
    }

    @Test
    fun aTestThatRanOutOfTimeSaysSo() {
        val limit = com.clarity.life.settings.DefaultSettings.TEST_TIMEOUT_S * 1000
        val cut = AiFailure("network", "could not reach the provider")
        assertTrue(OpenAiCompatibleClient.testLine(cut, startedMs = 0, nowMs = limit).startsWith("No answer within"))
        // A quick failure keeps its own line.
        assertEquals(OpenAiCompatibleClient.friendly(cut), OpenAiCompatibleClient.testLine(cut, startedMs = 0, nowMs = 2_000))
    }

    @Test
    fun theEndOfTheStreamIsRecognised() {
        assertEquals(OpenAiWire.Event.Done, OpenAiWire.parseLine("data: [DONE]"))
    }

    @Test
    fun aCutOffReplyIsNotTreatedAsAFinishedOne() {
        val event = OpenAiWire.parseLine("""data: {"choices":[{"delta":{},"finish_reason":"length"}]}""")
        assertEquals("length", (event as OpenAiWire.Event.Delta).finishReason)
        assertTrue(OpenAiWire.wasCutOff(event.finishReason))
        assertFalse(OpenAiWire.wasCutOff("stop"))
    }

    @Test
    fun anErrorObjectInTheStreamIsAFailure() {
        val event = OpenAiWire.parseLine("""data: {"error":{"message":"bad key","type":"auth"}}""")
        val failure = event as OpenAiWire.Event.Failure
        assertEquals("auth", failure.code)
        assertEquals("bad key", failure.message)
    }

    @Test
    fun somethingThatIsNotJsonFailsClosedInsteadOfBeingGuessedAt() {
        val event = OpenAiWire.parseLine("data: <html>502</html>")
        assertEquals("bad_json", (event as OpenAiWire.Event.Failure).code)
    }

    @Test
    fun cachedTokensAreReadFromEitherShape() {
        val deepSeek = OpenAiWire.parseLine(
            """data: {"usage":{"prompt_tokens":100,"completion_tokens":20,"prompt_cache_hit_tokens":64}}""",
        )
        assertEquals(OpenAiWire.Event.Usage(100, 20, 64), deepSeek)
        val openAi = OpenAiWire.parseLine(
            """data: {"usage":{"prompt_tokens":100,"completion_tokens":20,
            "prompt_tokens_details":{"cached_tokens":80}}}""".trimIndent().replace("\n", ""),
        )
        assertEquals(OpenAiWire.Event.Usage(100, 20, 80), openAi)
    }

    @Test
    fun aFinishReasonThatArrivesWithTheUsageIsNotLost() {
        val event = OpenAiWire.parseLine(
            """data: {"choices":[{"delta":{"content":""},"finish_reason":"length"}],""" +
                """"usage":{"prompt_tokens":100,"completion_tokens":20,"prompt_cache_hit_tokens":64}}""",
        ) as OpenAiWire.Event.Delta
        assertEquals("length", event.finishReason)
        assertEquals(OpenAiWire.Event.Usage(100, 20, 64), event.usage)
    }

    @Test
    fun theRequestBodyAsksForStreamingUsageAndTheModelFromSettings() {
        val endpoint = Endpoint("deepseek", "https://example.test", "k", "some-model", 16000)
        val body = OpenAiWire.requestBody(endpoint, ChatRequest("S", listOf(ChatMessage(ChatMessage.USER, "hi"))))
        assertTrue(body.contains("\"model\":\"some-model\""))
        assertTrue(body.contains("\"stream\":true"))
        assertTrue(body.contains("\"include_usage\":true"))
        assertTrue(body.contains("\"max_tokens\":16000"))
        assertTrue(body.contains("\"role\":\"system\""))
    }

    @Test
    fun deepSeekThinkingLevelsGoOnTheWire() {
        val base = Endpoint("deepseek", "https://api.test.com", "k", "deepseek-flash", 16000, temperature = 0.3)
        val req = ChatRequest("S", listOf(ChatMessage(ChatMessage.USER, "hi")))
        val low = OpenAiWire.requestBody(base.copy(effort = "low"), req)
        assertTrue(low.contains("\"thinking\":{\"type\":\"enabled\"}"))
        assertTrue(low.contains("\"reasoning_effort\":\"low\""))
        // Thinking mode ignores temperature, so it is not sent with it.
        assertTrue(!low.contains("temperature"))
        val off = OpenAiWire.requestBody(base.copy(effort = "off"), req)
        assertTrue(off.contains("\"thinking\":{\"type\":\"disabled\"}"))
        assertTrue(!off.contains("reasoning_effort"))
        assertTrue(off.contains("\"temperature\":0.3"))
        // Another OpenAI-style server gets neither field: it may reject what it does not know.
        val compat = OpenAiWire.requestBody(base.copy(provider = "compat", effort = "high"), req)
        assertTrue(!compat.contains("thinking") && !compat.contains("reasoning_effort"))
        assertEquals("deepseek-flash, thinking low", base.copy(effort = "low").label)
    }

    @Test
    fun jsonModeIsAskedForOnlyWhenTheRequestWantsItAndOnlyFromDeepSeek() {
        val base = Endpoint("deepseek", "https://api.test.com", "k", "deepseek-flash", 16000)
        val msgs = listOf(ChatMessage(ChatMessage.USER, "json please"))
        val json = OpenAiWire.requestBody(base, ChatRequest("S", msgs, jsonOutput = true))
        assertTrue(json.contains("\"response_format\":{\"type\":\"json_object\"}"))
        assertFalse(OpenAiWire.requestBody(base, ChatRequest("S", msgs)).contains("response_format"))
        // Another OpenAI-style server may reject a field it does not know.
        assertFalse(OpenAiWire.requestBody(base.copy(provider = "compat"), ChatRequest("S", msgs, jsonOutput = true)).contains("response_format"))
    }

    @Test
    fun theChatUrlDoesNotDoubleTheVersionSegment() {
        assertEquals("https://api.test.com/v1/chat/completions", OpenAiWire.chatUrl("https://api.test.com"))
        assertEquals("https://api.test.com/v1/chat/completions", OpenAiWire.chatUrl("https://api.test.com/"))
        assertEquals("https://api.test.com/v1/chat/completions", OpenAiWire.chatUrl("https://api.test.com/v1"))
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            OpenAiWire.chatUrl("https://generativelanguage.googleapis.com/v1beta/openai"),
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            OpenAiWire.chatUrl("https://generativelanguage.googleapis.com/v1beta/openai/"),
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            OpenAiWire.chatUrl("https://generativelanguage.googleapis.com/v1beta"),
        )
    }

    @Test
    fun googleGeminiErrorArrayIsParsed() {
        val body = """[{"error":{"code":400,"message":"Please pass a valid API key","status":"INVALID_ARGUMENT"}}]"""
        val failure = OpenAiWire.parseErrorBody(body)
        assertEquals("400", failure.code)
        assertEquals("Please pass a valid API key", failure.message)
        val line = OpenAiCompatibleClient.friendly(AiFailure("http_400", failure.message))
        assertEquals("The provider refused the key. Check the API key.", line)
    }

    // ---------- the Anthropic preset ----------

    @Test
    fun anthropicThinkingAndTextAreToldApart() {
        val thinking = AnthropicWire.parseLine(
            """data: {"type":"content_block_delta","delta":{"type":"thinking_delta","thinking":"hmm"}}""",
        )
        assertEquals(OpenAiWire.Event.Delta(null, "hmm", null), thinking)
        val text = AnthropicWire.parseLine(
            """data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"Okay."}}""",
        )
        assertEquals(OpenAiWire.Event.Delta("Okay.", null, null), text)
    }

    @Test
    fun anthropicMaxTokensBecomesTheSameWordTheAppUsesElsewhere() {
        val event = AnthropicWire.parseLine(
            """data: {"type":"message_delta","delta":{"stop_reason":"max_tokens"}}""",
        )
        assertEquals("length", (event as OpenAiWire.Event.Delta).finishReason)
    }

    @Test
    fun anthropicUsageIncludesTheCacheRead() {
        val event = AnthropicWire.parseLine(
            """data: {"type":"message_start","message":{"usage":{"input_tokens":10,"output_tokens":2,
            "cache_creation_input_tokens":3,"cache_read_input_tokens":7}}}""".replace("\n", "").replace("            ", ""),
        )
        // Every input token counts as input, as with the main provider; 7 of the 20 came from the cache.
        assertEquals(OpenAiWire.Event.Usage(20, 2, 7), event)
    }

    @Test
    fun anthropicCachesTheSystemMessageAndTheHistoryBeforeTheLastTurn() {
        val endpoint = Endpoint(provider = "anthropic", baseUrl = "https://api.anthropic.com", apiKey = "k", model = "m", maxOutputTokens = 16000)
        val body = kotlinx.serialization.json.Json.parseToJsonElement(
            AnthropicWire.requestBody(
                endpoint,
                ChatRequest(
                    system = "persona and frozen context",
                    messages = listOf(
                        ChatMessage(ChatMessage.USER, "first"),
                        ChatMessage(ChatMessage.ASSISTANT, "reply"),
                        ChatMessage(ChatMessage.USER, "NOW and the new message"),
                    ),
                ),
            ),
        ).jsonObject
        val system = body["system"]!!.jsonArray.single().jsonObject
        assertEquals("persona and frozen context", system["text"]!!.jsonPrimitive.content)
        assertEquals("ephemeral", system["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val messages = body["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals("first", messages[0]["content"]!!.jsonPrimitive.content)
        val marked = messages[1]["content"]!!.jsonArray.single().jsonObject
        assertEquals("reply", marked["text"]!!.jsonPrimitive.content)
        assertTrue(marked.containsKey("cache_control"))
        // The final turn changes every time, so it is never marked.
        assertEquals("NOW and the new message", messages[2]["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun anthropicThinksAdaptivelyAndNeverSendsWhatCurrentModelsRefuse() {
        fun body(e: Endpoint) = kotlinx.serialization.json.Json.parseToJsonElement(
            AnthropicWire.requestBody(e, ChatRequest(system = "s", messages = listOf(ChatMessage(ChatMessage.USER, "hi")))),
        ).jsonObject
        val base = Endpoint(provider = "anthropic", baseUrl = "https://api.anthropic.com", apiKey = "k", model = "m", maxOutputTokens = 16000)
        val off = body(base.copy(thinking = false, effort = "off", temperature = 0.3))
        assertEquals("adaptive", off["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(!off["thinking"]!!.jsonObject.containsKey("budget_tokens"))
        assertTrue(!off.containsKey("temperature"))
        assertEquals("low", off["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals("high", body(base.copy(effort = "high"))["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals("max", body(base.copy(effort = "max"))["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
    }
}
