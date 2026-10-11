package com.clarity.life.memory

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
import com.clarity.life.data.ClarityDatabase
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs [ReflectionJob] for one conversation. WorkManager creates it with its default factory (no custom
 * initializer to get wrong); the job comes from Hilt through an entry point.
 */
class ReflectionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun reflectionJob(): ReflectionJob

        fun indexer(): EmbeddingIndexer
    }

    override suspend fun doWork(): Result {
        val conversationId = inputData.getLong(KEY_CONVERSATION, -1L)
        if (conversationId <= 0) return Result.failure()
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        return when (deps.reflectionJob().run(conversationId)) {
            is ReflectionJob.Outcome.Retry -> Result.retry()
            // A new summary needs its meaning vector.
            is ReflectionJob.Outcome.Done -> {
                deps.indexer().schedule()
                Result.success()
            }
            // Failed is recorded in the job table and the log; WorkManager has nothing more to do.
            else -> Result.success()
        }
    }

    companion object {
        const val KEY_CONVERSATION = "conversation_id"
    }
}

/**
 * When a conversation is reflected (spec section 7): 30 minutes after the last reply (each new reply moves
 * the time back), right away when he starts another conversation, and on app start for anything left over.
 * A new day needs no rule of its own: the idle time has passed by then.
 */
@Singleton
class ReflectionScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: ClarityDatabase,
) {

    /** After every reply: wait until he has been quiet for [IDLE_MINUTES]. */
    fun afterReply(conversationId: Long) = enqueue(conversationId, TimeUnit.MINUTES.toMillis(IDLE_MINUTES), replace = true)

    /** The history window moved: reflect this conversation now, so EARLIER carries what left the window. */
    fun reflectNow(conversationId: Long) = enqueue(conversationId, 0, replace = false, now = true)

    /** He moved on (a new conversation): reflect the ones he left now, without waiting. */
    suspend fun reflectLeftBehind(exceptConversationId: Long?) {
        for (u in db.memory().unreflected()) {
            if (u.conversationId != exceptConversationId) enqueue(u.conversationId, 0, replace = false, now = true)
        }
    }

    /** On app start: anything not reflected yet, keeping work that is already scheduled. */
    suspend fun sweep(now: Long) {
        val idle = TimeUnit.MINUTES.toMillis(IDLE_MINUTES)
        for (u in db.memory().unreflected()) {
            enqueue(u.conversationId, (u.lastAt + idle - now).coerceIn(0, idle), replace = false)
        }
    }

    /**
     * A reflection asked for now has its own name and is kept: the next reply's 30-minute wait would
     * otherwise replace (cancel) it, mid-call, and EARLIER would stay behind while he keeps talking.
     * Running twice is harmless; the job key makes the second one a no-op.
     */
    private fun enqueue(conversationId: Long, delayMs: Long, replace: Boolean, now: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<ReflectionWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .setInputData(workDataOf(ReflectionWorker.KEY_CONVERSATION to conversationId))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            if (now) "reflection-now-$conversationId" else "reflection-$conversationId",
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }

    companion object {
        const val IDLE_MINUTES = 30L
        private const val BACKOFF_MINUTES = 2L
    }
}
