@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.clarity.life.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clarity.life.ui.theme.clarity

/**
 * Settings, as the 0.1.6 redesign draws it (docs/mockups/redesign/Settings.dc.html): a health check, then
 * grouped cards with an icon each (Coach, Letters and your day, Privacy, Your data, Advanced). Tapping a card
 * opens its controls inside it; every action is a real button, and the copy is short.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenReliable: () -> Unit = {},
    onOpenPrompts: () -> Unit = {},
    onOpenGuide: () -> Unit = {},
    model: SettingsViewModel = hiltViewModel(),
) {
    val state by model.state.collectAsStateWithLifecycle()
    var key by rememberSaveable { mutableStateOf("") }
    var bgKey by rememberSaveable { mutableStateOf("") }
    // One group open at a time; the health check opens the one a problem lives in.
    var openGroup by rememberSaveable { mutableStateOf<String?>(null) }
    // No key yet (a new person, or "Paste my key" on Talk): Coach opens by itself, with the key box in view.
    var autoOpened by rememberSaveable { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(state.loaded) {
        if (state.loaded && !autoOpened) {
            autoOpened = true
            if (!state.keySet && openGroup == null) openGroup = "Coach"
        }
    }
    // The Sunday alert needs permission to show a notification (Android 13 and later), asked when he turns it on.
    val context = androidx.compose.ui.platform.LocalContext.current
    fun notificationsOn() = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
    var notificationsAllowed by remember { mutableStateOf(notificationsOn()) }
    LifecycleResumeEffect(Unit) {
        notificationsAllowed = notificationsOn()
        model.refresh()
        onPauseOrDispose { }
    }
    val askNotifications = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { notificationsAllowed = notificationsOn() }

    Column(
        Modifier
            .fillMaxSize()
            .background(clarity.ground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconButton(onClick = onBack, modifier = Modifier.padding(top = 4.dp)) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = clarity.text)
        }
        Text(
            "Settings",
            style = MaterialTheme.typography.displayLarge,
            color = clarity.text,
            modifier = Modifier.padding(start = 8.dp, bottom = 6.dp),
        )
        HealthCheck(
            state = state,
            notificationsAllowed = notificationsAllowed,
            onCoach = { openGroup = "Coach" },
            onReminders = onOpenReliable,
            onData = { openGroup = "Your data" },
        )
        Group("Coach", "Main provider, API key, model and thinking", Icons.Outlined.AutoAwesome, openGroup, { openGroup = it }) {
            Label("Main provider")
            // Wraps to a second line on a narrow phone instead of breaking a name in half ("Anthrop / ic").
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                model.providers.forEach { (id, label) ->
                    Chip(label, state.provider == id) { model.pickProvider(id) }
                }
            }

            Label("Base URL")
            Field(state.baseUrl, model::setBaseUrl)

            Label("API key")
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(if (state.keySet) "Key set" else "Paste your key", color = clarity.muted) },
                    visualTransformation = PasswordVisualTransformation(),
                    colors = fieldColors(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                )
                Spacer(Modifier.size(8.dp))
                SmallButton("Save", primary = true, enabled = key.isNotBlank()) {
                    model.pasteKey(key)
                    key = ""
                }
            }
            if (state.keySet) SmallButton("Remove key", danger = true, modifier = Modifier.padding(top = 8.dp)) { model.clearKey() }
            Hint("Kept in the phone's secure keystore, never shown or logged.")
            com.clarity.life.settings.DefaultSettings.keyPage(state.provider)?.let { page ->
                if (!state.keySet) {
                    SmallButton("Get a key", modifier = Modifier.padding(top = 8.dp)) {
                        com.clarity.life.ui.talk.openPage(context, page)
                    }
                    Hint("Opens the provider's site: sign up, add a little credit, make an API key, copy it, paste it above.")
                }
            }

            Label("Chat model")
            Field(state.chatModel, model::setChatModel)
            if (state.provider == "gemini") {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 6.dp),
                ) {
                    com.clarity.life.settings.DefaultSettings.GEMINI_MODELS.forEach { m ->
                        Chip(m, state.chatModel == m) { model.setChatModel(m) }
                    }
                }
            } else if (state.provider == "deepseek") {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 6.dp),
                ) {
                    com.clarity.life.settings.DefaultSettings.DEEPSEEK_MODELS.forEach { m ->
                        Chip(m, state.chatModel == m) { model.setChatModel(m) }
                    }
                }
            }

            Label("Chat thinking")
            Hint("Thinking level per chat type: Low is quick; High and Max are slower and more careful.")
            Text("Free talk", style = MaterialTheme.typography.labelMedium, color = clarity.muted, modifier = Modifier.padding(top = 4.dp))
            Levels(state.chatThinkingFree, model::setChatThinkingFree)
            Text("Untangle", style = MaterialTheme.typography.labelMedium, color = clarity.muted, modifier = Modifier.padding(top = 4.dp))
            Levels(state.chatThinkingUntangle, model::setChatThinkingUntangle)
            Text("Decide", style = MaterialTheme.typography.labelMedium, color = clarity.muted, modifier = Modifier.padding(top = 4.dp))
            Levels(state.chatThinkingDecide, model::setChatThinkingDecide)
            Text("Plan", style = MaterialTheme.typography.labelMedium, color = clarity.muted, modifier = Modifier.padding(top = 4.dp))
            Levels(state.chatThinkingPlan, model::setChatThinkingPlan)

            Spacer(Modifier.height(10.dp))
            com.clarity.life.ui.theme.PrimaryButton(if (state.testing) "Testing chat..." else "Test main connection") {
                if (!state.testing) model.testConnection()
            }
            state.testLine?.let { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.testOk) clarity.text else clarity.danger,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }

            Label("Weekly letter model")
            Field(state.letterModel, model::setLetterModel)
            Label("Weekly letter thinking")
            Levels(state.letterThinking, model::setLetterThinking)
            Hint("Typed freely: check names in the provider's docs.")

            Hint("Changed a model? Run the test bench in Advanced before you rely on it.")

            Label("Your name")
            Field(state.userName, model::setUserName)
            Hint("Optional. Only the coach's prompt uses it.")
        }
        Group("Background AI", "Separate model for reflection, reviews, and memories", Icons.Outlined.Psychology, openGroup, { openGroup = it }) {
            Label("Background provider")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                model.backgroundProviders.forEach { (id, label) ->
                    Chip(label, state.backgroundProvider == id) { model.setBackgroundProvider(id) }
                }
            }
            if (state.backgroundProvider.isNotBlank()) {
                Label("Background base URL")
                Field(state.backgroundBaseUrl, model::setBackgroundBaseUrl)

                Label("Background API key")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = bgKey,
                        onValueChange = { bgKey = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(if (state.backgroundKeySet) "Key set" else "Paste background key", color = clarity.muted) },
                        visualTransformation = PasswordVisualTransformation(),
                        colors = fieldColors(),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                    )
                    Spacer(Modifier.size(8.dp))
                    SmallButton("Save", primary = true, enabled = bgKey.isNotBlank()) {
                        model.pasteBackgroundKey(bgKey)
                        bgKey = ""
                    }
                }
                if (state.backgroundKeySet) {
                    SmallButton("Remove background key", danger = true, modifier = Modifier.padding(top = 8.dp)) {
                        model.clearBackgroundKey()
                    }
                }
                Hint("Kept in the phone's secure keystore, never shown or logged.")
                com.clarity.life.settings.DefaultSettings.keyPage(state.backgroundProvider)?.let { page ->
                    if (!state.backgroundKeySet) {
                        SmallButton("Get a key", modifier = Modifier.padding(top = 8.dp)) {
                            com.clarity.life.ui.talk.openPage(context, page)
                        }
                        Hint("Opens the provider's site to create an API key.")
                    }
                }
            } else {
                Hint("Using your chat provider. Tap Google Gemini or OpenAI-style to use a separate free/cheap model.")
            }

            Label("Background model")
            Field(state.backgroundModel, model::setBackgroundModel)
            val effectiveBgProvider = if (state.backgroundProvider.isNotBlank()) state.backgroundProvider else state.provider
            if (effectiveBgProvider == "gemini") {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 6.dp),
                ) {
                    com.clarity.life.settings.DefaultSettings.GEMINI_MODELS.forEach { m ->
                        Chip(m, state.backgroundModel == m) { model.setBackgroundModel(m) }
                    }
                }
            } else if (effectiveBgProvider == "deepseek") {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 6.dp),
                ) {
                    com.clarity.life.settings.DefaultSettings.DEEPSEEK_MODELS.forEach { m ->
                        Chip(m, state.backgroundModel == m) { model.setBackgroundModel(m) }
                    }
                }
            }
            Hint("For reflection, reviews and memories. Falls back to your chat provider if this fails.")

            Spacer(Modifier.height(10.dp))
            com.clarity.life.ui.theme.PrimaryButton(if (state.testingBackground) "Testing background..." else "Test background connection") {
                if (!state.testingBackground) model.testBackgroundConnection()
            }
            state.backgroundTestLine?.let { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.backgroundTestOk) clarity.text else clarity.danger,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }
        Group("Letters and your day", "Sunday letter, when your day ends, reminders", Icons.Outlined.MailOutline, openGroup, { openGroup = it }) {
            SwitchRow(
                "Letters",
                "Every Sunday, plus one for each month and year",
                state.weeklyLetter,
            ) { model.setWeeklyLetter(it) }
            SwitchRow(
                "Sunday alert",
                "One silent notification",
                state.sundayAlert,
            ) {
                model.setSundayAlert(it)
                if (it && android.os.Build.VERSION.SDK_INT >= 33) askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            // On by default now, so he may never have been asked: say so plainly and offer the question here.
            if (state.sundayAlert && !notificationsAllowed) {
                Text(
                    "Notifications are off for Clarity, so the alert cannot show. Tap to allow them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = clarity.coral,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (android.os.Build.VERSION.SDK_INT >= 33) {
                                askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                context.startActivity(
                                    android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName),
                                )
                            }
                        }
                        .padding(vertical = 8.dp),
                )
            }
            Label("My day ends at")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SettingsViewModel.DAY_BOUNDARY_CHOICES.forEach { hour ->
                    Chip(if (hour == 0) "Midnight" else "$hour am", state.dayBoundaryHour == hour) { model.setDayBoundary(hour) }
                }
            }
            Hint(
                if (state.dayBoundaryHour == 0) {
                    "Like a calendar: a new day starts at midnight."
                } else {
                    "For late nights: until ${state.dayBoundaryHour} am, a check-in still counts for the day before."
                },
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Keep reminders reliable", style = MaterialTheme.typography.bodyLarge, color = clarity.text)
                    Text("Battery, autostart, alarms", style = MaterialTheme.typography.bodySmall, color = clarity.muted)
                }
                SmallButton("Open", onClick = onOpenReliable)
            }

        }
        Group("Privacy", "App lock, hide in recents", Icons.Outlined.Lock, openGroup, { openGroup = it }) {
            SwitchRow(
                "App lock",
                if (state.canLock) "Fingerprint or screen lock to open" else "Set a screen lock on the phone first",
                state.appLock,
            ) { model.setAppLock(it) }
            SwitchRow(
                "Hide in recents",
                "A blank card in the app switcher",
                state.hideInRecents,
            ) { model.setHideInRecents(it) }

        }
        Group("Your data", "Backup, restore, export, erase", Icons.Outlined.Storage, openGroup, { openGroup = it }) {
            YourData(state, model)
        }
        Group("Advanced", "Search, health, test bench, prompts", Icons.Outlined.Tune, openGroup, { openGroup = it }) {
            Hint("Search runs on this phone. Rebuilding loses nothing.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                com.clarity.life.ui.theme.SecondaryButton(if (state.rebuilding) "Rebuilding" else "Rebuild search", Modifier.weight(1f)) {
                    if (!state.rebuilding) model.rebuildSearch()
                }
                com.clarity.life.ui.theme.SecondaryButton("Re-index meaning", Modifier.weight(1f)) { model.reindex() }
            }
            state.rebuildLine?.let { Hint(it) }

            HealthSection()
            BenchSection()
            com.clarity.life.ui.theme.SecondaryButton("Edit the coach's prompts", Modifier.fillMaxWidth().padding(top = 8.dp), onClick = onOpenPrompts)

            Spacer(Modifier.height(20.dp))

            state.lastCrash?.let { crash ->
                Label("Last time the app stopped")
                Hint("Take a photo or screenshot of this and send it. It holds no messages and no keys.")
                Text(
                    crash,
                    style = MaterialTheme.typography.bodySmall,
                    color = clarity.text,
                    modifier = Modifier.padding(top = 6.dp),
                )
                SmallButton("Clear", modifier = Modifier.padding(top = 8.dp)) { model.clearCrash() }
            }
        }
        com.clarity.life.ui.theme.SecondaryButton("How Clarity works", Modifier.fillMaxWidth(), onClick = onOpenGuide)
        Hint("Clarity ${state.version}")
        Spacer(Modifier.height(32.dp))
    }
}

/**
 * Your data: the encrypted backups (spec section 9). A folder he chooses (Drive works), a passphrase he keeps
 * on paper, Back up now, and Restore, which asks for the passphrase and then asks again before it replaces
 * everything on this phone.
 */
@Composable
private fun YourData(state: SettingsViewModel.State, model: SettingsViewModel) {
    var passphraseDialog by rememberSaveable { mutableStateOf(false) }
    var testDialog by rememberSaveable { mutableStateOf(false) }
    var confirmRestore by rememberSaveable { mutableStateOf(false) }
    val folder = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> uri?.let(model::chooseFolder) }
    val file = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(model::pickRestore) }
    val exportTo = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> uri?.let(model::export) }
    var warnExport by rememberSaveable { mutableStateOf(false) }

    Hint("Encrypted, automatic, to a folder you choose. Each backup is read back and checked before older ones are removed.")
    Hint(
        "Write the passphrase on paper. If this phone is lost, reset or replaced, the backups are the only copy of " +
            "your data, and only the passphrase opens them. Nobody can recover it, not even the app.",
    )
    // Two-line rows: the name and its state on the left (a long folder path wraps, never squeezes), the button
    // on the right.
    BackupRow("Backup folder", state.backupFolder.ifBlank { "Not chosen yet" }) {
        SmallButton(if (state.backupFolder.isBlank()) "Choose" else "Change") { folder.launch(null) }
    }
    BackupRow("Backup passphrase", if (state.hasPassphrase) "Set. Keep a copy on paper too." else "Not set yet") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (state.hasPassphrase) SmallButton("Test") { testDialog = true }
            SmallButton(if (state.hasPassphrase) "Change" else "Set up") { passphraseDialog = true }
        }
    }
    Label("Back up")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        com.clarity.life.core.Maintenance.BACKUP_EVERY_DAYS.forEach { days ->
            Chip(if (days == 1) "Every night" else "Every week", state.backupEveryDays == days) { model.setBackupEvery(days) }
        }
    }
    Label("Keep the newest")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        com.clarity.life.core.Maintenance.BACKUP_KEEP.forEach { n ->
            Chip("$n", state.backupKeep == n) { model.setBackupKeep(n) }
        }
    }
    Hint("Made at night while the battery is not low, if the phone is on. Back up now makes one at once.")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 14.dp)) {
        com.clarity.life.ui.theme.PrimaryButton(if (state.backingUp) "Backing up" else "Back up now", Modifier.weight(1f)) {
            if (!state.backingUp) model.backUpNow()
        }
        com.clarity.life.ui.theme.SecondaryButton("Restore", Modifier.weight(1f)) {
            if (!state.restoring) file.launch(arrayOf("*/*"))
        }
    }
    state.backupLine?.let { Hint(it) }
    if (state.restoreUri == null) state.restoreLine?.let { Hint(it) }
    com.clarity.life.ui.theme.SecondaryButton(if (state.exporting) "Exporting" else "Export everything", Modifier.fillMaxWidth().padding(top = 8.dp)) {
        if (!state.exporting) warnExport = true
    }
    state.exportLine?.let { Hint(it) }
    var erasing by rememberSaveable { mutableStateOf(false) }
    com.clarity.life.ui.theme.DangerButton(if (state.erasing) "Erasing" else "Erase everything", Modifier.fillMaxWidth().padding(top = 8.dp)) {
        if (!state.erasing) erasing = true
    }
    Hint("Backups in your folder are not touched.")
    state.eraseLine?.let { Hint(it) }

    if (erasing) {
        var typed by rememberSaveable { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { if (!state.erasing) erasing = false },
            containerColor = clarity.surface,
            title = { Text("Erase everything?", color = clarity.text) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Every conversation, goal, check-in, promise, note, letter and setting on this phone is " +
                            "deleted, with your API key and backup passphrase. It cannot be undone. Backups in your " +
                            "folder stay, and can be restored with their passphrase. Clarity closes when it is done.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = clarity.text,
                    )
                    Text("Type ERASE to confirm.", style = MaterialTheme.typography.bodySmall, color = clarity.muted)
                    Field(typed) { typed = it }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { model.erase(typed) },
                    enabled = typed.trim() == com.clarity.life.data.Eraser.WORD && !state.erasing,
                ) { Text("Erase", color = clarity.danger) }
            },
            dismissButton = {
                TextButton(onClick = { erasing = false }, enabled = !state.erasing) { Text("Cancel", color = clarity.text) }
            },
        )
    }

    if (warnExport) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { warnExport = false },
            containerColor = clarity.surface,
            title = { Text("This copy is not encrypted", color = clarity.text) },
            text = {
                Text(
                    "Anyone who opens the file can read everything in it. Save it somewhere private, " +
                        "and use Back up now for a copy that is safe to keep in Drive.",
                    color = clarity.muted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    warnExport = false
                    exportTo.launch("clarity-export.zip")
                }) { Text("Export", color = clarity.yellow) }
            },
            dismissButton = { TextButton(onClick = { warnExport = false }) { Text("Cancel", color = clarity.text) } },
        )
    }

    if (testDialog) {
        var typed by rememberSaveable { mutableStateOf("") }
        var answer by remember { mutableStateOf<String?>(null) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { testDialog = false },
            containerColor = clarity.surface,
            title = { Text("Test your passphrase", color = clarity.text) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Type it from your paper, not from memory. The app only says whether it matches.",
                        style = MaterialTheme.typography.bodySmall,
                        color = clarity.muted,
                    )
                    SecretField(typed, "Passphrase") {
                        typed = it
                        answer = null
                    }
                    answer?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = clarity.text) }
                }
            },
            confirmButton = {
                TextButton(onClick = { answer = model.testPassphrase(typed) }) { Text("Check", color = clarity.yellow) }
            },
            dismissButton = { TextButton(onClick = { testDialog = false }) { Text("Close", color = clarity.text) } },
        )
    }

    if (passphraseDialog) {
        var first by rememberSaveable { mutableStateOf("") }
        var second by rememberSaveable { mutableStateOf("") }
        var problem by remember { mutableStateOf<String?>(null) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { passphraseDialog = false },
            containerColor = clarity.surface,
            title = { Text("Backup passphrase", color = clarity.text) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Write it on paper now. Without it no backup can be restored, and nobody can recover it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = clarity.muted,
                    )
                    SecretField(first, "Passphrase") { first = it }
                    SecretField(second, "The same again") { second = it }
                    problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = clarity.danger) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    problem = model.setPassphrase(first, second)
                    if (problem == null) passphraseDialog = false
                }) { Text("Save", color = clarity.yellow) }
            },
            dismissButton = { TextButton(onClick = { passphraseDialog = false }) { Text("Cancel", color = clarity.text) } },
        )
    }

    if (state.restoreUri != null) {
        var pass by rememberSaveable { mutableStateOf("") }
        if (!confirmRestore) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { if (!state.restoring) model.cancelRestore() },
                containerColor = clarity.surface,
                title = { Text("Restore this backup?", color = clarity.text) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Made ${state.restoreMade}. Restoring replaces everything on this phone with it.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = clarity.text,
                        )
                        SecretField(pass, "Its passphrase") { pass = it }
                        state.restoreLine?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = clarity.danger) }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { confirmRestore = true }, enabled = pass.isNotEmpty() && !state.restoring) {
                        Text(if (state.restoring) "Restoring" else "Restore", color = clarity.danger)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { model.cancelRestore() }, enabled = !state.restoring) { Text("Cancel", color = clarity.text) }
                },
            )
        }
        if (confirmRestore) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { confirmRestore = false },
                containerColor = clarity.surface,
                title = { Text("Replace everything?", color = clarity.text) },
                text = {
                    Text(
                        "Everything now on this phone is replaced by the backup. This cannot be undone.",
                        color = clarity.muted,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmRestore = false
                        model.restore(pass)
                    }) { Text("Replace", color = clarity.danger) }
                },
                dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text("Keep what I have", color = clarity.text) } },
            )
        }
    }
}

