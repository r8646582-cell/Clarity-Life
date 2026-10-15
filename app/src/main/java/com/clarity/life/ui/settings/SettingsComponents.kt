@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.clarity.life.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clarity.life.ui.theme.clarity

@Composable
internal fun SecretField(value: String, label: String, onChange: (String) -> Unit) {
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
internal fun Levels(current: String, onPick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        com.clarity.life.core.Thinking.LEVELS.forEach { (id, label) ->
            Chip(label, current == id) { onPick(id) }
        }
    }
}

/** One choice in a row of chips: yellow when picked. Never breaks a word across two lines. */
@Composable
internal fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
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
internal fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = clarity.muted,
        modifier = Modifier.padding(top = 18.dp, bottom = 6.dp),
    )
}

@Composable
internal fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = clarity.muted,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
internal fun Field(value: String, onChange: (String) -> Unit) {
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
internal fun BackupRow(title: String, value: String, button: @Composable () -> Unit) {
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
                overflow = TextOverflow.Ellipsis,
            )
        }
        button()
    }
}

@Composable
internal fun fieldColors() = TextFieldDefaults.colors(
    focusedTextColor = clarity.text,
    unfocusedTextColor = clarity.text,
    focusedContainerColor = clarity.surface,
    unfocusedContainerColor = clarity.surface,
    focusedIndicatorColor = clarity.controlOutline,
    unfocusedIndicatorColor = clarity.hairline,
    cursorColor = clarity.yellow,
)

@Composable
internal fun SwitchRow(title: String, hint: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable { onChange(!on) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = clarity.text)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = clarity.muted)
        }
        Switch(
            checked = on,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
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
internal fun Group(
    title: String,
    subtitle: String,
    icon: ImageVector,
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
            HorizontalDivider(color = clarity.hairline)
            Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 18.dp)) { content() }
        }
    }
}

/** A small real button for an action inside a row: Save, Choose, Open, Change. */
@Composable
internal fun SmallButton(
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
        fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Medium,
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
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/**
 * Health check at the top of Settings: is the coach set up, can reminders ring on time on this phone, is there
 * a backup. Read from the phone and the settings, never guessed; each problem has the tap that fixes it.
 */
@Composable
internal fun HealthCheck(
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
internal fun HealthRow(good: Boolean, text: String, detail: String?, action: String?, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        androidx.compose.foundation.layout.Box(
            Modifier
                .size(8.dp)
                .background(if (good) clarity.green else clarity.yellow, CircleShape),
        )
        Text(text, style = MaterialTheme.typography.bodyLarge, color = clarity.text, modifier = Modifier.weight(1f))
        detail?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = clarity.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 140.dp),
            )
        }
        action?.let { SmallButton(it, primary = true, onClick = onAction) }
    }
}
