package com.clarity.life.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * The old weekly backup schedule. Since the nightly maintenance (maintenance/MaintenanceRunner.kt) makes the
 * backup when one is due, this is no longer scheduled, and the scheduler cancels it. The class stays so a run
 * Android already had queued from an older version still finds it: it backs up only when one is due.
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
        if (backups.due()) backups.backUp()
        return Result.success()
    }

    companion object {
        const val NAME = "backup-weekly"
    }
}
