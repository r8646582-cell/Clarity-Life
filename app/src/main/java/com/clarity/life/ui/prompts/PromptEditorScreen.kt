package com.clarity.life.ui.prompts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.clarity.life.data.ClarityDatabase
import com.clarity.life.prompts.PromptStore
import com.clarity.life.ui.theme.clarity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The prompt editor (CLAUDE.md, Prompts): every prompt the app sends can be edited here. A version is saved
 * only if it passes contracts/prompts.json ([com.clarity.life.core.PromptContract], the same contract CI
 * checks), and Reset to built-in always brings back the copy inside the APK. After a save or a reset every
 * conversation rebuilds its context on its next message, so the change takes effect at once.
 */
@HiltViewModel
class PromptEditorViewModel @Inject constructor(
    private val prompts: PromptStore,
    private val db: ClarityDatabase,
) : ViewModel() {

    data class State(
        val names: List<Pair<String, Boolean>> = emptyList(),
        val open: String? = null,
        val text: String = "",
        val edited: Boolean = false,
        val problems: List<String> = emptyList(),
        val line: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    init {
        viewModelScope.launch { list() }
    }

    private suspend fun list() {
        _state.value = _state.value.copy(names = prompts.contract.prompts.keys.map { it to prompts.isOverridden(it) })
    }

    fun open(name: String) = viewModelScope.launch {
        _state.value = _state.value.copy(open = name, text = prompts.text(name), edited = prompts.isOverridden(name), problems = emptyList(), line = null)
    }

    fun close() {
        _state.value = _state.value.copy(open = null, problems = emptyList(), line = null)
    }

    fun edit(text: String) {
        _state.value = _state.value.copy(text = text, line = null)
    }

    fun save() = viewModelScope.launch {
        val name = _state.value.open ?: return@launch
        val problems = prompts.saveOverride(name, _state.value.text, System.currentTimeMillis())
        if (problems.isEmpty()) {
            db.conversations().clearAllPrefixes()
            _state.value = _state.value.copy(edited = true, problems = emptyList(), line = "Saved. The coach uses it from the next message. Run the test bench to check it.")
            list()
        } else {
            _state.value = _state.value.copy(problems = problems, line = null)
        }
    }

    fun reset() = viewModelScope.launch {
        val name = _state.value.open ?: return@launch
        prompts.resetToBuiltIn(name)
        db.conversations().clearAllPrefixes()
        _state.value = _state.value.copy(text = prompts.builtIn(name), edited = false, problems = emptyList(), line = "Back to the built-in version.")
        list()
    }
}

@Composable
fun PromptEditorScreen(onBack: () -> Unit, model: PromptEditorViewModel = hiltViewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    var confirmReset by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(clarity.ground).imePadding().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (state.open != null) model.close() else onBack() }) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = clarity.text)
            }
            Text(state.open ?: "Prompts", style = MaterialTheme.typography.titleLarge, color = clarity.text)
        }
        val open = state.open
        if (open == null) {
            Text(
                "These are the coach's instructions. An edit is saved only if it keeps every rule and placeholder the " +
                    "app needs; Reset to built-in always brings the original back.",
                style = MaterialTheme.typography.bodySmall,
                color = clarity.muted,
                modifier = Modifier.padding(vertical = 6.dp),
            )
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                state.names.forEach { (name, edited) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(clarity.surface, RoundedCornerShape(10.dp))
                            .clickable { model.open(name) }
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(name, style = MaterialTheme.typography.bodyLarge, color = clarity.text)
                        if (edited) Text("Edited", style = MaterialTheme.typography.labelMedium, color = clarity.yellow)
                    }
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (state.edited) "Your version" else "Built-in version",
                    style = MaterialTheme.typography.bodySmall,
                    color = clarity.muted,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { confirmReset = true }, enabled = state.edited) { Text("Reset to built-in", color = clarity.danger) }
                TextButton(onClick = { model.save() }) { Text("Save", color = clarity.yellow) }
            }
            state.line?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = clarity.text) }
            state.problems.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = clarity.danger) }
            OutlinedTextField(
                value = state.text,
                onValueChange = model::edit,
                modifier = Modifier.fillMaxWidth().weight(1f).padding(vertical = 8.dp),
                textStyle = MaterialTheme.typography.bodySmall,
                colors = TextFieldDefaults.colors(
                    focusedTextColor = clarity.text,
                    unfocusedTextColor = clarity.text,
                    focusedContainerColor = clarity.surface,
                    unfocusedContainerColor = clarity.surface,
                    cursorColor = clarity.yellow,
                ),
            )
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            containerColor = clarity.surface,
            title = { Text("Reset to built-in?", color = clarity.text) },
            text = { Text("Your version of this prompt is removed.", color = clarity.muted) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    model.reset()
                }) { Text("Reset", color = clarity.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Keep mine", color = clarity.text) } },
        )
    }
}
