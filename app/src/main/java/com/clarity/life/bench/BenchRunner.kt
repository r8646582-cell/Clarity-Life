package com.clarity.life.bench

import com.clarity.life.ai.AiFailure
import com.clarity.life.ai.ChatChunk
import com.clarity.life.ai.ChatMessage
import com.clarity.life.ai.ChatRequest
import com.clarity.life.chat.Blocks
import com.clarity.life.chat.ChatRepository
import com.clarity.life.core.Block
import com.clarity.life.core.BlockItem
import com.clarity.life.core.CardParser
import com.clarity.life.core.ContextBuilder
import com.clarity.life.core.Jv
import com.clarity.life.core.StrictJson
import com.clarity.life.core.TestBench
import com.clarity.life.prompts.PromptStore
import com.clarity.life.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The in-app test bench (prompts/testbench.md, spec section 13): every scenario is sent to the real model the
 * way a chat would be (persona, examples, cards, the chat-type file, then the scenario's Setup blocks), with an
 * otherwise EMPTY context: off the record, nothing saved, no real memory used. The last reply of each scenario
 * is scored with [TestBench] (the same checks as tools/bench.py), and he judges the rest against its Expect line.
 *
 * The result is kept per model and thinking level (a setting row), so a change of model or prompt can be
 * compared with the last run. It runs in its own scope, so leaving Settings does not stop it.
 */
@Singleton
class BenchRunner @Inject constructor(
    private val chat: ChatRepository,
    private val prompts: PromptStore,
    private val settings: SettingsRepository,
) {
    data class Result(
        val name: String,
        val expect: String,
        val reply: String,
        val failed: List<String>,
        val checks: Int,
        val error: String? = null,
    ) {
        val passed: Boolean get() = error == null && failed.isEmpty()
    }

    data class Run(
        val model: String,
        val total: Int,
        val results: List<Result> = emptyList(),
        val running: Boolean = true,
        val line: String? = null,
    ) {
        /** Scenarios with automatic checks, and how many of them passed every one. */
        val scored: Int get() = results.count { it.checks > 0 }
        val passed: Int get() = results.count { it.checks > 0 && it.passed }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val _run = MutableStateFlow<Run?>(null)
    val run: StateFlow<Run?> = _run

    val scenarioCount: Int get() = scenarios().size

    private fun scenarios() = TestBench.parse(prompts.builtIn(PromptStore.TESTBENCH))

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { runAll() }
    }

    fun stop() {
        job?.cancel()
        _run.value = _run.value?.copy(running = false, line = "Stopped.")
    }

    private suspend fun runAll() {
        val endpoint = chat.endpointForChat()
        if (endpoint == null) {
            _run.value = Run("", 0, running = false, line = "Paste your API key first.")
            return
        }
        val all = scenarios()
        val banned = prompts.contract.bannedPhrasesForReplies
        _run.value = Run(endpoint.label, all.size)
        var failuresInARow = 0
        for (s in all) {
            val result = try {
                val reply = replyTo(s, endpoint)
                failuresInARow = 0
                val failed = s.checks.filter { c ->
                    try {
                        !TestBench.runCheck(c, reply, s, banned)
                    } catch (e: IllegalArgumentException) {
                        true
                    }
                }
                Result(s.name, s.expect, reply, failed, s.checks.size)
            } catch (e: AiFailure) {
                failuresInARow++
                Result(s.name, s.expect, "", emptyList(), s.checks.size, error = com.clarity.life.ai.OpenAiCompatibleClient.friendly(e))
            } catch (e: com.clarity.life.core.ContextError) {
                Result(s.name, s.expect, "", emptyList(), s.checks.size, error = "The scenario could not be built (${e.message}).")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Anything else ends this scenario with a line; the run goes on and never stays "running".
                Result(s.name, s.expect, "", emptyList(), s.checks.size, error = "The app failed on this one (${e.javaClass.simpleName}).")
            }
            val now = _run.value ?: return
            _run.value = now.copy(results = now.results + result)
            if (failuresInARow >= 3) {
                _run.value = _run.value?.copy(running = false, line = "Stopped: the provider failed three times in a row.")
                return
            }
        }
        val done = _run.value ?: return
        _run.value = done.copy(running = false, line = null)
        save(done)
    }

    /** One scenario, the way a chat builds it, with only its own Setup lines as context. */
    private suspend fun replyTo(s: TestBench.Scenario, endpoint: com.clarity.life.ai.Endpoint): String {
        val chatType = if (s.type == "focus") "free" else s.type ?: "free"
        val name = settings.promptName()
        val tough = s.flags.firstOrNull { it.startsWith("tough_love=") }?.substringAfter('=') ?: "balanced"
        val blocks = s.setup.groupBy({ it.first }, { it.second }).map { (n, lines) -> Block(n, lines.map { BlockItem(it, 0) }) }
        val part2 = listOf(Blocks.flags(tough, "listen_only" in s.flags, chatType, offTheRecord = true)) +
            blocks.filter { it.name in ContextBuilder.PART2_ORDER }
        val part3 = blocks.filter { it.name in ContextBuilder.PART3_ORDER }
        val unknown = blocks.map { it.name }.filter { it !in ContextBuilder.PART2_ORDER && it !in ContextBuilder.PART3_ORDER }
        if (unknown.isNotEmpty()) throw com.clarity.life.core.ContextError("unknown block ${unknown.first()}")
        val system = Blocks.systemMessage(
            persona = prompts.fill(prompts.text(PromptStore.PERSONA), mapOf("USER_NAME" to name)),
            examples = prompts.fill(prompts.text(PromptStore.EXAMPLES), mapOf("USER_NAME" to name)),
            cards = prompts.fill(
                prompts.text(PromptStore.CARDS),
                mapOf("USER_NAME" to name, "ALLOWED_CARDS" to CardParser.CHAT_TYPES[chatType].orEmpty().joinToString(", ")),
            ),
            mode = PromptStore.modeFile(s.type ?: "free")?.let { prompts.text(it) },
            part2 = part2,
        ).system
        val history = s.prior.map { (role, text) ->
            ContextBuilder.historyMessage(role, text, null).let { ChatMessage(it.role, it.content) }
        }.toMutableList()
        var last = ""
        for (line in s.user) {
            val turn = Blocks.userTurn(part3, line).content
            last = collect(endpoint, ChatRequest(system, history + ChatMessage(ChatMessage.USER, turn)))
            history += ContextBuilder.historyMessage("user", line, null).let { ChatMessage(it.role, it.content) }
            history += ChatMessage(ChatMessage.ASSISTANT, ContextBuilder.escape(last))
        }
        return last
    }

    private suspend fun collect(endpoint: com.clarity.life.ai.Endpoint, request: ChatRequest): String {
        val out = StringBuilder()
        chat.stream(endpoint, request).collect { chunk -> if (chunk is ChatChunk.Text) out.append(chunk.text) }
        return out.toString()
    }

    /** Kept per model: the date, the score and which scenarios failed (names only). */
    private suspend fun save(run: Run) {
        val value = Jv.Obj(
            linkedMapOf(
                "at" to Jv.Num(System.currentTimeMillis().toDouble(), true),
                "passed" to Jv.Num(run.passed.toDouble(), true),
                "scored" to Jv.Num(run.scored.toDouble(), true),
                "failed" to Jv.Arr(run.results.filter { it.checks > 0 && !it.passed }.map { Jv.Str(it.name) }),
            ),
        )
        settings.set(KEY_PREFIX + run.model, StrictJson.write(value))
    }

    /** "54 of 60 passed, 10 Oct" for the model he uses now, or null if it was never run. */
    suspend fun lastLine(): String? {
        val endpoint = chat.endpointForChat() ?: return null
        val raw = settings.get(KEY_PREFIX + endpoint.label, "").ifBlank { return null }
        val m = StrictJson.parse(raw).asObj?.map ?: return null
        val at = m["at"]?.asNum?.toLong() ?: return null
        val day = com.clarity.life.core.TimeFacts.local(java.time.Instant.ofEpochMilli(at), settings.timeZone()).toLocalDate()
        return "Last run on ${endpoint.label}: ${m["passed"]?.asNum?.toInt()} of ${m["scored"]?.asNum?.toInt()} passed, " +
            com.clarity.life.core.TimeFacts.shortDay(day) + "."
    }

    companion object {
        const val KEY_PREFIX = "bench_result:"
    }
}
