package com.clarity.life.ui.talk

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clarity.life.data.entity.ConversationRow
import com.clarity.life.ui.theme.clarity

internal val CHAT_TYPES = listOf(
    "free" to "Free talk",
    "untangle" to "Untangle",
    "decide" to "Decide",
    "plan" to "Plan and track",
)

/**
 * The conversations drawer (spec section 2.1, docs/mockups/Drawer.dc.html): a panel from the left with New,
 * a search box (titles and summaries), every conversation newest first under yellow day headings, the open
 * one lit, and a ⋯ button on each row for Rename and Forget.
 */
@Composable
internal fun ConversationsDrawer(
    state: TalkViewModel.State,
    onOpen: (Long) -> Unit,
    onNew: () -> Unit,
    onClose: () -> Unit,
    onForget: (Long) -> Unit,
    onRename: (Long, String) -> Unit,
    onSearch: (String) -> Unit,
    dayOf: (Long) -> String,
) {
    var query by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var renamingId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<Long?>(null) }
    var renamingTitle by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    // A search left from last time would hide conversations he expects to see when he opens it again.
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { onSearch("") } }
    Column(
        Modifier
            .fillMaxHeight()
            .widthIn(max = 330.dp)
            .fillMaxWidth(0.86f)
            .background(clarity.surface)
            .border(1.dp, clarity.hairline)
            // Taps inside the panel stay inside it; only the dimmed part closes it.
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
            ) {}
            .padding(top = 18.dp, bottom = 16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Conversations",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                color = clarity.text,
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Outlined.Close, contentDescription = "Close conversations", tint = clarity.text)
            }
        }
        Row(
            Modifier
                .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 10.dp)
                .fillMaxWidth()
                .heightIn(min = 46.dp)
                .background(clarity.yellow, RoundedCornerShape(12.dp))
                .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onNew),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null, tint = clarity.onYellow, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(6.dp))
            Text(
                "New conversation",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                color = clarity.onYellow,
            )
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it; onSearch(it) },
            placeholder = { Text("Search conversations", color = clarity.muted) },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = clarity.muted) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedTextColor = clarity.text,
                unfocusedTextColor = clarity.text,
                focusedContainerColor = clarity.raised,
                unfocusedContainerColor = clarity.raised,
                cursorColor = clarity.yellow,
                focusedBorderColor = clarity.yellow,
                unfocusedBorderColor = clarity.hairline,
            ),
        )
        if (state.conversations.isEmpty()) {
            Text(
                if (query.isBlank()) "No conversations yet." else "Nothing matches that.",
                style = MaterialTheme.typography.bodyMedium,
                color = clarity.muted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            )
        }
        // Headings are worked out once per list, not on every row drawn.
        val rows = remember(state.conversations) {
            var last: String? = null
            state.conversations.map { row ->
                val day = dayOf(row.startedAt)
                (if (day != last) day else null).also { last = day } to row
            }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(top = 6.dp)) {
            items(rows, key = { it.second.id }) { (heading, row) ->
                Column {
                    heading?.let {
                        Text(
                            it.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            letterSpacing = 1.sp,
                            color = clarity.yellow,
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp),
                        )
                    }
                    DrawerRow(
                        row = row,
                        open = row.id == state.conversationId,
                        kind = state.focuses.firstOrNull { it.id == row.focusId }?.let { "Focus: ${it.name}" }
                            ?: CHAT_TYPES.firstOrNull { it.first == row.chatType }?.second ?: "Free talk",
                        onOpen = { onOpen(row.id) },
                        onRename = {
                            renamingId = row.id
                            renamingTitle = row.title
                        },
                        onForget = { onForget(row.id) },
                    )
                }
            }
        }
    }

    renamingId?.let { id ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { renamingId = null },
            containerColor = clarity.surface,
            title = { Text("Rename", color = clarity.text) },
            text = {
                OutlinedTextField(
                    value = renamingTitle,
                    onValueChange = { renamingTitle = it.take(TalkViewModel.MAX_TITLE_CHARS) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedTextColor = clarity.text,
                        unfocusedTextColor = clarity.text,
                        cursorColor = clarity.yellow,
                        focusedBorderColor = clarity.yellow,
                        unfocusedBorderColor = clarity.controlOutline,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRename(id, renamingTitle)
                    renamingId = null
                }, enabled = renamingTitle.isNotBlank()) { Text("Save", color = clarity.yellow) }
            },
            dismissButton = { TextButton(onClick = { renamingId = null }) { Text("Cancel", color = clarity.text) } },
        )
    }
}

/** One conversation in the drawer: its title, its kind and start time, and a ⋯ menu for Rename and Forget. */
@Composable
private fun DrawerRow(
    row: ConversationRow,
    open: Boolean,
    kind: String,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onForget: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val time = remember(row.startedAt) {
        com.clarity.life.core.TimeFacts.clock(
            java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(row.startedAt), java.time.ZoneId.systemDefault()),
        )
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (open) clarity.raised else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onOpen)
            .heightIn(min = 60.dp)
            .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                row.title.ifBlank { "Untitled" },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (open) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.SemiBold,
                color = clarity.text,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Text(
                "$kind · $time",
                style = MaterialTheme.typography.bodySmall,
                color = clarity.muted,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Outlined.MoreHoriz, contentDescription = "More for this conversation", tint = clarity.muted)
            }
            DropdownMenu(
                expanded = menu,
                onDismissRequest = { menu = false },
                containerColor = clarity.raised,
            ) {
                DropdownMenuItem(
                    text = { Text("Rename", color = clarity.text) },
                    onClick = { menu = false; onRename() },
                )
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("Forget this conversation", color = clarity.danger)
                            Text(
                                "Also removes anything learned only from it",
                                style = MaterialTheme.typography.bodySmall,
                                color = clarity.muted,
                            )
                        }
                    },
                    onClick = { menu = false; onForget() },
                )
            }
        }
    }
}
