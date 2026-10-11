package com.clarity.life.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.clarity.life.bench.BenchRunner
import com.clarity.life.ui.theme.clarity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class BenchViewModel @Inject constructor(private val runner: BenchRunner) : ViewModel() {
    val run: StateFlow<BenchRunner.Run?> = runner.run
    private val _last = MutableStateFlow<String?>(null)
    val last: StateFlow<String?> = _last
    val count: Int get() = runner.scenarioCount

    fun refresh() = viewModelScope.launch { _last.value = runner.lastLine() }

    fun start() = runner.start()

    fun stop() = runner.stop()
}

/**
 * Settings, Test bench (prompts/testbench.md): run every scenario on the real model after changing the model,
 * the thinking level or a prompt. Each result shows pass or fail for its automatic checks; tap one to read the
 * reply next to what a good reply looks like, and judge the rest yourself.
 */
@Composable
fun BenchSection(model: BenchViewModel = hiltViewModel()) {
    val run by model.run.collectAsStateWithLifecycle()
    val last by model.last.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(run?.running) { model.refresh() }

    Spacer(Modifier.height(20.dp))
    Text("Test bench", style = MaterialTheme.typography.titleMedium, color = clarity.text)
    Text(
        "Fixed scenarios that check the coach after you change the model, the thinking level or a prompt. " +
            "They run off the record: nothing is saved and none of your memory is used.",
        style = MaterialTheme.typography.bodySmall,
        color = clarity.muted,
        modifier = Modifier.padding(vertical = 4.dp),
    )
    last?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = clarity.text) }
    val r = run
    Row {
        if (r?.running == true) {
            TextButton(onClick = { model.stop() }) { Text("Stop", color = clarity.danger) }
        } else {
            TextButton(onClick = { confirm = true }) { Text("Run the test bench", color = clarity.yellow) }
        }
    }
    if (r != null) {
        val status = if (r.running) "Running ${r.results.size + 1} of ${r.total} on ${r.model}" else
            "${r.passed} of ${r.scored} passed their checks on ${r.model}"
        Text(status, style = MaterialTheme.typography.bodySmall, color = clarity.text)
        r.line?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = clarity.danger) }
        Column(Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // Failures first, so the ones to look at are on top.
            r.results.sortedBy { it.passed }.forEach { res ->
                val mark = when {
                    res.error != null -> "Did not run"
                    res.checks == 0 -> "Judge"
                    res.passed -> "Pass"
                    else -> "Fail"
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(clarity.surface, RoundedCornerShape(10.dp))
                        .clickable { open = if (open == res.name) null else res.name }
                        .padding(10.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(res.name, style = MaterialTheme.typography.bodyMedium, color = clarity.text, modifier = Modifier.weight(1f))
                        Text(
                            mark,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (mark == "Fail" || mark == "Did not run") clarity.danger else clarity.muted,
                        )
                    }
                    if (open == res.name) {
                        if (res.failed.isNotEmpty()) {
                            Text("Failed: " + res.failed.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = clarity.danger)
                        }
                        res.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = clarity.danger) }
                        Text("Expect: ${res.expect}", style = MaterialTheme.typography.bodySmall, color = clarity.muted)
                        if (res.reply.isNotBlank()) Text(res.reply, style = MaterialTheme.typography.bodySmall, color = clarity.text)
                    }
                }
            }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            containerColor = clarity.surface,
            title = { Text("Run ${model.count} scenarios?", color = clarity.text) },
            text = {
                Text(
                    "Each one is sent to your chat model with its thinking level, so this costs about as much as " +
                        "${model.count} short chats and takes several minutes. You can leave this screen while it runs.",
                    color = clarity.muted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    model.start()
                }) { Text("Run", color = clarity.yellow) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel", color = clarity.text) } },
        )
    }
}
