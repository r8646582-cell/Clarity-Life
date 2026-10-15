package com.clarity.life

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.clarity.life.data.StartupRepair
import com.clarity.life.ui.ClarityRoot
import com.clarity.life.ui.theme.ClarityTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var startupRepair: StartupRepair

    @Inject lateinit var reflections: com.clarity.life.memory.ReflectionScheduler

    @Inject lateinit var search: com.clarity.life.memory.SearchIndex

    @Inject lateinit var indexer: com.clarity.life.memory.EmbeddingIndexer

    @Inject lateinit var reminders: com.clarity.life.promise.Reminders

    @Inject lateinit var writing: com.clarity.life.writing.WritingScheduler

    @Inject lateinit var settings: com.clarity.life.settings.SettingsRepository

    @Inject lateinit var maintenance: com.clarity.life.maintenance.MaintenanceScheduler

    /** App lock (spec section 9): locked until he unlocks, again after more than a minute away. */
    private var locked by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Locked from the first frame when the lock may be on; the setting is read straight after.
        // A turn of the phone recreates the screen; that is not leaving the app, so it does not lock again.
        // The unlock lives in this process only: when the phone killed the app in the background, a saved
        // screen state must never reopen it unlocked.
        lockOn = com.clarity.life.security.AppLock.lastKnownOn(this)
        locked = needsLock()
        com.clarity.life.security.AppLock.hideInRecents(this, com.clarity.life.settings.DefaultSettings.HIDE_IN_RECENTS_ON)
        lifecycleScope.launch {
            try {
                lockOn = settings.getBool(com.clarity.life.settings.SettingsRepository.Keys.APP_LOCK_ON, com.clarity.life.settings.DefaultSettings.APP_LOCK_ON)
                com.clarity.life.security.AppLock.rememberOn(this@MainActivity, lockOn)
                if (!lockOn) locked = false
                com.clarity.life.security.AppLock.hideInRecents(
                    this@MainActivity,
                    settings.getBool(com.clarity.life.settings.SettingsRepository.Keys.HIDE_IN_RECENTS_ON, com.clarity.life.settings.DefaultSettings.HIDE_IN_RECENTS_ON),
                )
                if (locked) com.clarity.life.security.AppLock.ask(this@MainActivity) { unlock() }
            } catch (e: Exception) {
                CrashLog.record(this@MainActivity, e)
                // Fail closed, but never shut out: the phone's own lock screen always opens it.
                if (locked) com.clarity.life.security.AppLock.ask(this@MainActivity) { unlock() }
            }
        }
        // The repair sweep (spec section 18, point 6): a reply the process died in the middle of becomes
        // interrupted, with a Try again button, and a job left running goes back to pending.
        // Then anything he said that was never reflected (the phone was off, the job was stopped) is picked up.
        // Once per process, not on every turn of the phone: a reply or a job running right now is not stale.
        // In the process's own scope, not the screen's: a turn of the phone must not cut the sweep short.
        // Singletons only, so the old screen is not held while the sweep runs.
        val app = applicationContext
        val settings = settings
        val startupRepair = startupRepair
        val reminders = reminders
        val reflections = reflections
        val search = search
        val indexer = indexer
        val writing = writing
        val maintenance = maintenance
        startup.launch {
            try {
                // A saved deepseek-reasoner or deepseek-chat becomes the faster default (10 Oct 2026).
                settings.moveRetiredModels()
                startupRepair.run(System.currentTimeMillis())
                // Alarms can be lost (a force stop, a phone that killed the app); set every reminder again.
                reminders.syncAll()
                reflections.sweep(System.currentTimeMillis())
                // An index from an older version (Max plan Phase 2 added the records) is rebuilt once.
                search.rebuildIfStale()
                indexer.schedule()
                // Reviews and the weekly letter: catch up on anything missed, and keep next Sunday scheduled.
                writing.onStart(System.currentTimeMillis())
                // The nightly maintenance: database check, repairs, reminders, and the automatic encrypted
                // backup when one is due (it does nothing for backups until a folder and a passphrase are set).
                maintenance.schedule()
                // Last, as it takes longest: search made ready, so the first message does not wait for it.
                search.warmUp()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                CrashLog.record(app, e)
            }
        }
        setContent {
            ClarityTheme {
                if (locked) {
                    com.clarity.life.ui.LockScreen(onUnlock = { com.clarity.life.security.AppLock.ask(this) { unlock() } })
                } else {
                    ClarityRoot()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Talk, Plan and Promises follow the phone's zone too: back from a flight, they show the new day.
        com.clarity.life.settings.SettingsRepository.refreshPhoneZone()
    }

    override fun onStop() {
        super.onStop()
        leftAt = System.currentTimeMillis()
    }

    override fun onRestart() {
        super.onRestart()
        // Decided at once from the last known setting, so the conversation never shows for a frame first.
        if (needsLock()) {
            locked = true
            com.clarity.life.security.AppLock.ask(this) { unlock() }
        }
        // The lock setting may have changed in Settings while the app was open.
        lifecycleScope.launch {
            lockOn = settings.getBool(com.clarity.life.settings.SettingsRepository.Keys.APP_LOCK_ON, com.clarity.life.settings.DefaultSettings.APP_LOCK_ON)
            com.clarity.life.security.AppLock.rememberOn(this@MainActivity, lockOn)
            if (!lockOn) locked = false
        }
    }

    private fun needsLock(): Boolean =
        lockOn && com.clarity.life.security.AppLock.canLock(this) &&
            !(unlockedInProcess && System.currentTimeMillis() - leftAt <= com.clarity.life.security.AppLock.GRACE_MS)

    private fun unlock() {
        locked = false
        unlockedInProcess = true
    }

    @Deprecated("Only used on Android 8 and 9, where the screen-lock confirm screen answers here.")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == com.clarity.life.security.AppLock.CONFIRM_REQUEST && resultCode == RESULT_OK) unlock()
    }

    private companion object {
        /** Process-wide, so a new screen in the same process (a turn of the phone) keeps them. */
        val startup = StartupOnce(
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default),
        )
        @Volatile var lockOn = com.clarity.life.settings.DefaultSettings.APP_LOCK_ON
        @Volatile var unlockedInProcess = false
        @Volatile var leftAt = 0L
    }
}
