package com.clarity.life.chat

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.clarity.life.settings.DefaultSettings
import com.clarity.life.settings.SettingsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * The optional daily nudge (docs/CLARITY-V2-BUILD-SPEC.md rule 7): off by default. When it is on, one quiet
 * notification at 19:00, and only on a day with no chat yet. Never a streak, never a second reminder. Turning it
 * off cancels it. The text is fixed and says nothing about their life.
 */
object DailyNudge {
    private const val WORK = "daily-nudge"
    private const val CHANNEL = "daily_nudge"
    private const val NOTIFICATION_ID = 7107
    private val AT: LocalTime = LocalTime.of(19, 0)

    /** Plans the next 19:00 when the nudge is on; cancels the plan when it is off. */
    suspend fun ensure(context: Context, settings: SettingsRepository) {
        if (!settings.getBool(SettingsRepository.Keys.DAILY_NUDGE_ON, DefaultSettings.DAILY_NUDGE_ON)) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK)
            return
        }
        schedule(context, settings.timeZone(), ExistingWorkPolicy.KEEP)
    }

    internal fun schedule(context: Context, timeZone: String, policy: ExistingWorkPolicy) {
        val zone = ZoneId.of(timeZone)
        val now = LocalDateTime.now(zone)
        var next = now.toLocalDate().atTime(AT)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val delay = Duration.between(now.atZone(zone), next.atZone(zone)).toMillis().coerceAtLeast(60_000)
        val request = OneTimeWorkRequestBuilder<Worker>().setInitialDelay(delay, TimeUnit.MILLISECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, policy, request)
    }

    class Worker(private val ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        @EntryPoint
        @InstallIn(SingletonComponent::class)
        interface Deps {
            fun settings(): SettingsRepository

            fun chat(): ChatRepository
        }

        override suspend fun doWork(): Result {
            val deps = EntryPointAccessors.fromApplication(ctx, Deps::class.java)
            val settings = deps.settings()
            if (!settings.getBool(SettingsRepository.Keys.DAILY_NUDGE_ON, DefaultSettings.DAILY_NUDGE_ON)) return Result.success()
            try {
                if (deps.chat().todaysConversation(System.currentTimeMillis()) == null) notify(ctx)
            } catch (e: Exception) {
                com.clarity.life.CrashLog.record(ctx, e)
            }
            // Always plan tomorrow's, replacing nothing that is running (this name is the one that just ran).
            schedule(ctx, settings.timeZone(), ExistingWorkPolicy.REPLACE)
            return Result.success()
        }

        private fun notify(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Daily nudge", NotificationManager.IMPORTANCE_LOW).apply {
                        setShowBadge(false)
                        setSound(null, null)
                        enableVibration(false)
                        description = "One quiet note in the evening on a day you have not talked yet. Off unless you turn it on."
                    },
                )
            }
            val open = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
            val intent = PendingIntent.getActivity(
                context, 0, open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val note = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(com.clarity.life.R.drawable.ic_reminder_notification)
                .setContentTitle("Clarity")
                .setContentText("Anything on your mind today?")
                .setContentIntent(intent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            try {
                manager.notify(NOTIFICATION_ID, note)
            } catch (e: SecurityException) {
                // Notifications are not allowed for the app: nothing to show, nothing to do.
            }
        }
    }
}
