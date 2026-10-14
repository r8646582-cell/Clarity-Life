package com.clarity.life.memory

import com.clarity.life.ai.AiFailure
import com.clarity.life.ai.AnthropicClient
import com.clarity.life.ai.ChatChunk
import com.clarity.life.ai.ChatMessage
import com.clarity.life.ai.ChatRequest
import com.clarity.life.ai.OpenAiCompatibleClient
import com.clarity.life.core.NoteFacts
import com.clarity.life.core.Planner
import com.clarity.life.core.ReflectionApplier
import com.clarity.life.core.ReflectionContext
import com.clarity.life.core.ReflectionInput
import com.clarity.life.core.TimeFacts
import com.clarity.life.data.ClarityDatabase
import com.clarity.life.data.StateMachines
import com.clarity.life.data.entity.ErrorLogRow
import com.clarity.life.data.entity.JobRow
import com.clarity.life.prompts.PromptStore
import com.clarity.life.settings.SettingsRepository
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * The reflection job (spec section 7): after a conversation, the background model reads what he said and
 * returns strict JSON, [ReflectionApplier] checks it, and [MemoryRepository.applyReflection] applies what
 * survived in one transaction.
 *
 * Idempotent (spec section 18, point 7): the job row's key is the conversation plus the last message
 * covered, so a second run of the same work finds the row finished and makes no API call. There is no call
 * at all when he has said nothing new. A result that fails the checks is retried at most [MAX_ATTEMPTS]
 * times in all, then the job stays failed until he says something new in that conversation.
 */
@Singleton
class ReflectionJob @Inject constructor(
    private val db: ClarityDatabase,
    private val settings: SettingsRepository,
    private val prompts: PromptStore,
    private val mainClient: OpenAiCompatibleClient,
    private val anthropicClient: AnthropicClient,
    private val memory: MemoryRepository,
    private val planner: com.clarity.life.planner.PlannerRepository,
) {

    private suspend fun checkinFactsNow(): String {
        val today = planner.today(System.currentTimeMillis())
        return com.clarity.life.core.PlanBlocks.checkinFacts(planner.tracker(today), today).items.joinToString("\n") { it.text }
    }

    sealed class Outcome {
        /** Nothing new to learn, an off-the-record chat, or no API key yet: no call was made. */
        object NothingToDo : Outcome()

        /** Already done, or being done by another run. */
        object AlreadyDone : Outcome()
        data class Done(val applied: Int, val dropped: Int) : Outcome()

        /** Try again later (WorkManager backs off). [code] is content-free. */
        data class Retry(val code: String) : Outcome()

        /** Given up for this piece of the conversation. [code] is content-free. */
        data class Failed(val code: String) : Outcome()
    }

    suspend fun run(conversationId: Long, now: Long = System.currentTimeMillis()): Outcome {
        val conversation = db.conversations().get(conversationId) ?: return Outcome.NothingToDo
        if (conversation.offTheRecord == 1) return Outcome.NothingToDo
        val messages = db.messages().ofConversation(conversationId)
            .filter { row ->
                when (row.role) {
                    "user" -> true
                    // What he saw: finished replies, and the kept part of an interrupted one.
                    else -> (row.status == "complete" || row.status == "interrupted") && row.content.isNotBlank()
                }
            }
            .map { ReflectionInput.Msg(it.id, it.role, it.content, it.createdAt) }
        val selection = ReflectionInput.select(messages, conversation.reflectedUpToMessageId)
            ?: return Outcome.NothingToDo
        val endpoint = settings.backgroundEndpoint(json = true) ?: return Outcome.NothingToDo

        val key = ReflectionInput.jobKey(conversationId, selection.upTo)
        db.jobs().insertIfNew(JobRow(key = key, type = "reflection", createdAt = now))
        val row = db.jobs().byKey(key) ?: return Outcome.Retry("job_row_missing")
        val pending = when (row.status) {
            "succeeded", "skipped" -> return Outcome.AlreadyDone
            // Another run has it, unless that run died with the process long ago (the repair sweep only
            // runs when the app is opened, and WorkManager can start this job without the app).
            "running" -> if (now - (row.startedAt ?: 0L) < STALE_RUNNING_MS) {
                return Outcome.AlreadyDone
            } else {
                row.copy(status = StateMachines.move(StateMachines.JOB, "running", "pending"))
            }
            "failed" -> if (row.attempts >= MAX_ATTEMPTS) {
                return Outcome.Failed(row.lastError ?: "failed")
            } else {
                row.copy(status = StateMachines.move(StateMachines.JOB, "failed", "pending"))
            }
            else -> row
        }
        val job = pending.copy(
            status = StateMachines.move(StateMachines.JOB, pending.status, "running"),
            attempts = pending.attempts + 1,
            startedAt = now,
            lastError = null,
        )
        save(job)

        try {
            val tz = settings.timeZone()
            val boundary = settings.dayBoundaryHour()
            val today = dayKey(now, tz, boundary)
            val inputs = gather(conversation.focusId)
            val prompt = prompts.fill(
                prompts.text(PromptStore.REFLECTION),
                mapOf(
                    "USER_NAME" to settings.promptName(),
                    "SESSION_DATE" to sessionDate(selection, tz, boundary),
                    "CHAT_TYPE" to conversation.chatType + if (conversation.focusId != null) " (inside a focus)" else "",
                    "CONTEXT" to inputs.contextText,
                    "FOCUS_SUMMARY" to inputs.focusSummary.ifBlank { "(none)" },
                    // A later reflection rewrites the summary, so it must see the one it replaces, or the
                    // start of a long conversation would fall out of it (and out of EARLIER).
                    "PREVIOUS_SUMMARY" to com.clarity.life.core.ContextBuilder.escape(conversation.summary).ifBlank { "(none yet)" },
                    "CHECKIN_FACTS" to inputs.checkinFacts.ifBlank { "(none yet)" },
                    "DELETED_NOTES" to ReflectionInput.deleted(inputs.deletedNotes),
                    "TRANSCRIPT" to ReflectionInput.transcript(selection, conversation.reflectedUpToMessageId, tz),
                ),
            )
            // The filled prompt is the whole request: no prompt text is ever written in Kotlin.
            val request = ChatRequest(system = "", messages = listOf(ChatMessage(ChatMessage.USER, prompt)))
            val client = if (endpoint.provider == "anthropic") anthropicClient else mainClient
            val raw = StringBuilder()
            var finish: String? = null
            client.stream(endpoint, request).collect { chunk ->
                when (chunk) {
                    is ChatChunk.Text -> raw.append(chunk.text)
                    is ChatChunk.Thinking -> Unit
                    is ChatChunk.Finished -> {
                        finish = chunk.reason
                        chunk.usage?.let { u ->
                            db.diagnostics().addUsage(today.take(7), 1, u.tokensIn, u.tokensOut, u.tokensCached)
                        }
                    }
                }
            }
            if (finish == "length") return fail(job, "cut_off", now)

            val result = ReflectionApplier.apply(
                raw.toString(),
                ReflectionContext(
                    hisMessages = selection.newHis,
                    hisEarlierQuotes = inputs.earlierQuotes,
                    checkinFacts = inputs.checkinFacts,
                    notes = inputs.notes,
                    deletedNotes = inputs.deletedNotes,
                    people = inputs.people,
                    profile = inputs.profile,
                    inFocus = conversation.focusId != null,
                ),
            )
            if (!result.ok) return fail(job, "json_rejected", now)

            val said = messages.filter { it.role == "user" && it.id > conversation.reflectedUpToMessageId }
                .map { MemoryRepository.Said(it.text, dayKey(it.createdAt, tz, boundary)) }
            val applied = memory.applyReflection(conversationId, selection.upTo, job, result.ops, said, today, now)
                ?: run {
                    // A replay, or the conversation was forgotten: nothing was written, and that is the end of it.
                    save(job.copy(status = StateMachines.move(StateMachines.JOB, "running", "succeeded"), finishedAt = now))
                    return Outcome.AlreadyDone
                }
            val dropped = result.dropped.size + applied.skipped
            if (dropped > 0) log(now, "items_dropped", "n=$dropped")
            return Outcome.Done(applied.applied, dropped)
        } catch (e: CancellationException) {
            // WorkManager stopped the work (a newer reply rescheduled it): hand the job back, untouched.
            withContext(NonCancellable) {
                save(job.copy(status = StateMachines.move(StateMachines.JOB, "running", "pending")))
            }
            throw e
        } catch (e: AiFailure) {
            return fail(job, e.code, now, retryable = e.retryable)
        } catch (e: Throwable) {
            // A bug, not the model: the name of the error only, never content.
            return fail(job, "error_" + e.javaClass.simpleName, now, retryable = false)
        }
    }

    private suspend fun fail(job: JobRow, code: String, now: Long, retryable: Boolean = true): Outcome {
        withContext(NonCancellable) {
            save(
                job.copy(
                    status = StateMachines.move(StateMachines.JOB, "running", "failed"),
                    lastError = code,
                    finishedAt = now,
                ),
            )
            log(now, code, "attempt=${job.attempts}")
        }
        return if (retryable && job.attempts < MAX_ATTEMPTS) Outcome.Retry(code) else Outcome.Failed(code)
    }

    private suspend fun save(job: JobRow) =
        db.jobs().setState(job.id, job.status, job.attempts, job.lastError, job.startedAt, job.finishedAt)

    private suspend fun log(now: Long, code: String, detail: String) =
        db.diagnostics().log(ErrorLogRow(at = now, area = "reflection", code = code, detail = detail))

    /** Everything the job reads from memory, read once, before the call. */
    private class Inputs(
        val contextText: String,
        val focusSummary: String,
        val checkinFacts: String,
        val deletedNotes: List<String>,
        val notes: Map<Long, NoteFacts>,
        val people: List<String>,
        val profile: Map<String, Boolean>,
        val earlierQuotes: List<String>,
    )

    private suspend fun gather(focusId: Long?): Inputs {
        val dao = db.memory()
        val allNotes = dao.allNotes()
        val evidence = dao.allEvidence().groupBy { it.noteId }
        val active = allNotes.filter { it.status == "active" }
        val noteLines = active.map { n ->
            ReflectionInput.NoteLine(
                id = n.id,
                kind = n.kind,
                confidence = n.confidence,
                timesSeen = n.timesSeen,
                firstSeen = n.firstSeen,
                lastSeen = n.lastSeen,
                text = n.text,
                evidence = (evidence[n.id] ?: emptyList()).takeLast(EVIDENCE_PER_NOTE)
                    .map { ReflectionInput.EvidenceLine(it.kind, it.text, it.day) },
            )
        }
        val profile = dao.profile()
        val people = dao.people()
        val quotes = dao.recentQuotes(ReflectionInput.EARLIER_QUOTES).reversed().map { it.text }
        val events = dao.recentEvents(ReflectionInput.RECENT_EVENTS).reversed().map { ev ->
            val day = TimeFacts.local(Instant.ofEpochMilli(ev.createdAt), settings.timeZone()).toLocalDate().toString()
            ReflectionInput.EventLine(day, ev.situation, ev.action, ev.outcome)
        }
        val context = ReflectionInput.context(
            profile = profile.map { ReflectionInput.ProfileLine(it.key, it.value, it.confirmed == 1) },
            people = people.map { ReflectionInput.PersonLine(it.name, it.relation, it.notes) },
            notes = noteLines,
            events = events,
            earlierQuotes = quotes,
        )
        return Inputs(
            contextText = context,
            focusSummary = focusId?.let { dao.focus(it)?.summary }.orEmpty(),
            // The app's own counts, the only numbers a note may cite as evidence (copied word for word).
            checkinFacts = checkinFactsNow(),
            deletedNotes = dao.deletedNoteTexts(),
            notes = allNotes.associate { n ->
                n.id to NoteFacts(n.kind, n.confidence, n.status, n.editedByUser == 1, n.text, n.timesSeen)
            },
            people = people.map { it.name },
            profile = profile.associate { it.key to (it.confirmed == 1) },
            earlierQuotes = ReflectionInput.earlierQuotesShown(noteLines, quotes),
        )
    }

    private fun dayKey(at: Long, tz: String, boundary: Int): String =
        Planner.dayKey(Instant.ofEpochMilli(at).toString(), tz, boundary).toString()

    /** The day of his last new message, written out ("Saturday 10 October 2026"). Code writes dates. */
    private fun sessionDate(selection: ReflectionInput.Selection, tz: String, boundary: Int): String {
        val last = selection.messages.last { it.role == "user" }
        return LocalDate.parse(dayKey(last.createdAt, tz, boundary)).format(DATE)
    }

    companion object {
        const val MAX_ATTEMPTS = 3
        private const val STALE_RUNNING_MS = 30 * 60 * 1000L
        private const val EVIDENCE_PER_NOTE = 2
        private val DATE = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ENGLISH)
    }
}
