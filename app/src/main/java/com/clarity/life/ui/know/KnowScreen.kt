package com.clarity.life.ui.know

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clarity.life.ui.know.KnowViewModel.Item
import com.clarity.life.ui.know.KnowViewModel.Ref
import com.clarity.life.ui.theme.clarity

/**
 * What I know (docs/mockups/Know.dc.html): sections collapsed by default, each item opening to its evidence
 * with Edit, Delete and That's right. A search opens every section that has a match. Deleting asks first,
 * because a deletion is final.
 */
@Composable
fun KnowScreen(model: KnowViewModel = hiltViewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(setOf<String>()) }
    var expanded by remember { mutableStateOf<Ref?>(null) }
    var editing by remember { mutableStateOf<Item?>(null) }
    var deleting by remember { mutableStateOf<Item?>(null) }
    // The box shows what he typed at once; the filtered list follows (a field fed back from the model's
    // async state drops letters and makes the cursor jump while typing fast).
    var typed by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(state.query) }
    val searching = state.query.isNotBlank()

    LazyColumn(Modifier.fillMaxSize().background(clarity.ground)) {
        item {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp)) {
                Text(
                    buildAnnotatedString {
                        append("What I know")
                        withStyle(SpanStyle(color = clarity.yellow)) { append(".") }
                    },
                    style = MaterialTheme.typography.displaySmall,
                    color = clarity.text,
                )
                Text(
                    "Only what you said or did. Edit or delete anything; a deletion is final.",
                    style = MaterialTheme.typography.bodySmall,
                    color = clarity.muted,
                    modifier = Modifier.padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = typed,
                    onValueChange = {
                        typed = it
                        model.search(it)
                    },
                    singleLine = true,
                    placeholder = { Text("Search what I know", color = clarity.muted) },
                    shape = RoundedCornerShape(12.dp),
                    colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
                )
                state.quietLine?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = clarity.danger)
                }
            }
        }
        val sections = state.sections + KnowViewModel.Section("Archive", state.archive)
        for (section in sections) {
            if (section.name == "Archive" && section.items.isEmpty()) continue
            val isOpen = searching || section.name in open
            item(key = "section:" + section.name) {
                SectionRow(section.name, section.items.size, isOpen) {
                    open = if (section.name in open) open - section.name else open + section.name
                }
            }
            if (isOpen) {
                if (section.items.isEmpty()) {
                    item(key = "empty:" + section.name) {
                        Text(
                            if (searching) "Nothing here matches." else "Nothing yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = clarity.muted,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                    }
                }
                items(section.items, key = { section.name + ":" + it.ref.toString() }) { item ->
                    ItemCard(
                        item = item,
                        expanded = expanded == item.ref,
                        onToggle = { expanded = if (expanded == item.ref) null else item.ref },
                        onEdit = { editing = item },
                        onDelete = { deleting = item },
                        onConfirm = { model.confirm(item.ref) },
                        onStatus = { status -> model.setStatus(item.ref, status) },
                    )
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    editing?.let { item -> EditDialog(item, onDismiss = { editing = null }) { text, second ->
        model.edit(item.ref, text, second)
        editing = null
    } }

    deleting?.let { item ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = clarity.surface,
            title = { Text("Delete this?", color = clarity.text) },
            text = {
                Text(
                    if (item.ref is Ref.Note) {
                        "A deletion is final. The coach will not see it again, and nothing like it will be noted again."
                    } else {
                        "This removes it from what the coach knows."
                    },
                    color = clarity.muted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    model.delete(item.ref)
                    if (expanded == item.ref) expanded = null
                    deleting = null
                }) { Text("Delete", color = clarity.danger) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep it", color = clarity.text) } },
        )
    }
}

@Composable
private fun SectionRow(name: String, count: Int, isOpen: Boolean, onClick: () -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onClick),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(name, style = MaterialTheme.typography.titleLarge, color = if (isOpen) clarity.yellow else clarity.text)
            Text(count.toString(), style = MaterialTheme.typography.bodySmall, color = clarity.muted)
        }
        HorizontalDivider(color = clarity.hairline)
    }
}

@Composable
private fun ItemCard(
    item: Item,
    expanded: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onConfirm: () -> Unit,
    onStatus: (String) -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .background(clarity.surface, RoundedCornerShape(14.dp))
            .clickable(onClick = onToggle)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (item.grade != null || item.seen != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item.grade?.let { GradeTag(it, item.confidence) }
                item.seen?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = clarity.muted) }
            }
        }
        item.label?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = clarity.muted) }
        Text(
            item.text,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = if (item.confidence == "confirmed") FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = clarity.text,
            maxLines = if (expanded) Int.MAX_VALUE else 3,
        )
        if (expanded) {
            if (item.evidence.isNotEmpty()) {
                HorizontalDivider(color = clarity.hairline)
                item.evidence.forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = clarity.muted)
                }
            }
            if (item.canEdit || item.canDelete || item.canConfirm || item.statusActions.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (item.canEdit) OutlineButton("Edit", clarity.text, onEdit)
                    item.statusActions.forEach { (label, status) -> OutlineButton(label, clarity.text) { onStatus(status) } }
                    if (item.canDelete) OutlineButton("Delete", clarity.danger, onDelete)
                    if (item.canConfirm) {
                        Text(
                            "That's right",
                            style = MaterialTheme.typography.labelLarge,
                            color = clarity.ground,
                            modifier = Modifier
                                .heightIn(min = 44.dp)
                                .background(clarity.yellow, RoundedCornerShape(10.dp))
                                .clickable(onClick = onConfirm)
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GradeTag(text: String, confidence: String?) {
    val shape = RoundedCornerShape(6.dp)
    val (fill, fg, outline) = when (confidence) {
        "confirmed" -> Triple(clarity.yellow, clarity.ground, clarity.yellow)
        "likely" -> Triple(Color.Transparent, clarity.yellow, clarity.yellow)
        else -> Triple(Color.Transparent, clarity.muted, clarity.controlOutline)
    }
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = fg,
        modifier = Modifier
            .background(fill, shape)
            .border(1.dp, outline, shape)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun OutlineButton(text: String, color: Color, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = color,
        modifier = Modifier
            .heightIn(min = 44.dp)
            .border(1.dp, clarity.controlOutline, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/** Edit a note or a profile entry (one field), or a person (relation and notes). */
@Composable
private fun EditDialog(item: Item, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    val person = item.ref is Ref.Person
    var first by remember(item.ref) { mutableStateOf(item.editValues.getOrElse(0) { "" }) }
    var second by remember(item.ref) { mutableStateOf(item.editValues.getOrElse(1) { "" }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = clarity.surface,
        title = { Text(item.label ?: "Edit", color = clarity.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = first,
                    onValueChange = { first = it },
                    label = { Text(if (person) "Who they are to you" else "In your words", color = clarity.muted) },
                    colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (person) {
                    OutlinedTextField(
                        value = second,
                        onValueChange = { second = it },
                        label = { Text("Notes", color = clarity.muted) },
                        colors = fieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(
                        if (item.ref is Ref.Focus) {
                            "The coach carries on from your version after the next conversation in this focus."
                        } else {
                            "Your version is kept as confirmed, and the coach will not change it."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = clarity.muted,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(first, second) }) { Text("Save", color = clarity.yellow) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = clarity.text) } },
    )
}

@Composable
private fun fieldColors() = TextFieldDefaults.colors(
    focusedTextColor = clarity.text,
    unfocusedTextColor = clarity.text,
    focusedContainerColor = clarity.raised,
    unfocusedContainerColor = clarity.raised,
    focusedIndicatorColor = clarity.yellow,
    unfocusedIndicatorColor = clarity.hairline,
    cursorColor = clarity.yellow,
)
