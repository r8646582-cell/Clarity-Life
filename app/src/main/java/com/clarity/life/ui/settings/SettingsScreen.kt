package com.clarity.life.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
 * Settings. The Coach section is the one stage A needs: provider, base URL, key, the two model names and
 * Test connection (docs/mockups/SettingsCoach.dc.html). The other sections arrive with the stages that
 * create what they configure, and say so rather than showing a dead switch.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenReliable: () -> Unit = {},
    onOpenPrompts: () -> Unit = {},
    model: SettingsViewModel = hiltViewModel(),
) {
    val state by model.state.collectAsStateWithLifecycle()
    var key by remember { mutableStateOf("") }
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
            .padding(horizontal = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = clarity.muted)
            }
            Text("Coach.", style = MaterialTheme.typography.displaySmall, color = clarity.text)
        }

        Label("Main provider")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            model.providers.forEach { (id, label) ->
                val on = state.provider == id
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (on) clarity.ground else clarity.text,
                    modifier = Modifier
                        .background(if (on) clarity.yellow else clarity.ground, RoundedCornerShape(20.dp))
                        .border(1.dp, if (on) clarity.yellow else clarity.controlOutline, RoundedCornerShape(20.dp))
                        .clickable { model.pickProvider(id) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
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
            TextButton(
                onClick = {
                    model.pasteKey(key)
                    key = ""
                },
                enabled = key.isNotBlank(),
            ) { Text("Save", color = clarity.yellow) }
            if (state.keySet) {
                TextButton(onClick = { model.clearKey() }) { Text("Remove", color = clarity.danger) }
            }
        }
        Hint("Kept in the phone's secure keystore. Never shown again in full, never logged.")

        Label("Chat model")
        Field(state.chatModel, model::setChatModel)
        Label("Chat thinking")
        Levels(state.chatThinking, model::setChatThinking)
        Hint("How long the coach thinks before it answers. Low is quick; High and Max are slower and more careful.")
        Label("Background model")
        Field(state.backgroundModel, model::setBackgroundModel)
        Hint("Reflection and reviews, written while you are away.")
        Label("Weekly letter model")
        Field(state.letterModel, model::setLetterModel)
        Label("Weekly letter thinking")
        Levels(state.letterThinking, model::setLetterThinking)
        Hint("Typed freely, so a retired model never needs a new app. Check names in the provider's docs.")

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { model.testConnection() },
            enabled = !state.testing,
            colors = ButtonDefaults.buttonColors(
                containerColor = clarity.yellow,
                contentColor = clarity.ground,
            ),
            shape = RoundedCornerShape(14.dp),
        ) { Text(if (state.testing) "Testing" else "Test connection") }

        state.testLine?.let { line ->
            Text(
                line,
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.testOk) clarity.text else clarity.danger,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        Hint("Changed the model or the thinking level? Run the test bench (below) before you rely on it.")

        Label("Your name")
        Field(state.userName, model::setUserName)
        Hint("Optional. Used in the coach's prompt, nowhere else. Empty: the coach says \"this person\" until you tell it your name.")

        Spacer(Modifier.height(20.dp))
        Text("Reminders", style = MaterialTheme.typography.titleMedium, color = clarity.text)
        SwitchRow(
            "Weekly letter",
            "Written on Sunday evening from your week. It appears in Talk and in Plan, Reviews.",
            state.weeklyLetter,
        ) { model.setWeeklyLetter(it) }
        SwitchRow(
            "Sunday alert",
            "One silent notification when the letter is ready. No sound, nothing on the lock screen.",
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
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SettingsViewModel.DAY_BOUNDARY_CHOICES.forEach { hour ->
                val on = state.dayBoundaryHour == hour
                Text(
                    if (hour == 0) "12" else "$hour",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (on) clarity.ground else clarity.text,
                    modifier = Modifier
                        .background(if (on) clarity.yellow else clarity.ground, RoundedCornerShape(20.dp))
                        .border(1.dp, if (on) clarity.yellow else clarity.controlOutline, RoundedCornerShape(20.dp))
                        .clickable { model.setDayBoundary(hour) }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                )
            }
        }
        Hint(
            "Hours after midnight, in am. At ${if (state.dayBoundaryHour == 0) 12 else state.dayBoundaryHour} am, " +
                "a 12:30 am promise still counts for the day before.",
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .background(clarity.surface, RoundedCornerShape(14.dp))
                .clickable(onClick = onOpenReliable)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Keep reminders reliable", style = MaterialTheme.typography.bodyLarge, color = clarity.text)
                Text(
                    "Battery, autostart, alarms and notifications on this phone",
                    style = MaterialTheme.typography.bodySmall,
                    color = clarity.muted,
                )
            }
            Text("Open", color = clarity.yellow)
        }

        Spacer(Modifier.height(20.dp))
        Text("Advanced", style = MaterialTheme.typography.titleMedium, color = clarity.text)
        Hint(
            "Search is built from your messages and summaries: by keyword, and by meaning with " +
                "bge-small-en-v1.5, which runs on this phone. Rebuilding either loses nothing.",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { model.rebuildSearch() }, enabled = !state.rebuilding) {
                Text(if (state.rebuilding) "Rebuilding" else "Rebuild search", color = clarity.yellow)
            }
            TextButton(onClick = { model.reindex() }) { Text("Re-index meaning", color = clarity.yellow) }
        }
        state.rebuildLine?.let { Hint(it) }

        Spacer(Modifier.height(20.dp))
        Text("Privacy", style = MaterialTheme.typography.titleMedium, color = clarity.text)
        SwitchRow(
            "App lock",
            if (state.canLock) {
                "Asks for your fingerprint or screen lock when you open Clarity, and after a minute away."
            } else {
                "This phone has no screen lock, so Clarity cannot lock. Set one in the phone's settings first."
            },
            state.appLock,
        ) { model.setAppLock(it) }
        SwitchRow(
            "Hide in recents",
            "The app switcher shows a blank card instead of your conversation. Applies the next time Clarity opens.",
            state.hideInRecents,
        ) { model.setHideInRecents(it) }

        YourData(state, model)
        HealthSection()
        BenchSection()
        TextButton(onClick = onOpenPrompts) { Text("Edit the coach's prompts", color = clarity.yellow) }

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
            TextButton(onClick = { model.clearCrash() }) { Text("Clear", color = clarity.yellow) }
        }
        Hint("Version ${state.version}")
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

    Spacer(Modifier.height(20.dp))
    Text("Your data", style = MaterialTheme.typography.titleMedium, color = clarity.text)
    Hint(
        "Backups are encrypted with your passphrase and saved once a week to a folder you choose. " +
            "The newest four are kept. Write the passphrase on paper: without it a backup cannot be restored.",
    )
    Label("Backup folder")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            state.backupFolder.ifBlank { "Not chosen yet" },
            style = MaterialTheme.typography.bodyMedium,
            color = if (state.backupFolder.isBlank()) clarity.muted else clarity.text,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { folder.launch(null) }) { Text("Choose", color = clarity.yellow) }
    }
    Label("Backup passphrase")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (state.hasPassphrase) "Set" else "Not set yet",
            style = MaterialTheme.typography.bodyMedium,
            color = if (state.hasPassphrase) clarity.text else clarity.muted,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { passphraseDialog = true }) {
            Text(if (state.hasPassphrase) "Change" else "Set", color = clarity.yellow)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { model.backUpNow() }, enabled = !state.backingUp) {
            Text(if (state.backingUp) "Backing up" else "Back up now", color = clarity.yellow)
        }
        TextButton(onClick = { file.launch(arrayOf("*/*")) }, enabled = !state.restoring) {
            Text("Restore from a backup", color = clarity.yellow)
        }
    }
    state.backupLine?.let { Hint(it) }
    if (state.restoreUri == null) state.restoreLine?.let { Hint(it) }
    TextButton(onClick = { warnExport = true }, enabled = !state.exporting) {
        Text(if (state.exporting) "Exporting" else "Export everything", color = clarity.yellow)
    }
    Hint("A readable copy (Markdown and JSON) of your conversations, goals, check-ins, promises, notes and chapters.")
    state.exportLine?.let { Hint(it) }
    var erasing by remember { mutableStateOf(false) }
    TextButton(onClick = { erasing = true }, enabled = !state.erasing) {
        Text(if (state.erasing) "Erasing" else "Erase everything", color = clarity.danger)
    }
    Hint("Deletes everything Clarity keeps on this phone. Backups in your folder are not touched.")
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
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        com.clarity.life.core.Thinking.LEVELS.forEach { (id, label) ->
            val on = current == id
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (on) clarity.ground else clarity.text,
                modifier = Modifier
                    .background(if (on) clarity.yellow else clarity.ground, RoundedCornerShape(20.dp))
                    .border(1.dp, if (on) clarity.yellow else clarity.controlOutline, RoundedCornerShape(20.dp))
                    .clickable { onPick(id) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            )
        }
    }
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
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable { onChange(!on) },
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
                checkedThumbColor = clarity.ground,
                checkedTrackColor = clarity.yellow,
                uncheckedThumbColor = clarity.muted,
                uncheckedTrackColor = clarity.surface,
                uncheckedBorderColor = clarity.controlOutline,
            ),
        )
    }
}
