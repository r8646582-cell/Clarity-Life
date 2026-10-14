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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
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
    var key by remember { mutableStateOf("") }
    // One group open at a time; the health check opens the one a problem lives in.
    var openGroup by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    // No key yet (a new person, or "Paste my key" on Talk): Coach opens by itself, with the key box in view.
    var autoOpened by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
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
        Group("Coach", "Provider, key, models, your name", Icons.Outlined.AutoAwesome, openGroup, { openGroup = it }) {
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
            Label("Chat thinking")
            Levels(state.chatThinking, model::setChatThinking)
            Hint(
                if (state.provider == "anthropic") {
                    "Claude always thinks a little, so Off works like Low. High and Max are slower and more careful."
                } else {
                    "Low is quick; High and Max are slower and more careful."
                },
            )
            Label("Background model")
            Field(state.backgroundModel, model::setBackgroundModel)
            Hint("For reflection, reviews and the monthly story.")
            Label("Weekly letter model")
            Field(state.letterModel, model::setLetterModel)
            Label("Weekly letter thinking")
            Levels(state.letterThinking, model::setLetterThinking)
            Hint("Typed freely: check names in the provider's docs.")

            Spacer(Modifier.height(14.dp))
            com.clarity.life.ui.theme.PrimaryButton(if (state.testing) "Testing" else "Test connection") {
                if (!state.testing) model.testConnection()
            }

            state.testLine?.let { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.testOk) clarity.text else clarity.danger,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            Hint("Changed a model? Run the test bench in Advanced before you rely on it.")

            Label("Your name")
            Field(state.userName, model::setUserName)
            Hint("Optional. Only the coach's prompt uses it.")

        }
        Group("Letters and your day", "Sunday letter, when your day ends, reminders", Icons.Outlined.MailOutline, openGroup, { openGroup = it }) {
            SwitchRow(
                "Weekly letter",
                "Every Sunday evening",
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
    var passphraseDialog by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }
    val folder = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> uri?.let(model::chooseFolder) }
    val file = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(model::pickRestore) }
    val exportTo = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> uri?.let(model::export) }
    var warnExport by remember { mutableStateOf(false) }

    Hint("Encrypted, weekly, to a folder you choose. Write the passphrase on paper: without it a backup cannot be restored.")
    // Two-line rows: the name and its state on the left (a long folder path wraps, never squeezes), the button
    // on the right.
    BackupRow("Backup folder", state.backupFolder.ifBlank { "Not chosen yet" }) {
        SmallButton(if (state.backupFolder.isBlank()) "Choose" else "Change") { folder.launch(null) }
    }
    BackupRow("Backup passphrase", if (state.hasPassphrase) "Set. Keep a copy on paper too." else "Not set yet") {
        SmallButton(if (state.hasPassphrase) "Change" else "Set up") { passphraseDialog = true }
    }
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
    var erasing by remember { mutableStateOf(false) }
    com.clarity.life.ui.theme.DangerButton(if (state.erasing) "Erasing" else "Erase everything", Modifier.fillMaxWidth().padding(top = 8.dp)) {
        if (!state.erasing) erasing = true
    }
    Hint("Backups in your folder are not touched.")
    state.eraseLine?.let { Hint(it) }

    if (erasing) {
        var typed by remember { mutableStateOf("") }
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

    if (passphraseDialog) {
        var first by remember { mutableStateOf("") }
        var second by remember { mutableStateOf("") }
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
        var pass by remember { mutableStateOf("") }
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

@Composable
private fun SecretField(value: String, label: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, color = clarity.muted) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        colors = fieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Off, Low, High, Max as a row of chips, the same as "My day ends at". */
@Composable
private fun Levels(current: String, onPick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        com.clarity.life.core.Thinking.LEVELS.forEach { (id, label) ->
            Chip(label, current == id) { onPick(id) }
        }
    }
}

/** One choice in a row of chips: yellow when picked. Never breaks a word across two lines. */
@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (on) clarity.ground else clarity.text,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .background(if (on) clarity.yellow else clarity.ground, RoundedCornerShape(20.dp))
            .border(1.dp, if (on) clarity.yellow else clarity.controlOutline, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    )
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = clarity.muted,
        modifier = Modifier.padding(top = 18.dp, bottom = 6.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = clarity.muted,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        colors = fieldColors(),
        shape = RoundedCornerShape(12.dp),
        singleLine = true,
    )
}

@Composable
private fun BackupRow(title: String, value: String, button: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = clarity.text)
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                color = clarity.muted,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        button()
    }
}

@Composable
private fun fieldColors() = TextFieldDefaults.colors(
    focusedTextColor = clarity.text,
    unfocusedTextColor = clarity.text,
    focusedContainerColor = clarity.surface,
    unfocusedContainerColor = clarity.surface,
    focusedIndicatorColor = clarity.controlOutline,
    unfocusedIndicatorColor = clarity.hairline,
    cursorColor = clarity.yellow,
)

@Composable
private fun SwitchRow(title: String, hint: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable { onChange(!on) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = clarity.text)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = clarity.muted)
        }
        androidx.compose.material3.Switch(
            checked = on,
            onCheckedChange = onChange,
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedThumbColor = clarity.onYellow,
                checkedTrackColor = clarity.yellow,
                checkedBorderColor = clarity.yellow,
                uncheckedThumbColor = clarity.muted,
                uncheckedTrackColor = clarity.surface,
                uncheckedBorderColor = clarity.controlOutline,
            ),
        )
    }
}

/**
 * A settings group (the 0.1.6 redesign): a card with an icon, its name and what is inside; tapping it opens its
 * controls inside the card. One group is open at a time, so the page stays short.
 */
@Composable
private fun Group(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    open: String?,
    onOpen: (String?) -> Unit,
    content: @Composable () -> Unit,
) {
    val isOpen = open == title
    Column(
        Modifier
            .fillMaxWidth()
            .background(clarity.surface, RoundedCornerShape(22.dp))
            .border(1.dp, if (isOpen) clarity.controlOutline else clarity.hairline, RoundedCornerShape(22.dp)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .clickable { onOpen(if (isOpen) null else title) }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            androidx.compose.foundation.layout.Box(
                Modifier.size(40.dp).background(clarity.raised, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, contentDescription = null, tint = clarity.yellow) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = clarity.text)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = clarity.muted)
            }
            Icon(
                if (isOpen) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = clarity.muted,
            )
        }
        if (isOpen) {
            androidx.compose.material3.HorizontalDivider(color = clarity.hairline)
            Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 18.dp)) { content() }
        }
    }
}

/** A small real button for an action inside a row: Save, Choose, Open, Change. */
@Composable
private fun SmallButton(
    label: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = if (primary) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Medium,
        color = when {
            !enabled -> clarity.muted
            primary -> clarity.onYellow
            danger -> clarity.danger
            else -> clarity.text
        },
        modifier = modifier
            .heightIn(min = 44.dp)
            .then(
                when {
                    primary && enabled -> Modifier.background(clarity.yellow, shape)
                    danger -> Modifier.border(1.dp, clarity.coralEdge, shape)
                    else -> Modifier.background(clarity.raised, shape).border(1.dp, clarity.controlOutline, shape)
                },
            )
            .clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/**
 * Health check at the top of Settings: is the coach set up, can reminders ring on time on this phone, is there
 * a backup. Read from the phone and the settings, never guessed; each problem has the tap that fixes it.
 */
@Composable
private fun HealthCheck(
    state: SettingsViewModel.State,
    notificationsAllowed: Boolean,
    onCoach: () -> Unit,
    onReminders: () -> Unit,
    onData: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val power = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
    val alarms = context.getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager
    val reminderChecks = listOf(
        notificationsAllowed,
        power.isIgnoringBatteryOptimizations(context.packageName),
        if (android.os.Build.VERSION.SDK_INT >= 31) alarms.canScheduleExactAlarms() else true,
    )
    val ok = reminderChecks.count { it }
    Column(
        Modifier
            .fillMaxWidth()
            .background(clarity.surface, RoundedCornerShape(22.dp))
            .border(1.dp, clarity.hairline, RoundedCornerShape(22.dp))
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HealthRow(
            good = state.keySet && (state.testLine == null || state.testOk),
            text = when {
                !state.keySet -> "No API key yet"
                state.testLine != null && !state.testOk -> "The last connection test failed"
                else -> "Coach key set"
            },
            detail = if (state.keySet) state.chatModel else null,
            action = if (state.keySet && (state.testLine == null || state.testOk)) null else "Fix",
            onAction = onCoach,
        )
        HealthRow(
            good = ok == reminderChecks.size,
            text = if (ok == reminderChecks.size) "Reminders can ring on time" else "Reminders may be late",
            detail = "$ok of ${reminderChecks.size}",
            action = if (ok == reminderChecks.size) null else "Fix",
            onAction = onReminders,
        )
        val backup = state.backupFolder.isNotBlank() && state.hasPassphrase
        HealthRow(
            good = backup,
            text = if (backup) "Backups are set up" else "No backup yet",
            detail = null,
            action = if (backup) null else "Set up",
            onAction = onData,
        )
    }
}

@Composable
private fun HealthRow(good: Boolean, text: String, detail: String?, action: String?, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        androidx.compose.foundation.layout.Box(
            Modifier
                .size(8.dp)
                .background(if (good) clarity.green else clarity.yellow, androidx.compose.foundation.shape.CircleShape),
        )
        Text(text, style = MaterialTheme.typography.bodyLarge, color = clarity.text, modifier = Modifier.weight(1f))
        detail?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = clarity.muted,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 140.dp),
            )
        }
        action?.let { SmallButton(it, primary = true, onClick = onAction) }
    }
}
