package com.clarity.life.data

import android.app.ActivityManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.work.WorkManager
import com.clarity.life.promise.Reminders
import com.clarity.life.security.SecretStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Erase everything (spec section 2, Settings, Your data; confirmed by typing ERASE). Everything this app
 * keeps on the phone goes: the database, the sealed database key, API keys and backup passphrase, settings,
 * the crash log, every alarm, notification and background job. The encrypted backups in the folder he
 * chose are his files outside the app and stay; the app gives up its access to that folder.
 *
 * Android's own "clear data" does the wiping and closes the app; the next launch is a first launch. The
 * steps before it make sure nothing rings or runs in between, even if the phone is slow to close the app.
 */
@Singleton
class Eraser @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: ClarityDatabase,
    private val reminders: Reminders,
    private val secrets: SecretStore,
) {
    /** The exact word he must type. */
    fun matches(typed: String): Boolean = typed.trim() == WORD

    suspend fun eraseEverything() {
        // Alarms and notifications first: a reminder must never ring for a promise that no longer exists.
        runCatching { db.promises().remindersAfter(0).forEach { reminders.cancel(it.id) } }
        reminders.cancel(Reminders.TEST_ID)
        NotificationManagerCompat.from(context).cancelAll()
        WorkManager.getInstance(context).cancelAllWork()
        context.contentResolver.persistedUriPermissions.forEach { grant ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    grant.uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        // The sealed key goes before the database, so even a half-finished erase leaves nothing readable.
        secrets.eraseEverything()
        db.close()
        val am = context.getSystemService(ActivityManager::class.java)
        if (am.clearApplicationUserData()) return
        // Android refused (it should not): delete by hand, then close the app so it starts fresh.
        listOf("", "-wal", "-shm", "-journal").forEach { context.getDatabasePath(ClarityDatabase.NAME + it).delete() }
        context.filesDir.listFiles()?.forEach(File::deleteRecursively)
        context.cacheDir.listFiles()?.forEach(File::deleteRecursively)
        File(context.applicationInfo.dataDir, "shared_prefs").listFiles()?.forEach(File::delete)
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    companion object {
        const val WORD = "ERASE"
    }
}
