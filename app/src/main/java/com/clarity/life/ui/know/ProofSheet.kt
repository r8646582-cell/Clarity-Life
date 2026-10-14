package com.clarity.life.ui.know

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.clarity.life.ui.know.KnowViewModel.Item
import com.clarity.life.ui.theme.Bold
import com.clarity.life.ui.theme.DangerButton
import com.clarity.life.ui.theme.Kicker
import com.clarity.life.ui.theme.PrimaryButton
import com.clarity.life.ui.theme.SecondaryButton
import com.clarity.life.ui.theme.clarity

/** How many moments show when the list is first unfolded; a note seen all year folds the rest behind one line. */
private const val FIRST_MOMENTS = 8

/**
 * A note's proof, over the page he was on (Your story or a What I know section), so a tap never drops him into
 * a long list: the note, its grade, how often and when it was seen, the newest thing he said about it, and every
 * moment folded under one line. That's right, Not quite (an edit in place) and Delete (asked twice) run the same
 * commands as What I know; the sheet itself writes nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProofSheet(
    item: Item,
    kind: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onEdit: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var open by rememberSaveable(item.ref.toString()) { mutableStateOf(false) }
    var all by rememberSaveable(item.ref.toString()) { mutableStateOf(false) }
    var editing by rememberSaveable(item.ref.toString()) { mutableStateOf(false) }
    var draft by rememberSaveable(item.ref.toString()) { mutableStateOf(item.text) }
    var asking by rememberSaveable(item.ref.toString()) { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = clarity.surface,
        scrimColor = Color.Black.copy(alpha = 0.6f),
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 6.dp)
                    .width(38.dp)
                    .heightIn(min = 4.dp)
                    .background(clarity.controlOutline, Bold.pillShape),
            )
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 22.dp, end = 22.dp, top = 6.dp, bottom = 22.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Kicker(kind) }
                item.grade?.let { GradeTag(it, item.confidence) }
            }
            Text(item.text, style = MaterialTheme.typography.headlineSmall, color = clarity.text)
            item.seen?.let { Text(it.replaceFirstChar { c -> c.uppercase() }, style = MaterialTheme.typography.bodySmall, color = clarity.muted) }

            item.moments.firstOrNull { it.said }?.let { m ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(clarity.ground, RoundedCornerShape(16.dp))
                        .border(1.dp, clarity.hairline, RoundedCornerShape(16.dp))
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("“${m.text}”", style = MaterialTheme.typography.headlineSmall.copy(fontSize = MaterialTheme.typography.titleLarge.fontSize), color = clarity.text)
                    Text("You, ${m.day}", style = MaterialTheme.typography.bodySmall, color = clarity.muted)
                }
            }

            if (item.moments.size > 1) {
                Column {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 44.dp)
                            .clickable(role = Role.Button) { open = !open },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "All ${item.moments.size} moments",
                            style = MaterialTheme.typography.bodyMedium,
                            color = clarity.muted,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            if (open) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                            contentDescription = if (open) "Fold the moments" else "Show the moments",
                            tint = clarity.muted,
                        )
                    }
                    if (open) {
                        val shown = if (all) item.moments else item.moments.take(FIRST_MOMENTS)
                        shown.forEach { m ->
                            HorizontalDivider(color = clarity.hairline)
                            Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(m.day, style = MaterialTheme.typography.bodySmall, color = clarity.muted, modifier = Modifier.width(52.dp))
                                Text(
                                    if (m.said) "“${m.text}”" else m.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (m.said) clarity.text else clarity.muted,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        val hidden = item.moments.size - shown.size
                        if (hidden > 0) {
                            HorizontalDivider(color = clarity.hairline)
                            Text(
                                "Show $hidden earlier",
                                style = MaterialTheme.typography.bodyMedium,
                                color = clarity.yellow,
                                modifier = Modifier
                                    .heightIn(min = 44.dp)
                                    .clickable(role = Role.Button) { all = true }
                                    .padding(vertical = 12.dp),
                            )
                        }
                    }
                }
            }

            when {
                editing -> {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        label = { Text("In your words", color = clarity.muted) },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = clarity.ground,
                            unfocusedContainerColor = clarity.ground,
                            focusedTextColor = clarity.text,
                            unfocusedTextColor = clarity.text,
                            cursorColor = clarity.yellow,
                            focusedIndicatorColor = clarity.yellow,
                            unfocusedIndicatorColor = clarity.controlOutline,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton("Cancel", Modifier.weight(1f)) {
                            draft = item.text
                            editing = false
                        }
                        PrimaryButton("Save", Modifier.weight(1f)) {
                            if (draft.isNotBlank()) onEdit(draft.trim())
                            editing = false
                        }
                    }
                }
                asking -> {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(clarity.coralTint, RoundedCornerShape(16.dp))
                            .border(1.dp, clarity.coralEdge, RoundedCornerShape(16.dp))
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            "Delete this for good? The coach will not see it again, and nothing like it will be noted again.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = clarity.text,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SecondaryButton("Keep it", Modifier.weight(1f)) { asking = false }
                            DangerButton("Delete", Modifier.weight(1f)) {
                                asking = false
                                onDelete()
                            }
                        }
                    }
                }
                else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (item.canConfirm) PrimaryButton("That's right", Modifier.weight(1f), onConfirm)
                    if (item.canEdit) SecondaryButton("Not quite", Modifier.weight(1f)) { editing = true }
                    if (item.canDelete) DangerButton("Delete", Modifier.weight(1f)) { asking = true }
                }
            }
        }
    }
}
