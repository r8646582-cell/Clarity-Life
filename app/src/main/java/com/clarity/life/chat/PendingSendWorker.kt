package com.clarity.life.chat

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.clarity.life.ai.AiFailure
import com.clarity.life.ai.ChatChunk
import com.clarity.life.core.CardParser
import com.clarity.life.core.KnownIds
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * The offline queue (docs/CLARITY-V2-BUILD-SPEC.md section 5). A message sent with no signal is stored as
 * `pending` and shown as "Waiting to send". This worker runs when the network is back: it sends the waiting
 * messages in order (they become part of the conversation) and asks for the reply once. The reply is saved
 * about once a second, so if the owner is in the app it appears as it is written.
 *
 * Unique work per conversation. At most [MAX_RETRIES] retries per cycle, with backoff. A reply that fails
 * after it started is a normal failed reply with Try again; nothing loops.
 */
class PendingSendWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun chat(): ChatRepository

        fun reflections(): com.clarity.life.memory.ReflectionScheduler

        fun indexer(): com.clarity.life.memory.EmbeddingIndexer
    }

    override suspend fun doWork(): Result {
        val conversationId = inputData.getLong(KEY_CONVERSATION, -1L)
        if (conversationId <= 0) return Result.failure()
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        val chat = deps.chat()
        if (chat.pendingMessages(conversationId).isEmpty()) return Result.success()
        if (!Connectivity.isOnline(applicationContext)) {
            return if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
        }
        val endpoint = try {
            chat.endpointForChat("free")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            null
        } ?: return Result.success() // no key yet: the messages keep waiting; Settings, Coach explains
        val now = System.currentTimeMillis()
        chat.flushPending(conversationId)
        deps.indexer().schedule()
        val coachId = chat.openCoachReply(conversationId, now, endpoint.model)
        val partial = StringBuilder()
        var lastSave = 0L
        var finishReason: String? = null
        var usage: com.clarity.life.ai.Usage? = null
        try {
            val packet = chat.buildPacket(conversationId, coachId, now)
            chat.stream(endpoint, packet.request).collect { chunk ->
                when (chunk) {
                    is ChatChunk.Text -> {
                        partial.append(chunk.text)
                        val at = System.currentTimeMillis()
                        if (at - lastSave > 1_000) {
                            lastSave = at
                            chat.savePartial(coachId, partial.toString())
                        }
                    }
                    is ChatChunk.Finished -> {
                        finishReason = chunk.reason
                        usage = chunk.usage
                    }
                    else -> Unit
                }
            }
            val prose = CardParser.parseReply(partial.toString(), "free", KnownIds()).prose
            chat.closeCoachReply(
                conversationId = conversationId,
                messageId = coachId,
                raw = prose,
                chatType = "free",
                known = KnownIds(),
                finishReason = finishReason,
                usage = usage,
                now = System.currentTimeMillis(),
                modelLabel = endpoint.label,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            withContext(NonCancellable) {
                chat.settleIfStreaming(coachId, partial.toString(), AiFailure("cancelled", "the reply was stopped", retryable = true), System.currentTimeMillis())
            }
            throw e
        } catch (e: Throwable) {
            val failure = e as? AiFailure ?: AiFailure("unexpected", "the reply could not be finished", retryable = true)
            chat.settleIfStreaming(coachId, partial.toString(), failure, System.currentTimeMillis())
        }
        try {
            deps.reflections().afterReply(conversationId)
        } catch (e: Exception) {
            com.clarity.life.CrashLog.record(applicationContext, e)
        }
        return Result.success()
    }

    companion object {
        const val KEY_CONVERSATION = "conversation_id"
        const val MAX_RETRIES = 2

        /** One unique job per conversation; a second message while offline joins the same job. */
        fun enqueue(context: Context, conversationId: Long) {
            val request = OneTimeWorkRequestBuilder<PendingSendWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .setInputData(workDataOf(KEY_CONVERSATION to conversationId))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("pending-send-$conversationId", ExistingWorkPolicy.KEEP, request)
        }
    }
}
