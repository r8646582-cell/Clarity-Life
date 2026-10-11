package com.clarity.life.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

/**
 * The automatic weekly backup (spec section 9). It runs only once a folder and a passphrase are set; a failed
 * run leaves one plain line in Settings and an error code in the log, and the next week tries again.
 */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun backups(): BackupRepository
    }

    override suspend fun doWork(): Result {
        val backups = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java).backups()
        if (!backups.ready()) return Result.success()
        backups.backUp()
        return Result.success()
    }

    companion object {
        private const val NAME = "backup-weekly"

        /** Once a week from now on; scheduling again keeps the schedule that is already there. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<BackupWorker>(7, TimeUnit.DAYS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
