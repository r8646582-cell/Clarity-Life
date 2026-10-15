package com.clarity.life.writing

import androidx.room.withTransaction
import com.clarity.life.ai.AiFailure
import com.clarity.life.ai.AnthropicClient
import com.clarity.life.ai.ChatChunk
import com.clarity.life.ai.ChatMessage
import com.clarity.life.ai.ChatRequest
import com.clarity.life.ai.Endpoint
import com.clarity.life.ai.OpenAiCompatibleClient
import com.clarity.life.data.ClarityDatabase
import com.clarity.life.data.StateMachines
import com.clarity.life.data.entity.ErrorLogRow
import com.clarity.life.data.entity.JobRow
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * One background model call: the filled prompt is the whole request (no prompt text is ever written in Kotlin),
 * the reply is collected as text, and the usage counter is updated. Thinking text is never kept.
 */
@Singleton
class BackgroundCall @Inject constructor(
    private val db: ClarityDatabase,
    private val mainClient: OpenAiCompatibleClient,
    private val anthropicClient: AnthropicClient,
) {
    class CutOff : Exception("cut_off")

    /**
     * Throws [AiFailure] when the provider fails, and [CutOff] when the reply hit the length limit. [kind] (one of
     * [com.clarity.life.data.UsageKind]) says which job made the call, for the cost split in Health.
     */
    suspend fun text(endpoint: Endpoint, prompt: String, month: String, kind: String): String {
        val request = ChatRequest(system = "", messages = listOf(ChatMessage(ChatMessage.USER, prompt)))
        val client = if (endpoint.provider == "anthropic") anthropicClient else mainClient
        val out = StringBuilder()
        var finish: String? = null
        client.stream(endpoint, request).collect { chunk ->
            when (chunk) {
                is ChatChunk.Text -> out.append(chunk.text)
                is ChatChunk.Thinking -> Unit
                is ChatChunk.Finished -> {
                    finish = chunk.reason
                    chunk.usage?.let { u -> db.diagnostics().addUsage(month, kind, 1, u.tokensIn, u.tokensOut, u.tokensCached) }
                }
            }
        }
        if (finish == "length") throw CutOff()
        return out.toString()
    }
}

/**
 * The job table for background writing (contracts/states.json): a deterministic key, so running the same work
 * twice finds it done; at most [MAX_ATTEMPTS] tries; a row left running by a dead process is taken back.
 */
@Singleton
class JobClaims @Inject constructor(private val db: ClarityDatabase) {

    sealed interface Claim {
        data class Run(val job: JobRow) : Claim

        /** Done, skipped, being done by another run, or out of attempts. */
        data class Settled(val status: String) : Claim
    }

    /** Read and taken in one transaction, so two runs (the Sunday one and a catch-up) can never both take it. */
    suspend fun claim(key: String, type: String, now: Long, retry: Boolean = false, max: Int = MAX_ATTEMPTS): Claim =
        db.withTransaction { claimInside(key, type, now, retry, max) }

    private suspend fun claimInside(key: String, type: String, now: Long, retry: Boolean, max: Int): Claim {
        db.jobs().insertIfNew(JobRow(key = key, type = type, createdAt = now))
        val row = db.jobs().byKey(key) ?: return Claim.Settled("missing")
        val pending = when (row.status) {
            "succeeded", "skipped" -> return Claim.Settled(row.status)
            "running" -> if (now - (row.startedAt ?: 0L) < STALE_RUNNING_MS) {
                return Claim.Settled("running")
            } else {
                row.copy(status = StateMachines.move(StateMachines.JOB, "running", "pending"))
            }
            // Try again (his tap) gives a failed job a fresh set of attempts.
            "failed" -> if (row.attempts >= max && !retry) {
                return Claim.Settled("failed")
            } else {
                row.copy(
                    status = StateMachines.move(StateMachines.JOB, "failed", "pending"),
                    attempts = if (retry) 0 else row.attempts,
                )
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
        return Claim.Run(job)
    }

    suspend fun succeed(job: JobRow, now: Long) =
        save(job.copy(status = StateMachines.move(StateMachines.JOB, "running", "succeeded"), finishedAt = now))

    /** Nothing to say (no answers, no conversations): settled with no call made. */
    suspend fun skip(key: String, type: String, now: Long) {
        db.jobs().insertIfNew(JobRow(key = key, type = type, createdAt = now))
        val row = db.jobs().byKey(key) ?: return
        if (row.status == "pending") save(row.copy(status = StateMachines.move(StateMachines.JOB, "pending", "skipped"), finishedAt = now))
    }

    /** A content-free code in the job and in the log. Returns true while attempts remain. */
    suspend fun fail(job: JobRow, area: String, code: String, now: Long, max: Int = MAX_ATTEMPTS): Boolean =
        withContext(NonCancellable) {
            save(job.copy(status = StateMachines.move(StateMachines.JOB, "running", "failed"), lastError = code, finishedAt = now))
            db.diagnostics().log(ErrorLogRow(at = now, area = area, code = code, detail = "attempt=${job.attempts}"))
            job.attempts < max
        }

    /** Hand a job back untouched (WorkManager stopped the work). */
    suspend fun release(job: JobRow) = withContext(NonCancellable) {
        save(job.copy(status = StateMachines.move(StateMachines.JOB, "running", "pending")))
    }

    suspend fun status(key: String): JobRow? = db.jobs().byKey(key)

    private suspend fun save(job: JobRow) =
        db.jobs().setState(job.id, job.status, job.attempts, job.lastError, job.startedAt, job.finishedAt)

    companion object {
        const val MAX_ATTEMPTS = 3
        private const val STALE_RUNNING_MS = 30 * 60 * 1000L
    }
}
