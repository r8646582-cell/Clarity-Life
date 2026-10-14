@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clarity.life.ui.know.KnowViewModel.Item
import com.clarity.life.ui.know.KnowViewModel.Ref
import com.clarity.life.ui.theme.clarity
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Check
import com.clarity.life.ui.theme.BoldCard
import com.clarity.life.ui.theme.Kicker
import com.clarity.life.ui.theme.PrimaryButton
import com.clarity.life.ui.theme.Quiet
import com.clarity.life.ui.theme.SecondaryButton
import com.clarity.life.ui.theme.TextAction

/**
 * What I know, redesigned (the owner's approved mockup of 13 Oct 2026): a short promise of what this is, the
 * search, one featured note the coach is not sure of yet (That's right / Not quite), then the sections as one
 * quiet list. A row opens its section; a search opens every section that has a match. A note opens its proof
 * sheet (That's right, Not quite, Delete); other items open in place. Deleting asks first, because it is final.
 */
@Composable
fun KnowScreen(onTalk: () -> Unit = {}, model: KnowViewModel = hiltViewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val focus by model.focus.collectAsStateWithLifecycle()
    focus?.let { f ->
        androidx.activity.compose.BackHandler { model.closeFocus() }
        FocusPage(f, onBack = { model.closeFocus() }, onTalk = {
            model.talkAboutFocus(f.focus.id)
            model.closeFocus()
            onTalk()
        })
        return
    }
    var section by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf<Ref?>(null) }
    var editing by remember { mutableStateOf<Item?>(null) }
    var deleting by remember { mutableStateOf<Item?>(null) }
    // The note whose proof sheet is open, over whatever page he is on (Your story or a section).
    var proof by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<Long?>(null) }
    // The box shows what he typed at once; the filtered list follows (a field fed back from the model's
    // async state drops letters and makes the cursor jump while typing fast).
    var typed by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(state.query) }
    // Your story (spec 2.5) opens over the list; its proof and chapters open their item here.
    var showStory by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    if (showStory) {
        StoryScreen(
            onBack = { showStory = false },
            onTalk = {
                showStory = false
                onTalk()
            },
            onProof = { n -> proof = n.id },
            onChapter = { id ->
                showStory = false
                typed = ""
                model.search("")
                section = "Chapters"
                expanded = Ref.Chapter(id)
            },
        )
        proof?.let { id -> NoteProof(id, state, model) { proof = null } }
        return
    }
    val searching = state.query.isNotBlank()
    val sections = state.sections + KnowViewModel.Section("Archive", state.archive)
    val shownSection = sections.firstOrNull { it.name == section }
    if (shownSection != null && !searching) androidx.activity.compose.BackHandler { section = null }
    // The note to check: one the coach is not sure of yet, so he can confirm or correct it in one tap.
    val toCheck = state.sections.flatMap { it.items }.firstOrNull { it.canConfirm }

    LazyColumn(Modifier.fillMaxSize().background(clarity.ground)) {
        item {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("What I know", style = MaterialTheme.typography.displayLarge, color = clarity.text)
                OutlinedTextField(
                    value = typed,
                    onValueChange = {
                        typed = it
                        model.search(it)
                    },
                    singleLine = true,
                    placeholder = { Text("Search what I know", color = clarity.muted) },
                    shape = RoundedCornerShape(16.dp),
                    colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
                )
                state.quietLine?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = clarity.danger)
                }
            }
        }
        when {
            searching -> {
                val hits = sections.filter { it.items.isNotEmpty() }
                if (hits.isEmpty()) {
                    item { Quiet("Nothing here matches.", Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
                }
                hits.forEach { s ->
                    item(key = "head:" + s.name) { SectionTitle(s.name) }
                    items(s.items, key = { s.name + ":" + it.ref.toString() }) { item ->
                        KnowRow(
                            item = item,
                            expanded = expanded == item.ref,
                            model = model,
                            onProof = { proof = it },
                            onExpand = { expanded = if (expanded == item.ref) null else item.ref },
                            onEdit = { editing = item },
                            onDelete = { deleting = item },
                        )
                    }
                }
            }
            shownSection != null -> {
                item(key = "back") {
                    Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextAction("‹ All") { section = null }
                    }
                }
                item(key = "title") { SectionTitle(shownSection.name) }
                if (shownSection.items.isEmpty()) {
                    item { Quiet("Nothing here yet. It fills in from your conversations.", Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
                }
                items(shownSection.items, key = { shownSection.name + ":" + it.ref.toString() }) { item ->
                    KnowRow(
                        item = item,
                        expanded = expanded == item.ref,
                        model = model,
                        onProof = { proof = it },
                        onExpand = { expanded = if (expanded == item.ref) null else item.ref },
                        onEdit = { editing = item },
                        onDelete = { deleting = item },
                    )
                }
            }
            else -> {
                item(key = "story") {
                    com.clarity.life.ui.theme.CalmCard(
                        Modifier.padding(horizontal = 20.dp, vertical = 8.dp).clickable { showStory = true },
                        gap = 6,
                    ) {
                        Kicker("Your story", clarity.yellow)
                        Text(
                            "What you did, what you got through, and how you changed.",
                            style = MaterialTheme.typography.headlineSmall,
                            color = clarity.text,
                        )
                        Text("Open your story", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = clarity.yellow)
                    }
                }
                if (toCheck != null) {
                    item(key = "check") {
                        BoldCard(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), gap = 12) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f)) { Kicker("Is this right?") }
                                toCheck.grade?.let { GradeTag(it, toCheck.confidence) }
                            }
                            Text(toCheck.text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = clarity.text)
                            toCheck.evidence.firstOrNull()?.let { e ->
                                Column(
                                    Modifier.fillMaxWidth().background(clarity.raised, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
                                ) { Text(e, style = MaterialTheme.typography.bodySmall, color = clarity.muted) }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PrimaryButton("That's right", Modifier.weight(1f)) { model.confirm(toCheck.ref) }
                                SecondaryButton("Not quite", Modifier.weight(1f)) { editing = toCheck }
                            }
                        }
                    }
                }
                item(key = "sections") {
                    SectionList(state, Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) { section = it }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    proof?.let { id -> NoteProof(id, state, model) { proof = null } }

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

/** One item in a section or a search: a note opens its proof sheet, a focus its page, anything else opens in place. */
@Composable
private fun KnowRow(
    item: Item,
    expanded: Boolean,
    model: KnowViewModel,
    onProof: (Long) -> Unit,
    onExpand: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val note = item.ref as? Ref.Note
    if (note != null) {
        NoteLine(item) { onProof(note.id) }
        return
    }
    ItemCard(
        item = item,
        expanded = expanded,
        onToggle = { (item.ref as? Ref.Focus)?.let { model.openFocus(it.id) } ?: onExpand() },
        onEdit = onEdit,
        onDelete = onDelete,
        onConfirm = { model.confirm(item.ref) },
        onStatus = { status -> model.setStatus(item.ref, status) },
    )
}

/**
 * The sections as one quiet list: the name, and how many on the right in the same column, so a full year of
 * notes reads as calmly as the first week. An empty section shows a dash, never a bare 0 or "Nothing yet".
 */
@Composable
private fun SectionList(state: KnowViewModel.State, modifier: Modifier, onOpen: (String) -> Unit) {
    val rows = state.sections.map { it.name to it.items.size } +
        (if (state.archive.isNotEmpty()) listOf("Archive" to state.archive.size) else emptyList())
    Column(
        modifier
            .fillMaxWidth()
            .background(clarity.surface, com.clarity.life.ui.theme.Bold.cardShape)
            .border(1.dp, clarity.hairline, com.clarity.life.ui.theme.Bold.cardShape),
    ) {
        rows.forEachIndexed { i, (name, count) ->
            if (i > 0) HorizontalDivider(color = clarity.hairline, modifier = Modifier.padding(horizontal = 18.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable(role = androidx.compose.ui.semantics.Role.Button) { onOpen(name) }
                    .padding(start = 18.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (name == "Archive") clarity.muted else clarity.text,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (count == 0) "—" else "$count",
                    style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                    color = clarity.muted,
                )
                androidx.compose.material3.Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = clarity.controlOutline,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}

/**
 * A note in a list: its words on at most two lines, and one quiet line under it with its grade and how often it
 * was seen. The whole row opens the proof sheet.
 */
@Composable
internal fun NoteLine(item: Item, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    Row(
        modifier
            .padding(horizontal = 20.dp, vertical = 5.dp)
            .fillMaxWidth()
            .background(clarity.surface, RoundedCornerShape(16.dp))
            .border(1.dp, clarity.hairline, RoundedCornerShape(16.dp))
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onOpen)
            .padding(start = 16.dp, end = 10.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item.label?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = clarity.muted) }
            Text(
                item.text,
                style = MaterialTheme.typography.bodyLarge,
                color = clarity.text,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            NoteSeenLine(item.confidence, item.seen)
        }
        androidx.compose.material3.Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = "See the proof",
            tint = clarity.controlOutline,
        )
    }
}

/** "✓ Seen 9 times · Oct to Nov" for a confirmed note; "Likely · seen 3 times" otherwise. */
@Composable
internal fun NoteSeenLine(confidence: String?, seen: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (confidence == "confirmed") {
            androidx.compose.material3.Icon(
                Icons.Outlined.Check,
                contentDescription = "Confirmed by you",
                tint = clarity.green,
                modifier = Modifier.size(15.dp),
            )
        }
        val grade = when (confidence) {
            "likely" -> "Likely"
            "guess", null -> "Guess"
            else -> null
        }
        val text = listOfNotNull(grade, seen?.let { if (grade == null) it.replaceFirstChar { c -> c.uppercase() } else it }).joinToString(" · ")
        Text(text, style = MaterialTheme.typography.bodySmall, color = clarity.muted, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

/** The proof sheet for one note, found by id among the sections and the archive. */
@Composable
private fun NoteProof(id: Long, state: KnowViewModel.State, model: KnowViewModel, onClose: () -> Unit) {
    val ref = Ref.Note(id)
    val found = state.sections.firstNotNullOfOrNull { s -> s.items.firstOrNull { it.ref == ref }?.let { s.name to it } }
        ?: state.archive.firstOrNull { it.ref == ref }?.let { "Archive" to it }
    // Deleted, or forgotten with its conversation: the sheet closes by itself.
    androidx.compose.runtime.LaunchedEffect(found == null, state.loaded) { if (found == null && state.loaded) onClose() }
    val (sectionName, item) = found ?: return
    ProofSheet(
        item = item,
        kind = item.label ?: sectionName,
        onDismiss = onClose,
        onConfirm = { model.confirm(ref) },
        onEdit = { text -> model.edit(ref, text) },
        onDelete = {
            model.delete(ref)
            onClose()
        },
    )
}

@Composable
private fun SectionTitle(name: String) {
    Text(
        name,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        color = clarity.text,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
    )
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
                // Wraps on a narrow phone rather than squeezing a button's words onto two lines.
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
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
internal fun GradeTag(text: String, confidence: String?) {
    val shape = RoundedCornerShape(999.dp)
    val (fill, fg, outline) = when (confidence) {
        "confirmed" -> Triple(clarity.greenTint, clarity.green, clarity.greenTint)
        "likely" -> Triple(clarity.yellowTint, clarity.yellow, clarity.yellowEdge)
        else -> Triple(Color.Transparent, clarity.muted, clarity.controlOutline)
    }
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = fg,
        modifier = Modifier
            .background(fill, shape)
            .border(1.dp, outline, shape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
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

/**
 * A Focus (the owner's approved mockup of 13 Oct 2026): what it is and why, where it stands in the coach's
 * words, the steps as a ladder (answered, this one, and the conversations about it), and Talk about this,
 * which opens a new conversation inside the focus. Opened from What I know, Focuses.
 */
@Composable
private fun FocusPage(d: com.clarity.life.memory.MemoryRepository.FocusDetail, onBack: () -> Unit, onTalk: () -> Unit) {
    val f = d.focus
    val zone = java.time.ZoneId.systemDefault()
    fun day(ms: Long) = com.clarity.life.core.TimeFacts.shortDay(java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDate())
    val weeks = java.time.temporal.ChronoUnit.WEEKS.between(
        java.time.Instant.ofEpochMilli(f.createdAt).atZone(zone).toLocalDate(),
        java.time.LocalDate.now(zone),
    ) + 1
    Column(
        Modifier
            .fillMaxSize()
            .background(clarity.ground)
            .verticalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TextAction("‹ What I know", modifier = Modifier.padding(top = 12.dp), onClick = onBack)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            com.clarity.life.ui.theme.Pill(
                when (f.status) {
                    "active" -> "Focus · week $weeks"
                    "paused" -> "Paused"
                    else -> "Done"
                },
            )
            com.clarity.life.ui.theme.Headline(f.name)
            if (f.why.isNotBlank()) Quiet("Why: ${f.why}")
        }
        if (f.summary.isNotBlank()) {
            BoldCard(gap = 8) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.clarity.life.ui.theme.CoachDot()
                    Quiet("Where we are")
                }
                Text(f.summary, style = MaterialTheme.typography.headlineSmall, fontSize = 21.sp, lineHeight = 29.sp, color = clarity.text)
            }
        }
        if (d.current != null || d.last.isNotEmpty()) {
            BoldCard(gap = 14) {
                Text("Steps", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = clarity.text)
                d.last.reversed().forEach { p -> StepRow(p.text, outcome(p.status) + (p.reason?.let { ", $it" } ?: "") + " · " + day(p.resolvedAt ?: p.dueAt), done = true) }
                d.current?.let { p -> StepRow(p.text, "This one · due ${p.dueText}", done = false) }
                Quiet("Each step is a promise you set in this focus. The next one is chosen from how the last one went.")
            }
        }
        if (d.conversations.isNotEmpty()) {
            Column {
                Text("Talks about it", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = clarity.text)
                d.conversations.take(5).forEachIndexed { i, c ->
                    if (i > 0) HorizontalDivider(color = clarity.hairline)
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(c.title.ifBlank { "A conversation" }, style = MaterialTheme.typography.bodyLarge, color = clarity.text, modifier = Modifier.weight(1f))
                        Quiet(day(c.startedAt))
                    }
                }
            }
        }
        if (f.status == "active") PrimaryButton("Talk about this", onClick = onTalk)
        Spacer(Modifier.height(24.dp))
    }
}

private fun outcome(status: String) = when (status) {
    "kept" -> "Done"
    "not_done" -> "Not done"
    "moved" -> "Moved"
    "let_go" -> "Let go"
    else -> status
}

@Composable
private fun StepRow(text: String, sub: String, done: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            Modifier
                .padding(top = 3.dp)
                .size(20.dp)
                .then(
                    if (done) Modifier.background(clarity.yellow, androidx.compose.foundation.shape.CircleShape)
                    else Modifier.border(2.dp, clarity.yellow, androidx.compose.foundation.shape.CircleShape),
                ),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text, style = MaterialTheme.typography.bodyLarge, fontWeight = if (done) FontWeight.Normal else FontWeight.SemiBold, color = if (done) clarity.muted else clarity.text)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = if (done) clarity.muted else clarity.yellow)
        }
    }
}
