package com.clarity.life.promise

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.clarity.life.MainActivity
import com.clarity.life.R
import com.clarity.life.data.ClarityDatabase
import com.clarity.life.data.entity.ErrorLogRow
import com.clarity.life.data.entity.PromiseRow
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reminders for promises (spec section 0, rule 8: the only notifications are reminders he set by tapping).
 *
 * A reminder is an exact alarm at the promise's due moment, set only for an open promise whose card said
 * "Reminder on". Alarms do not survive a restart of the phone, so [syncAll] sets every one again after a
 * restart, after an app update and on every app start; [sync] is safe to call any number of times, because
 * it always looks at the promise as it is now: open, with a reminder, still ahead means set, anything else
 * means cancel.
 *
 * Adapted from the old repo's promise/Reminders.kt.
 */
@Singleton
class Reminders @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: ClarityDatabase,
) {
    private val alarms get() = context.getSystemService(AlarmManager::class.java)

    /** True when Android lets the app ring at the exact minute (Settings, Keep reminders reliable). */
    fun exactAllowed(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()

    fun notificationsAllowed(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** Sets or cancels the alarm for one promise, whatever it was before. */
    suspend fun sync(promiseId: Long, now: Long = System.currentTimeMillis()) {
        val p = db.promises().get(promiseId)
        if (p != null && p.status == "open" && p.remind == 1 && p.dueAt > now) set(p.id, p.dueAt) else cancel(promiseId)
    }

    /** After a restart, an update or an app start: every open reminder still ahead is set again. */
    suspend fun syncAll(now: Long = System.currentTimeMillis()): Int {
        val due = db.promises().remindersAfter(now)
        for (p in due) set(p.id, p.dueAt)
        return due.size
    }

    /**
     * The self-check (docs/MAX-PLAN.md, Phase 1, step 8): every open reminder still ahead must have an alarm
     * waiting. One that has none (a phone that cleared the app's alarms, a force stop) is set again at once, and
     * the count is logged without a word of the promise. Returns how many were missing. Run by the nightly
     * maintenance and shown in Settings, Health.
     */
    suspend fun heal(now: Long = System.currentTimeMillis()): Int {
        val missing = db.promises().remindersAfter(now).filter { !isSet(it.id) }
        for (p in missing) set(p.id, p.dueAt)
        if (missing.isNotEmpty()) {
            try {
                db.diagnostics().log(ErrorLogRow(at = now, area = "reminder", code = "alarm_missing", detail = "n=${missing.size}"))
            } catch (e: Exception) {
                // The log is best effort.
            }
        }
        return missing.size
    }

    /** For Settings, Health: how many reminders are ahead, and how many of them have no alarm right now. */
    suspend fun status(now: Long = System.currentTimeMillis()): Pair<Int, Int> {
        val ahead = db.promises().remindersAfter(now)
        return ahead.size to ahead.count { !isSet(it.id) }
    }

    /** "Check it for real" in Settings: a test reminder one minute from now, with no promise behind it. */
    fun setTest(now: Long = System.currentTimeMillis()) = set(TEST_ID, now + 60_000L)

    private fun set(promiseId: Long, at: Long) {
        val pending = alarmIntent(promiseId)
        if (exactAllowed()) {
            // Exact, and allowed to ring while the phone dozes.
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        } else {
            // Without the exact-alarm permission Android may ring a few minutes late; Settings says so.
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    fun cancel(promiseId: Long) {
        val pending = alarmIntent(promiseId)
        alarms.cancel(pending)
        pending.cancel()
        NotificationManagerCompat.from(context).cancel(notificationId(promiseId))
    }

    /** Whether an alarm is waiting for this promise (used by the emulator test). */
    fun isSet(promiseId: Long): Boolean =
        PendingIntent.getBroadcast(
            context,
            requestCode(promiseId),
            ringIntent(promiseId),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) != null

    private fun ringIntent(promiseId: Long) = Intent(context, ReminderReceiver::class.java)
        .setAction(ReminderReceiver.ACTION_RING)
        .putExtra(ReminderReceiver.EXTRA_PROMISE, promiseId)

    private fun alarmIntent(promiseId: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        requestCode(promiseId),
        ringIntent(promiseId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * The alarm went off. The promise is read again first: if he answered it, let it go or moved it in the
     * meantime, nothing rings.
     */
    suspend fun ring(promiseId: Long, now: Long = System.currentTimeMillis()) {
        if (promiseId == TEST_ID) {
            show(TEST_ID, "Test reminder", "If you can read this, reminders work on this phone.")
            return
        }
        val p = db.promises().get(promiseId) ?: return
        if (p.status != "open" || p.remind != 1) return
        // A reminder that arrives more than a day late (the phone was off) is not shown; Plan, Promises has it.
        if (now - p.dueAt > LATE_LIMIT_MS) return
        show(p.id, p.text, reminderLine(p))
    }

    private fun reminderLine(p: PromiseRow): String =
        if (p.why.isNotBlank()) "Now, as you planned. ${p.why}" else "Now, as you planned."

    private suspend fun show(id: Long, title: String, text: String) {
        channel()
        if (!notificationsAllowed()) {
            logQuietly("notifications_off")
            return
        }
        val open = PendingIntent.getActivity(
            context,
            requestCode(id),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // On the lock screen only "A reminder you set" shows; his words appear once the phone is unlocked.
        val public = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_reminder_notification)
            .setContentTitle("A reminder you set")
            .build()
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_reminder_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(notificationId(id), n)
        } catch (e: SecurityException) {
            logQuietly("notifications_denied")
        }
    }

    private fun channel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Reminders you set", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Only for promises where you chose Reminder on. Nothing else uses this."
                    setShowBadge(false)
                },
            )
        }
    }

    private suspend fun logQuietly(code: String) {
        // The log never holds the promise's words.
        try {
            db.diagnostics().log(ErrorLogRow(at = System.currentTimeMillis(), area = "reminder", code = code, detail = ""))
        } catch (e: Exception) {
            // The log is best effort; a reminder must never crash because of it.
        }
    }

    companion object {
        const val CHANNEL = "reminders"
        const val TEST_ID = 0L
        private const val LATE_LIMIT_MS = 24 * 60 * 60 * 1000L

        private fun requestCode(promiseId: Long): Int = (promiseId % Int.MAX_VALUE).toInt()

        fun notificationId(promiseId: Long): Int = 10_000 + (promiseId % 1_000_000).toInt()
    }
}
