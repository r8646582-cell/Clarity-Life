package com.clarity.life.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clarity.life.ai.AnthropicClient
import com.clarity.life.ai.OpenAiCompatibleClient
import com.clarity.life.ai.TestResult
import com.clarity.life.settings.DefaultSettings
import com.clarity.life.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Settings, Coach (docs/mockups/SettingsCoach.dc.html): provider, base URL, key, the two model names and
 * Test connection. The model names are typed freely on purpose, so a retired model never needs a new APK.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val openAi: OpenAiCompatibleClient,
    private val anthropic: AnthropicClient,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val search: com.clarity.life.memory.SearchIndex,
    private val indexer: com.clarity.life.memory.EmbeddingIndexer,
    private val backups: com.clarity.life.backup.BackupRepository,
    private val exporter: com.clarity.life.backup.Exporter,
    private val eraser: com.clarity.life.data.Eraser,
) : ViewModel() {

    data class State(
        val provider: String = DefaultSettings.PROVIDER,
        val baseUrl: String = DefaultSettings.BASE_URL,
        val chatModel: String = DefaultSettings.CHAT_MODEL,
        val backgroundModel: String = DefaultSettings.BACKGROUND_MODEL,
        val chatThinking: String = DefaultSettings.CHAT_THINKING,
        val letterModel: String = DefaultSettings.LETTER_MODEL,
        val letterThinking: String = DefaultSettings.LETTER_THINKING,
        val userName: String = DefaultSettings.USER_NAME,
        val keySet: Boolean = false,
        val testing: Boolean = false,
        val testLine: String? = null,
        val testOk: Boolean = false,
        val version: String = "",
        val lastCrash: String? = null,
        val rebuilding: Boolean = false,
        val rebuildLine: String? = null,
        val dayBoundaryHour: Int = DefaultSettings.DAY_BOUNDARY_HOUR,
        val weeklyLetter: Boolean = true,
        val sundayAlert: Boolean = com.clarity.life.settings.DefaultSettings.SUNDAY_ALERT_ON,
        /** Your data: where backups go, whether a passphrase is set, and the last backup's one line. */
        val backupFolder: String = "",
        val hasPassphrase: Boolean = false,
        val backupLine: String? = null,
        val backingUp: Boolean = false,
        /** A backup file he picked to restore, and the day it was made (read from its header). */
        val restoreUri: String? = null,
        val restoreMade: String? = null,
        val restoring: Boolean = false,
        val restoreLine: String? = null,
        val appLock: Boolean = DefaultSettings.APP_LOCK_ON,
        val hideInRecents: Boolean = DefaultSettings.HIDE_IN_RECENTS_ON,
        /** False when the phone has no screen lock, so the app cannot lock either. */
        val canLock: Boolean = true,
        val exporting: Boolean = false,
        val exportLine: String? = null,
        val erasing: Boolean = false,
        val eraseLine: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** The three presets of the mockup. "OpenAI-style" is any other compatible server. */
    val providers = listOf(
        "deepseek" to "DeepSeek",
        "compat" to "OpenAI-style",
        "anthropic" to "Anthropic",
    )

    init {
        viewModelScope.launch { reload() }
    }

    private suspend fun reload() {
        val provider = settings.get(SettingsRepository.Keys.PROVIDER, DefaultSettings.PROVIDER)
        _state.value = State(
            provider = provider,
            baseUrl = settings.get(SettingsRepository.Keys.BASE_URL, DefaultSettings.BASE_URL),
            chatModel = settings.get(SettingsRepository.Keys.CHAT_MODEL, DefaultSettings.CHAT_MODEL),
            backgroundModel = settings.get(
                SettingsRepository.Keys.BACKGROUND_MODEL,
                DefaultSettings.BACKGROUND_MODEL,
            ),
            chatThinking = settings.chatThinking(),
            letterModel = settings.get(SettingsRepository.Keys.LETTER_MODEL, DefaultSettings.LETTER_MODEL),
            letterThinking = settings.letterThinking(),
            userName = settings.userName(),
            keySet = settings.hasKey(provider),
            version = com.clarity.life.BuildConfig.VERSION_NAME,
            lastCrash = com.clarity.life.CrashLog.read(context),
            dayBoundaryHour = settings.dayBoundaryHour(),
            weeklyLetter = settings.getBool(SettingsRepository.Keys.WEEKLY_LETTER_ON, DefaultSettings.WEEKLY_LETTER_ON),
            sundayAlert = settings.getBool(SettingsRepository.Keys.SUNDAY_ALERT_ON, DefaultSettings.SUNDAY_ALERT_ON),
            backupFolder = folderName(settings.get(SettingsRepository.Keys.BACKUP_FOLDER, "")),
            hasPassphrase = backups.hasPassphrase(),
            backupLine = backupLine(),
            appLock = settings.getBool(SettingsRepository.Keys.APP_LOCK_ON, DefaultSettings.APP_LOCK_ON),
            hideInRecents = settings.getBool(SettingsRepository.Keys.HIDE_IN_RECENTS_ON, DefaultSettings.HIDE_IN_RECENTS_ON),
            canLock = com.clarity.life.security.AppLock.canLock(context),
        )
    }

    fun setAppLock(on: Boolean) = viewModelScope.launch {
        settings.setBool(SettingsRepository.Keys.APP_LOCK_ON, on)
        com.clarity.life.security.AppLock.rememberOn(context, on)
        _state.value = _state.value.copy(appLock = on)
    }

    /** Takes effect the next time the app opens (the switcher card is set when the screen is made). */
    fun setHideInRecents(on: Boolean) = viewModelScope.launch {
        settings.setBool(SettingsRepository.Keys.HIDE_IN_RECENTS_ON, on)
        _state.value = _state.value.copy(hideInRecents = on)
    }

    private suspend fun backupLine(): String? {
        val at = settings.get(SettingsRepository.Keys.LAST_BACKUP_AT, "").toLongOrNull()
        val line = settings.get(SettingsRepository.Keys.LAST_BACKUP_LINE, "").ifBlank { null }
        val last = at?.let { "Last verified backup: ${stamp(it)}." }
        return listOfNotNull(line, last).distinct().joinToString(" ").ifBlank { null }
    }

    private suspend fun stamp(ms: Long): String {
        val local = com.clarity.life.core.TimeFacts.local(java.time.Instant.ofEpochMilli(ms), settings.timeZone())
        return java.time.format.DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm").format(local)
    }

    /** The last part of the folder's address ("Drive/Clarity backups"), never the whole URI. */
    private fun folderName(uri: String): String =
        if (uri.isBlank()) "" else android.net.Uri.decode(uri.substringAfterLast('/')).substringAfter(':')

    /** Choose folder: kept across restarts. */
    fun chooseFolder(uri: android.net.Uri) = viewModelScope.launch {
        try {
            backups.keepFolder(uri)
            settings.set(SettingsRepository.Keys.BACKUP_FOLDER, uri.toString())
            _state.value = _state.value.copy(backupFolder = folderName(uri.toString()))
        } catch (e: SecurityException) {
            _state.value = _state.value.copy(backupLine = "That folder cannot be kept. Choose another one.")
        }
    }

    /** Set or change the passphrase; it must be typed twice the same. */
    fun setPassphrase(first: String, second: String): String? {
        if (first != second) return "The two passphrases are not the same."
        if (first.length < com.clarity.life.core.BackupFormat.MIN_PASSPHRASE) {
            return "Use at least ${com.clarity.life.core.BackupFormat.MIN_PASSPHRASE} characters."
        }
        backups.setPassphrase(first.toCharArray())
        _state.value = _state.value.copy(hasPassphrase = true)
        return null
    }

    fun backUpNow() = viewModelScope.launch {
        _state.value = _state.value.copy(backingUp = true)
        backups.backUp()
        _state.value = _state.value.copy(backingUp = false, backupLine = backupLine())
    }

    /** He picked a backup file: show the day it was made before asking for the passphrase. */
    fun pickRestore(uri: android.net.Uri) = viewModelScope.launch {
        val made = backups.createdAt(uri)
        _state.value = if (made == null) {
            _state.value.copy(restoreUri = null, restoreLine = "That is not a Clarity backup.")
        } else {
            _state.value.copy(restoreUri = uri.toString(), restoreMade = stamp(made), restoreLine = null)
        }
    }

    /** Export everything, to the file he created with the system's Save dialog. */
    fun export(uri: android.net.Uri) = viewModelScope.launch {
        _state.value = _state.value.copy(exporting = true, exportLine = null)
        val line = exporter.export(uri)
        _state.value = _state.value.copy(exporting = false, exportLine = line)
    }

    /** Erase everything, only after he typed ERASE. The app closes itself when it is done. */
    fun erase(typed: String) {
        if (!eraser.matches(typed) || _state.value.erasing) return
        _state.value = _state.value.copy(erasing = true, eraseLine = null)
        viewModelScope.launch {
            try {
                eraser.eraseEverything()
            } catch (e: Exception) {
                com.clarity.life.CrashLog.record(context, e)
                _state.value = _state.value.copy(
                    erasing = false,
                    eraseLine = "Not everything could be erased. Close Clarity and try again.",
                )
            }
        }
    }

    fun cancelRestore() {
        _state.value = _state.value.copy(restoreUri = null, restoreMade = null)
    }

    fun restore(passphrase: String) = viewModelScope.launch {
        val uri = _state.value.restoreUri ?: return@launch
        _state.value = _state.value.copy(restoring = true, restoreLine = null)
        val outcome = backups.restore(android.net.Uri.parse(uri), passphrase.toCharArray())
        val line = when (outcome) {
            is com.clarity.life.backup.BackupRepository.Outcome.Restored ->
                "Restored the backup from ${stamp(outcome.createdAt)}. Close Clarity and open it again. " +
                    "Your API key is not in a backup: paste it again in Coach."
            is com.clarity.life.backup.BackupRepository.Outcome.Failed -> outcome.line
            else -> null
        }
        val done = outcome is com.clarity.life.backup.BackupRepository.Outcome.Restored
        // A wrong passphrase keeps the dialog open, so he can type it again.
        _state.value = _state.value.copy(
            restoring = false,
            restoreUri = if (done) null else uri,
            restoreMade = if (done) null else _state.value.restoreMade,
            restoreLine = line,
        )
    }

    /** Weekly letter on or off. Off means no letter is written (and no model call is made for it). */
    fun setWeeklyLetter(on: Boolean) = viewModelScope.launch {
        settings.setBool(SettingsRepository.Keys.WEEKLY_LETTER_ON, on)
        _state.value = _state.value.copy(weeklyLetter = on)
    }

    /** Sunday alert: one silent notification when the letter is ready. */
    fun setSundayAlert(on: Boolean) = viewModelScope.launch {
        settings.setBool(SettingsRepository.Keys.SUNDAY_ALERT_ON, on)
        _state.value = _state.value.copy(sundayAlert = on)
    }

    /** My day ends at: a check-in or promise answered before this hour counts for the day before. */
    fun setDayBoundary(hour: Int) {
        require(hour in DAY_BOUNDARY_CHOICES)
        viewModelScope.launch {
            settings.set(SettingsRepository.Keys.DAY_BOUNDARY_HOUR, hour.toString())
            _state.value = _state.value.copy(dayBoundaryHour = hour)
        }
    }

    companion object {
        val DAY_BOUNDARY_CHOICES = listOf(0, 1, 2, 3, 4, 5, 6)
    }

    fun pickProvider(id: String) {
        viewModelScope.launch {
            settings.set(SettingsRepository.Keys.PROVIDER, id)
            // Each preset has its own address; he can still edit it.
            val url = when (id) {
                "deepseek" -> DefaultSettings.BASE_URL
                "anthropic" -> DefaultSettings.ANTHROPIC_BASE_URL
                else -> "https://"
            }
            settings.set(SettingsRepository.Keys.BASE_URL, url)
            _state.value = _state.value.copy(provider = id, baseUrl = url, testLine = null, keySet = settings.hasKey(id))
        }
    }

    fun setBaseUrl(value: String) = save(SettingsRepository.Keys.BASE_URL, value) { copy(baseUrl = value) }

    fun setChatModel(value: String) = save(SettingsRepository.Keys.CHAT_MODEL, value) { copy(chatModel = value) }

    fun setBackgroundModel(value: String) =
        save(SettingsRepository.Keys.BACKGROUND_MODEL, value) { copy(backgroundModel = value) }

    fun setChatThinking(level: String) =
        save(SettingsRepository.Keys.CHAT_THINKING, level) { copy(chatThinking = level, testLine = null) }

    fun setLetterModel(value: String) =
        save(SettingsRepository.Keys.LETTER_MODEL, value) { copy(letterModel = value) }

    fun setLetterThinking(level: String) =
        save(SettingsRepository.Keys.LETTER_THINKING, level) { copy(letterThinking = level) }

    fun setUserName(value: String) = save(SettingsRepository.Keys.USER_NAME, value) { copy(userName = value) }

    /** The key goes straight into the Keystore and is never shown again in full, never logged. */
    fun pasteKey(value: String) {
        val provider = _state.value.provider
        settings.setKey(provider, value)
        _state.value = _state.value.copy(keySet = settings.hasKey(provider), testLine = null)
    }

    fun clearCrash() {
        com.clarity.life.CrashLog.clear(context)
        _state.value = _state.value.copy(lastCrash = null)
    }

    fun clearKey() {
        val provider = _state.value.provider
        settings.setKey(provider, null)
        _state.value = _state.value.copy(keySet = false, testLine = null)
    }

    /** Advanced, Rebuild search: the keyword index is derived data, so it can always be made again. */
    fun rebuildSearch() {
        viewModelScope.launch {
            _state.value = _state.value.copy(rebuilding = true, rebuildLine = null)
            val line = try {
                val n = search.rebuild()
                "Search rebuilt from your messages and summaries ($n items)."
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                com.clarity.life.CrashLog.record(context, e)
                "The rebuild did not finish (${e.javaClass.simpleName}). Nothing was lost; try again."
            }
            _state.value = _state.value.copy(rebuilding = false, rebuildLine = line)
        }
    }

    /** Advanced, Re-index: the meaning vectors are derived data too; they are made again in the background. */
    fun reindex() {
        viewModelScope.launch {
            val line = try {
                indexer.reindex()
                "Meaning search is being rebuilt in the background. Keyword search works meanwhile."
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                com.clarity.life.CrashLog.record(context, e)
                "Re-index did not start (${e.javaClass.simpleName}). Nothing was lost; try again."
            }
            _state.value = _state.value.copy(rebuildLine = line)
        }
    }

    /** Test connection: one small real streamed request, so the line he sees is the truth. */
    fun testConnection() {
        viewModelScope.launch {
            _state.value = _state.value.copy(testing = true, testLine = null)
            val endpoint = settings.chatEndpoint()
            val result: TestResult = when {
                endpoint == null -> TestResult(false, "Paste your API key first.")
                endpoint.provider == "anthropic" -> anthropic.test(endpoint)
                else -> openAi.test(endpoint)
            }
            _state.value = _state.value.copy(testing = false, testLine = result.detail, testOk = result.ok)
        }
    }

    private fun save(key: String, value: String, update: State.() -> State) {
        _state.value = _state.value.update()
        viewModelScope.launch { settings.set(key, value) }
    }
}
