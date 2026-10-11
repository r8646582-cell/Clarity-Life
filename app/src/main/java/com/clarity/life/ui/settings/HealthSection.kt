package com.clarity.life.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.clarity.life.data.ClarityDatabase
import com.clarity.life.settings.SettingsRepository
import com.clarity.life.ui.theme.clarity
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Health (spec section 18, point 13): loud, content-free diagnostics. The last run of each background job,
 * how often each error code was logged in the last 30 days (cards dropped, quotes or numbers rejected, JSON
 * rejected ...), interrupted replies, this month's calls and how much of the input the cache covered, backup
 * age, database size, and the yearly restore test he marks himself. A rising count after a model or prompt
 * change says something shifted. No message text and no key ever appears here.
 */
@HiltViewModel
class HealthViewModel @Inject constructor(
    @ApplicationContext private val context: android.content.Context,
    private val db: ClarityDatabase,
    private val settings: SettingsRepository,
) : ViewModel() {

    data class State(val lines: List<Pair<String, String>> = emptyList(), val restoreTestDue: Boolean = false)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    fun refresh() = viewModelScope.launch {
        val now = System.currentTimeMillis()
        val d = db.diagnostics()
        val lines = mutableListOf<Pair<String, String>>()
        for (job in d.lastFinishedJobs()) {
            val at = job.finishedAt ?: job.startedAt ?: job.createdAt
            val how = when (job.status) {
                "succeeded" -> "done"
                "skipped" -> "nothing to do"
                else -> "failed" + (job.lastError?.let { " ($it)" } ?: "")
            }
            lines.add(JOB_NAMES.getOrDefault(job.type, job.type) to "${ago(now - at)}, $how")
        }
        if (lines.isEmpty()) lines.add("Background jobs" to "none has run yet")
        d.jobsWaiting().takeIf { it > 0 }?.let { lines.add("Jobs waiting" to "$it") }
        lines.add("Interrupted replies" to "${d.interruptedReplies()}")
        val since = now - 30L * DAY
        val codes = d.codeCounts(since)
        if (codes.isEmpty()) lines.add("Errors, last 30 days" to "none")
        for (c in codes) lines.add(c.code to "${c.n} in 30 days")
        val month = com.clarity.life.core.TimeFacts.local(Instant.ofEpochMilli(now), settings.timeZone()).toLocalDate().toString().take(7)
        d.usage(month)?.let { u ->
            val cached = if (u.tokensIn > 0) " (${u.tokensCached * 100 / u.tokensIn}% from the cache)" else ""
            // Formatted piece by piece: the cache share has a % sign in it, which format() would read as a code.
            lines.add("This month" to "${u.calls} calls, ${"%,d".format(u.tokensIn)} tokens in$cached, ${"%,d".format(u.tokensOut)} out")
        }
        val backupAt = settings.get(SettingsRepository.Keys.LAST_BACKUP_AT, "").toLongOrNull()
        lines.add("Last verified backup" to (backupAt?.let { ago(now - it) } ?: "never"))
        val size = context.getDatabasePath(ClarityDatabase.NAME).length()
        lines.add("Database size" to "%.1f MB".format(size / 1_048_576.0))
        val restoreAt = settings.get(KEY_RESTORE_TEST, "").toLongOrNull()
        lines.add("Restore test" to (restoreAt?.let { "done ${ago(now - it)}" } ?: "not done yet"))
        _state.value = State(lines, restoreAt == null || now - restoreAt > 365L * DAY)
    }

    /** He restored a backup on a second phone or an emulator and checked it: marked once a year. */
    fun markRestoreTest() = viewModelScope.launch {
        settings.set(KEY_RESTORE_TEST, System.currentTimeMillis().toString())
        refresh()
    }

    private fun ago(ms: Long): String {
        val minutes = ms / 60_000
        return when {
            minutes < 2 -> "just now"
            minutes < 120 -> "$minutes minutes ago"
            minutes < 48 * 60 -> "${minutes / 60} hours ago"
            else -> "${minutes / (24 * 60)} days ago"
        }
    }

    companion object {
        const val KEY_RESTORE_TEST = "restore_test_at"
        private const val DAY = 86_400_000L
        private val JOB_NAMES = mapOf(
            "reflection" to "Reflection",
            "review_week" to "Weekly review",
            "review_month" to "Monthly review",
            "letter" to "Weekly letter",
            "chapter" to "Chapter",
            "gardening" to "Gardening",
            "backup" to "Backup",
            "index" to "Search index",
        )
    }
}

@Composable
fun HealthSection(model: HealthViewModel = hiltViewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { model.refresh() }
    Spacer(Modifier.height(20.dp))
    Text("Health", style = MaterialTheme.typography.titleMedium, color = clarity.text)
    Text(
        "Counts only, never what was said. A count that starts rising after you change the model or a prompt is worth a look.",
        style = MaterialTheme.typography.bodySmall,
        color = clarity.muted,
        modifier = Modifier.padding(vertical = 4.dp),
    )
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        state.lines.forEach { (k, v) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(k, style = MaterialTheme.typography.bodySmall, color = clarity.muted)
                Spacer(Modifier.widthIn(min = 12.dp))
                Text(v, style = MaterialTheme.typography.bodySmall, color = clarity.text)
            }
        }
    }
    if (state.restoreTestDue) {
        Text(
            "Once a year, restore a backup on a second phone or an emulator and check it, then mark it here.",
            style = MaterialTheme.typography.bodySmall,
            color = clarity.muted,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
    Row {
        TextButton(onClick = { model.refresh() }) { Text("Refresh", color = clarity.yellow) }
        TextButton(onClick = { model.markRestoreTest() }) { Text("I did the restore test", color = clarity.yellow) }
    }
}
