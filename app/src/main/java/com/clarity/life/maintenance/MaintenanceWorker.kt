package com.clarity.life.maintenance

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.clarity.life.core.Maintenance
import com.clarity.life.settings.SettingsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** The nightly run of [MaintenanceRunner]; it schedules the next night before it ends, whatever happened. */
class MaintenanceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun runner(): MaintenanceRunner
        fun scheduler(): MaintenanceScheduler
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        try {
            deps.runner().runOnce()
        } finally {
            deps.scheduler().schedule()
        }
        return Result.success()
    }
}

@Singleton
class MaintenanceScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {
    /**
     * The next 03:30, named by its date: scheduling the same night twice keeps one, and a running night that
     * schedules the next one never replaces (and so cancels) itself. Only while the battery is not low; a night
     * the phone was off runs as soon as Android allows, and the job key keeps it to once a day.
     */
    suspend fun schedule(now: Long = System.currentTimeMillis()) {
        val delay = Maintenance.delayToNextRun(Instant.ofEpochMilli(now), settings.timeZone())
        val night = Instant.ofEpochMilli(now + delay).toString().take(10)
        val request = OneTimeWorkRequestBuilder<MaintenanceWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        val work = WorkManager.getInstance(context)
        work.enqueueUniqueWork("maintenance-$night", ExistingWorkPolicy.KEEP, request)
        // The weekly backup now runs inside the nightly maintenance; the old schedule is retired.
        work.cancelUniqueWork(com.clarity.life.backup.BackupWorker.NAME)
    }
}
