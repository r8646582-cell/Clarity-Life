package com.clarity.life.writing

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
import com.clarity.life.core.ReviewTimes
import com.clarity.life.settings.SettingsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes what is due: the review rows (facts in code), the notes under them, the weekly letter, the
 * quarterly chapter, then the monthly memory gardening. Runs on
 * every app start (catch-up) and each Sunday at 19:00 and 19:30; Try again on a review or a letter runs it for
 * that one with fresh attempts. Every run is safe to repeat: the job keys make a second run a no-op.
 */
class WritingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun reviews(): ReviewWriter
        fun letters(): LetterWriter
        fun chapters(): ChapterWriter
        fun gardening(): GardeningJob
        fun scheduler(): WritingScheduler
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        val now = System.currentTimeMillis()
        var again = false
        val retryReview = inputData.getLong(KEY_RETRY_REVIEW, -1L)
        val retryLetter = inputData.getString(KEY_RETRY_LETTER)
        try {
            for (review in deps.reviews().catchUp(now)) {
                val outcome = deps.reviews().writeNote(review.id, now, retry = review.id == retryReview)
                if (outcome is ReviewWriter.Outcome.Retry) again = true
            }
            val week = retryLetter?.let(LocalDate::parse) ?: deps.letters().dueWeek(now)
            if (week != null) {
                val outcome = deps.letters().write(week, now, retry = retryLetter != null)
                if (outcome is LetterWriter.Outcome.Retry) again = true
            }
            // The quarterly chapter, once the quarter is over (a no-op once it is written or skipped).
            val chapter = deps.chapters().write(deps.chapters().dueQuarter(now), now)
            if (chapter is ChapterWriter.Outcome.Retry) again = true
            // Monthly memory gardening (and an extra tidy when a kind of note is full).
            if (deps.gardening().runDue(now) is GardeningJob.Outcome.Retry) again = true
        } finally {
            deps.scheduler().scheduleNextSunday(now)
        }
        return if (again && runAttemptCount < 3) Result.retry() else Result.success()
    }

    companion object {
        const val KEY_RETRY_REVIEW = "retry_review"
        const val KEY_RETRY_LETTER = "retry_letter"
    }
}

@Singleton
class WritingScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {
    /** On app start: catch up on anything missed, and make sure next Sunday is scheduled. */
    suspend fun onStart(now: Long) {
        enqueue("writing-catch-up", 0, ExistingWorkPolicy.KEEP)
        scheduleNextSunday(now)
    }

    /** Try again, on a review note that is delayed. */
    fun retryReview(reviewId: Long) = enqueue("writing-retry", 0, ExistingWorkPolicy.REPLACE, KEY to reviewId)

    /** Try again, on a letter that is delayed. */
    fun retryLetter(monday: LocalDate) =
        enqueue("writing-retry", 0, ExistingWorkPolicy.REPLACE, WritingWorker.KEY_RETRY_LETTER to monday.toString())

    /** The next Sunday run: 19:00 for the review, 19:30 for the letter, whichever comes first. */
    suspend fun scheduleNextSunday(now: Long) {
        val tz = settings.timeZone()
        val local = com.clarity.life.core.TimeFacts.local(java.time.Instant.ofEpochMilli(now), tz)
        val next = minOf(ReviewTimes.nextSunday(local, ReviewTimes.REVIEW_AT), ReviewTimes.nextSunday(local, ReviewTimes.LETTER_AT))
        // Real time until then, not wall-clock time: across a clock change the two differ by an hour, and a
        // run an hour early found nothing due and could not schedule the next one (same name, KEEP).
        val at = next.atZone(java.time.ZoneId.of(tz)).toInstant()
        val delay = Duration.between(java.time.Instant.ofEpochMilli(now), at).toMillis().coerceAtLeast(0) + ONE_MINUTE
        // Named by its time: scheduling the same Sunday twice keeps one, and a running Sunday job that
        // schedules the next one never replaces (and so cancels) itself.
        enqueue("writing-sunday-$next", delay, ExistingWorkPolicy.KEEP)
    }

    private fun enqueue(name: String, delayMs: Long, policy: ExistingWorkPolicy, vararg data: Pair<String, Any?>) {
        val request = OneTimeWorkRequestBuilder<WritingWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .setInputData(workDataOf(*data))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(name, policy, request)
    }

    companion object {
        private const val KEY = WritingWorker.KEY_RETRY_REVIEW
        private const val ONE_MINUTE = 60_000L
    }
}
