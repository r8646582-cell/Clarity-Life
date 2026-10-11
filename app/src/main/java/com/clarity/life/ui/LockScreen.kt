package com.clarity.life.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.clarity.life.ui.theme.clarity

/** What shows while the app is locked: nothing of his, only the way back in. */
@Composable
fun LockScreen(onUnlock: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(clarity.ground).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Clarity is locked.", style = MaterialTheme.typography.headlineSmall, color = clarity.text)
        Text(
            "Use your fingerprint or your phone's screen lock.",
            style = MaterialTheme.typography.bodyMedium,
            color = clarity.muted,
            modifier = Modifier.padding(top = 8.dp),
        )
        TextButton(onClick = onUnlock, modifier = Modifier.padding(top = 16.dp)) { Text("Unlock", color = clarity.yellow) }
    }
}
