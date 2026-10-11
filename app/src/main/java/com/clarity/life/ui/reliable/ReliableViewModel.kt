package com.clarity.life.ui.reliable

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.lifecycle.ViewModel
import com.clarity.life.promise.Reminders
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Settings, Keep reminders reliable (docs/mockups/Reliable.dc.html). Xiaomi phones (HyperOS) stop apps in
 * the background to save battery, so a reminder can stay silent. This reads what Android lets an app read
 * and opens the right system page for each setting. Autostart cannot be read by any app, so it is always
 * "check by hand", with the steps.
 */
@HiltViewModel
class ReliableViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val reminders: Reminders,
) : ViewModel() {

    data class State(
        val batteryUnrestricted: Boolean = false,
        val exactAlarms: Boolean = false,
        val notifications: Boolean = false,
        val testSet: Boolean = false,
    ) {
        /** Autostart is not counted: it cannot be read, so it is always shown as "check by hand". */
        val needAttention: Int get() = listOf(batteryUnrestricted, exactAlarms, notifications).count { !it }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** Called whenever the page is shown, so coming back from a system page shows the new state. */
    fun refresh() {
        val power = context.getSystemService(PowerManager::class.java)
        _state.value = _state.value.copy(
            batteryUnrestricted = power.isIgnoringBatteryOptimizations(context.packageName),
            exactAlarms = reminders.exactAllowed(),
            notifications = reminders.notificationsAllowed(),
        )
    }

    /** Battery: Android's own "allow to run in the background without limits" question. */
    fun batteryIntent(): List<Intent> = listOf(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        appDetails(),
    )

    /** Autostart lives in Xiaomi's Security app; on other phones, the app's own settings page. */
    fun autostartIntent(): List<Intent> = listOf(
        Intent().setComponent(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        ),
        appDetails(),
    )

    fun exactAlarmIntent(): List<Intent> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
        }
        add(appDetails())
    }

    fun notificationIntent(): List<Intent> = listOf(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        appDetails(),
    )

    private fun appDetails() =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    /** Opens the first page this phone has. Returns false when none opened. */
    fun open(activity: Context, intents: List<Intent>): Boolean {
        for (intent in intents) {
            try {
                activity.startActivity(intent)
                return true
            } catch (e: ActivityNotFoundException) {
                continue
            } catch (e: SecurityException) {
                continue
            }
        }
        return false
    }

    /** "Check it for real": a test reminder in about a minute. */
    fun test() {
        reminders.setTest()
        _state.value = _state.value.copy(testSet = true)
    }
}
