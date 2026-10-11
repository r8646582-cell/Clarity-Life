package com.clarity.life.ui.reliable

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clarity.life.ui.theme.clarity

/** Keep reminders reliable (docs/mockups/Reliable.dc.html). */
@Composable
fun ReliableScreen(onBack: () -> Unit, model: ReliableViewModel = hiltViewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleResumeEffect(Unit) {
        model.refresh()
        onPauseOrDispose { }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(clarity.ground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back to Settings", tint = clarity.muted)
        }
        Text("Keep reminders reliable.", style = MaterialTheme.typography.headlineMedium, color = clarity.text)
        Muted(
            "Xiaomi phones stop apps in the background to save battery. These four settings let a reminder " +
                "you set actually ring.",
        )

        Setting(
            "Battery saver",
            ok = state.batteryUnrestricted,
            okLine = "No restrictions",
            badLine = "Limited: reminders may ring late or not at all",
            steps = listOf("Tap Open settings.", "Choose Allow (or No restrictions).", "Come back here."),
        ) { model.open(context, model.batteryIntent()) }

        Setting(
            "Autostart",
            ok = null,
            okLine = "",
            badLine = "No app can read this one. Check it by hand, once.",
            steps = listOf("Tap Open settings.", "Find Clarity in the list.", "Turn on Autostart, then come back here."),
        ) { model.open(context, model.autostartIntent()) }

        Setting(
            "Alarms and reminders",
            ok = state.exactAlarms,
            okLine = "Allowed, so reminders ring on time",
            badLine = "Not allowed: reminders may ring a few minutes late",
            steps = listOf("Tap Open settings.", "Turn on Allow setting alarms and reminders.", "Come back here."),
        ) { model.open(context, model.exactAlarmIntent()) }

        Setting(
            "Notifications",
            ok = state.notifications,
            okLine = "Allowed",
            badLine = "Off: a reminder cannot show at all",
            steps = listOf("Tap Open settings.", "Turn on notifications for Clarity.", "Come back here."),
        ) { model.open(context, model.notificationIntent()) }

        Text("Check it for real", style = MaterialTheme.typography.titleMedium, color = clarity.text)
        Muted("Lock the phone after tapping. If nothing rings in a minute, one of the settings above is still blocking it.")
        Button(
            onClick = { model.test() },
            modifier = Modifier.heightIn(min = 44.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = clarity.yellow, contentColor = clarity.ground),
        ) { Text(if (state.testSet) "Test set: lock the phone now" else "Send a test reminder in 1 minute") }

        Muted("After the phone restarts, the app sets every reminder again on its own.")
        Text("", modifier = Modifier.padding(bottom = 24.dp))
    }
}

@Composable
private fun Setting(
    title: String,
    ok: Boolean?,
    okLine: String,
    badLine: String,
    steps: List<String>,
    onOpen: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(clarity.surface, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row {
            Text(title, style = MaterialTheme.typography.titleMedium, color = clarity.text, modifier = Modifier.weight(1f))
            Text(
                when (ok) {
                    true -> "OK"
                    false -> "Needs attention"
                    null -> "Check by hand"
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (ok == true) clarity.yellow else clarity.danger,
            )
        }
        Muted(if (ok == true) okLine else badLine)
        if (ok != true) {
            steps.forEachIndexed { i, s -> Muted("${i + 1}. $s") }
            Button(
                onClick = onOpen,
                modifier = Modifier.heightIn(min = 44.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = clarity.ground, contentColor = clarity.text),
            ) { Text("Open settings") }
        }
    }
}

@Composable
private fun Muted(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = clarity.muted)
