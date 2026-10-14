package com.clarity.life.promise

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.clarity.life.CrashLog
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Receives a reminder alarm. Not exported: only the app's own alarm can reach it. */
@AndroidEntryPoint
class ReminderReceiver : BroadcastReceiver() {

    @Inject lateinit var reminders: Reminders

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RING) return
        val id = intent.getLongExtra(EXTRA_PROMISE, -1L)
        runAsync(context) { reminders.ring(id) }
    }

    companion object {
        const val ACTION_RING = "com.clarity.life.action.REMINDER"
        const val EXTRA_PROMISE = "promise"
    }
}

/**
 * The moments when every alarm must be set again, because Android forgets them: the phone restarted
 * (including the "quick boot" some Xiaomi phones send), the app was updated, the clock or the time zone was
 * changed, or Android granted the exact-alarm permission. Any of these only ever re-sets what is already saved.
 */
@AndroidEntryPoint
class RestartReceiver : BroadcastReceiver() {

    @Inject lateinit var reminders: Reminders

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        runAsync(context) { reminders.syncAll() }
    }

    private companion object {
        val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            "android.intent.action.QUICKBOOT_POWERON",
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
        )
    }
}

/** Database work off the main thread, while Android keeps the process alive for the receiver. */
private fun BroadcastReceiver.runAsync(context: Context, work: suspend () -> Unit) {
    val pending = goAsync()
    CoroutineScope(Dispatchers.IO).launch {
        try {
            work()
        } catch (e: Throwable) {
            CrashLog.record(context, e)
        } finally {
            pending.finish()
        }
    }
}
