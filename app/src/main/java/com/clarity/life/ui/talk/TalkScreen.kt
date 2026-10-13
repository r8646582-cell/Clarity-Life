package com.clarity.life.ui.talk

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.clarity.life.data.entity.CardRow
import com.clarity.life.data.entity.MessageRow
import com.clarity.life.ui.theme.clarity

/**
 * Talk, as the 0.1.6 redesign draws it (docs/mockups/redesign/TalkHome.dc.html and TalkChat.dc.html). With no
 * conversation open it shows Talk home ([TalkHome]); in a conversation, coach replies are plain text beside the
 * coach's presence dot, his own messages sit in a white bubble, and a suggestion card sits under the reply that
 * offered it.
 *
 * Nothing on this screen writes to the database by itself: each card button calls a SaveCard command.
 */
private val CHAT_TYPES = listOf(
    "free" to "Free talk",
    "untangle" to "Untangle",
    "decide" to "Decide",
    "plan" to "Plan and track",
)

@Composable
fun TalkScreen(
    onOpenSettings: () -> Unit,
    onOpenGuide: () -> Unit = {},
    onOpenPromises: () -> Unit = {},
    onOpenPlan: () -> Unit = onOpenPromises,
    model: TalkViewModel = hiltViewModel(),
    promises: com.clarity.life.ui.plan.PromisesViewModel = hiltViewModel(),
    planner: com.clarity.life.ui.plan.PlanViewModel = hiltViewModel(),
    reviews: com.clarity.life.ui.plan.ReviewsViewModel = hiltViewModel(),
) {
    val state by model.state.collectAsStateWithLifecycle()
    val promiseState by promises.state.collectAsStateWithLifecycle()
    val planState by planner.state.collectAsStateWithLifecycle()
    val reviewState by reviews.state.collectAsStateWithLifecycle()
    // "Plan with coach" and "Talk about this letter" ask for a new conversation of a given chat type. Only
    // the Talk screen that is on show takes the request, so the conversation opens here, already switched.
    val doorway by model.doorway.collectAsStateWithLifecycle()
    LaunchedEffect(doorway) {
        if (doorway != null) model.takeDoorway()
    }
    // Kept through a turn of the phone and a trip to another tab.
    var draft by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    // A message typed for him by another screen ("Add the ramp"): into the box, never sent by itself.
    val typedForHim by model.draft.collectAsStateWithLifecycle()
    LaunchedEffect(typedForHim) {
        model.takeDraft()?.let { draft = it }
    }
    val home = state.conversationId == 0L
    val providerName by model.provider.collectAsStateWithLifecycle()
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        model.refreshKey()
        onPauseOrDispose { }
    }
    var typeMenu by remember { mutableStateOf(false) }
    var drawer by remember { mutableStateOf(false) }
    var forgetting by remember { mutableStateOf<Long?>(null) }
    val listState = rememberLazyListState()
    // Whether the list follows new text to the bottom (see below).
    var follow by remember { mutableStateOf(true) }
    // A reminder needs permission to show a notification (Android 13 and later); it is asked for the first
    // time he sets a promise with Reminder on, never before.
    val askNotifications = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { }
    val actions = remember(model) {
        CardActions(
            fieldsOf = model::cardFields,
            promiseTime = model::promiseTime,
            show = model::cardTime,
            isAhead = model::isAhead,
            today = model::today,
            setPromise = { id, date, time, remind ->
                model.setPromise(id, date, time)
                if (remind && android.os.Build.VERSION.SDK_INT >= 33 && !model.notificationsAllowed()) {
                    askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            keepNote = model::keepNote,
            startFocus = model::startFocus,
            switchMode = model::switchFromCard,
            notNow = model::dismissCard,
            openPromises = { reviews.showSection("Promises"); onOpenPromises() },
            savePlan = model::savePlanCard,
            openPlan = { reviews.showSection("Goals"); onOpenPlan() },
            focusName = model::focusName,
        )
    }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    // Send closes the keyboard, so the reply has the whole screen.
    val sendDraft = {
        follow = true
        // A refused send (a reply is still being written) keeps what he typed.
        if (model.send(draft)) draft = ""
        keyboard?.hide()
        focus.clearFocus()
    }

    // Follow the newest text, unless he has scrolled up to read: a drag up stops following, and reaching the
    // bottom again (or sending) starts it again. Following is an instant scroll on each frame the text grows,
    // never an animation restarted for every piece of text, which is what made streaming judder.
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect {
            when (it) {
                is androidx.compose.foundation.interaction.DragInteraction.Start -> follow = false
                is androidx.compose.foundation.interaction.DragInteraction.Stop,
                is androidx.compose.foundation.interaction.DragInteraction.Cancel,
                -> if (!listState.canScrollForward) follow = true
                else -> Unit
            }
        }
    }
    LaunchedEffect(listState) {
        androidx.compose.runtime.snapshotFlow { listState.canScrollForward }.collect { more -> if (!more) follow = true }
    }
    // A long reply is followed only until its first line reaches the top of the screen. After that the
    // list stays still, so the start of the reply is never pushed out of sight while the rest is written.
    val toBottom: (Long) -> Unit = { replyId ->
        if (follow) scope.launch {
            val info = listState.layoutInfo
            val reply = info.visibleItemsInfo.firstOrNull { it.key == replyId }
            if (reply == null) {
                listState.scrollBy(100_000f)
            } else {
                val room = reply.offset - info.viewportStartOffset
                if (room > 0) listState.scrollBy(room.toFloat())
            }
        }
    }
    LaunchedEffect(state.messages.size) {
        val last = state.messages.size
        if (last > 0 && follow) listState.scrollToItem(last)
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(clarity.ground).imePadding()) {
            if (home) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { drawer = !drawer }) {
                        Icon(Icons.Outlined.Menu, contentDescription = "Conversations", tint = clarity.text)
                    }
                    Text(
                        if (planState.ready) planner.longDay(planState.today) else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = clarity.muted,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onOpenGuide) {
                        Icon(
                            Icons.AutoMirrored.Outlined.HelpOutline,
                            contentDescription = "How Clarity works",
                            tint = clarity.text,
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings", tint = clarity.text)
                    }
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { drawer = false; model.home() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back to Talk home", tint = clarity.text)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            state.conversations.firstOrNull { it.id == state.conversationId }?.title?.takeIf { it.isNotBlank() }
                                ?: "New conversation",
                            style = MaterialTheme.typography.headlineSmall,
                            color = clarity.text,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        Box {
                            Row(
                                Modifier.clickable { typeMenu = true }.padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    state.focuses.firstOrNull { it.id == state.focusId }?.name
                                        ?: CHAT_TYPES.firstOrNull { it.first == state.chatType }?.second ?: "Free talk",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = clarity.muted,
                                )
                                Icon(Icons.Outlined.ExpandMore, contentDescription = null, tint = clarity.muted)
                            }
                            DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                                CHAT_TYPES.forEach { (id, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = {
                                            typeMenu = false
                                            model.switchChatType(id)
                                        },
                                    )
                                }
                                // One entry per active focus (spec section 2.1); the conversation joins it.
                                state.focuses.forEach { f ->
                                    DropdownMenuItem(
                                        text = { Text(f.name) },
                                        onClick = {
                                            typeMenu = false
                                            model.switchToFocus(f.id)
                                        },
                                    )
                                }
                            }
                        }
                    }
                    IconButton(onClick = { drawer = !drawer }) {
                        Icon(Icons.Outlined.Menu, contentDescription = "Conversations", tint = clarity.text)
                    }
                }
                androidx.compose.material3.HorizontalDivider(color = clarity.hairline)
            }


            // On Talk home the quiet line sits at the top. In a conversation it goes under the last reply instead
            // (below), so it is read with the reply it is about and never covers the words as they scroll.
            if (home) state.quietLine?.let { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = clarity.danger,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                )
            }
            if (!home) {
                val line = state.quietLine
                LaunchedEffect(line) {
                    // Bring a new line into view: it is the item right after the last message.
                    if (line != null) listState.animateScrollToItem(state.messages.size)
                }
            }

            if (home) {
                TalkHome(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    plan = planState,
                    planModel = planner,
                    promises = promiseState,
                    latest = state.conversations.firstOrNull(),
                    hasKey = state.hasKey,
                    provider = providerName,
                    onSetUp = onOpenSettings,
                    onOpenGuide = onOpenGuide,
                    focuses = state.focuses,
                    letterCard = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            // The letter card, only while a new weekly letter is unread (spec section 2.1).
                            com.clarity.life.ui.plan.LetterCard(reviewState, reviews, onRead = onOpenPlan)
                            // Promises due now, written by the app, never the coach (spec section 2.1).
                            com.clarity.life.ui.plan.DueCard(promiseState, promises, onSeeAll = { reviews.showSection("Promises"); onOpenPromises() })
                        }
                    },
                    onStart = { type, focusId -> follow = true; model.startFromHome(type, focusId) },
                    onOpen = { id -> follow = true; model.open(id) },
                    // Part and Not are answered on Plan, Today, where the amount and the reasons are.
                    onOpenToday = { reviews.showSection("Today"); onOpenPromises() },
                    onOpenPromises = { reviews.showSection("Promises"); onOpenPromises() },
                )
            } else LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(state.messages, key = { it.id }) { row ->
                    MessageBubble(
                        row = row,
                        live = model.live,
                        isLive = state.live?.messageId == row.id,
                        onGrow = { toBottom(row.id) },
                        cards = state.cards[row.id].orEmpty(),
                        receipts = state.receipts,
                        cardLines = state.cardLines,
                        previews = state.previews,
                        actions = actions,
                        onTryAgain = { model.tryAgain(row.id) },
                    )
                }
                state.quietLine?.let { line ->
                    item(key = "quiet-line") {
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall,
                            color = clarity.danger,
                            modifier = Modifier.padding(start = 4.dp, end = 4.dp),
                        )
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }

            state.live?.note?.let { note ->
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = clarity.muted,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(if (home) "Talk to your coach" else "Message", color = clarity.muted) },
                    shape = RoundedCornerShape(27.dp),
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = clarity.text,
                        unfocusedTextColor = clarity.text,
                        focusedContainerColor = clarity.surface,
                        unfocusedContainerColor = clarity.surface,
                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        cursorColor = clarity.yellow,
                    ),
                    keyboardActions = KeyboardActions(onSend = { sendDraft() }),
                    maxLines = 6,
                )
                Box(
                    Modifier
                        .size(52.dp)
                        .background(clarity.yellow, androidx.compose.foundation.shape.CircleShape)
                        .clickable(role = androidx.compose.ui.semantics.Role.Button) { sendDraft() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.ArrowUpward, contentDescription = "Send", tint = clarity.onYellow)
                }
            }
        }

        // The conversations drawer (docs/mockups/Drawer.dc.html): a panel from the left over a dimmed screen.
        // A tap on the dimmed part, Back, or the close button puts it away.
        androidx.compose.animation.AnimatedVisibility(
            visible = drawer,
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut(),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(androidx.compose.ui.graphics.Color(0xB808070C))
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null,
                        onClickLabel = "Close conversations",
                    ) { drawer = false },
            )
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = drawer,
            enter = androidx.compose.animation.slideInHorizontally { -it },
            exit = androidx.compose.animation.slideOutHorizontally { -it },
        ) {
            ConversationsDrawer(
                state = state,
                onOpen = { id -> model.open(id); drawer = false },
                onNew = { model.newConversation("free"); drawer = false },
                onClose = { drawer = false },
                onForget = { id -> forgetting = id },
                onRename = model::rename,
                onSearch = model::searchConversations,
                dayOf = model::drawerDay,
            )
        }
        androidx.activity.compose.BackHandler(enabled = drawer) { drawer = false }
    }

    // Forget is final (spec section 3), so it asks first.
    forgetting?.let { id ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { forgetting = null },
            containerColor = clarity.surface,
            title = { Text("Forget this conversation?", color = clarity.text) },
            text = {
                Text(
                    "It is removed for good, with everything learned only from it. Notes you edited or confirmed stay.",
                    color = clarity.muted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    model.forget(id)
                    forgetting = null
                }) { Text("Forget", color = clarity.danger) }
            },
            dismissButton = { TextButton(onClick = { forgetting = null }) { Text("Keep it", color = clarity.text) } },
        )
    }
}

/**
 * The conversations drawer (spec section 2.1, docs/mockups/Drawer.dc.html): a panel from the left with New,
 * a search box (titles and summaries), every conversation newest first under yellow day headings, the open
 * one lit, and a ⋯ button on each row for Rename and Forget.
 */
@Composable
private fun ConversationsDrawer(
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
    var renaming by remember { mutableStateOf<com.clarity.life.data.entity.ConversationRow?>(null) }
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
                        onRename = { renaming = row },
                        onForget = { onForget(row.id) },
                    )
                }
            }
        }
    }

    renaming?.let { row ->
        var title by remember(row.id) { mutableStateOf(row.title) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { renaming = null },
            containerColor = clarity.surface,
            title = { Text("Rename", color = clarity.text) },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(TalkViewModel.MAX_TITLE_CHARS) },
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
                    onRename(row.id, title)
                    renaming = null
                }, enabled = title.isNotBlank()) { Text("Save", color = clarity.yellow) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel", color = clarity.text) } },
        )
    }
}

/** One conversation in the drawer: its title, its kind and start time, and a ⋯ menu for Rename and Forget. */
@Composable
private fun DrawerRow(
    row: com.clarity.life.data.entity.ConversationRow,
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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    row: MessageRow,
    live: kotlinx.coroutines.flow.StateFlow<com.clarity.life.chat.ReplyBus.Live?>,
    isLive: Boolean,
    onGrow: () -> Unit,
    cards: List<CardRow>,
    receipts: Map<Long, String>,
    cardLines: Map<Long, String>,
    previews: Map<Long, com.clarity.life.chat.CardSaver.Preview>,
    actions: CardActions,
    onTryAgain: () -> Unit,
) {
    val his = row.role == "user"
    // A reply that was streaming when this bubble first appeared is typed out; older ones show at once.
    val typed = remember(row.id) { row.status == "streaming" }
    var menu by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val writing = !his && (typed || isLive || row.status == "streaming")
    Column(Modifier.fillMaxWidth()) {
        if (writing || row.content.isNotEmpty()) Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = if (his) Arrangement.End else Arrangement.Start,
        ) {
            Box {
                // His messages are bubbles on the right; the coach's replies use the full width, like a page.
                // A long press opens Copy and Details.
                Column(
                    (
                        if (his) {
                            Modifier
                                .widthIn(max = 320.dp)
                                .background(clarity.text, RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
                        } else {
                            Modifier.fillMaxWidth()
                        }
                        )
                        .combinedClickable(
                            onClick = {},
                            onLongClick = {
                                haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                menu = true
                            },
                        )
                        .padding(
                            horizontal = if (his) 14.dp else 4.dp,
                            vertical = if (his) 10.dp else 6.dp,
                        ),
                ) {
                    if (his) {
                        Text(row.content, style = MaterialTheme.typography.bodyLarge, color = clarity.ground)
                    } else {
                        // The coach's presence dot beside its reply; it breathes while the reply is being written.
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            com.clarity.life.ui.theme.CoachDot(alive = writing, modifier = Modifier.padding(top = 1.dp))
                            Column(Modifier.weight(1f)) {
                                if (writing) {
                                    ReplyText(row, live, onGrow)
                                } else if (row.content.isNotEmpty()) {
                                    // The coach may mark a few words bold or write a short list; his own text is never changed.
                                    Text(
                                        remember(row.content) { styled(row.content) },
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontSize = 17.sp,
                                        lineHeight = 26.sp,
                                        color = clarity.text,
                                    )
                                }
                            }
                        }
                    }
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Copy") },
                        onClick = {
                            menu = false
                            val text = if (isLive) live.value?.text?.let(com.clarity.life.core.LiveText::visible).orEmpty() else row.content
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
                            // Android 13 and later show their own "Copied" bubble.
                            if (android.os.Build.VERSION.SDK_INT < 33) {
                                android.widget.Toast.makeText(context, "Copied", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Details") },
                        onClick = {
                            menu = false
                            details = true
                        },
                    )
                }
            }
        }
        when (row.status) {
            "interrupted" -> QuietLineWithAction("That reply was cut off.", "Try again", onTryAgain)
            "failed" -> QuietLineWithAction("That reply did not arrive.", "Try again", onTryAgain)
            else -> Unit
        }
        cards.forEach { card -> SuggestionCard(card, receipts[card.id], cardLines[card.id], previews[card.id], actions) }
    }
    if (details) MessageDetails(row, onClose = { details = false })
}

/** A coach reply with its bold words and list lines drawn; the parsing is in [com.clarity.life.core.RichText]. */
private fun styled(text: String): androidx.compose.ui.text.AnnotatedString {
    val rich = com.clarity.life.core.RichText.parse(text)
    return androidx.compose.ui.text.buildAnnotatedString {
        append(rich.text)
        rich.bold.forEach { range ->
            addStyle(
                androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                range.first,
                range.last + 1,
            )
        }
    }
}

/**
 * A reply being written, typed out at an even pace (a few characters a frame) however the network delivers
 * it. The live text is read once a frame, not collected, so a new piece of text redraws only this one Text;
 * when the stored reply takes over, the typing carries on instead of jumping, and the loop then stops.
 */
@Composable
private fun ReplyText(
    row: MessageRow,
    live: kotlinx.coroutines.flow.StateFlow<com.clarity.life.chat.ReplyBus.Live?>,
    onGrow: () -> Unit,
) {
    val rowNow by androidx.compose.runtime.rememberUpdatedState(row)
    var full by remember(row.id) { mutableStateOf("") }
    var shown by remember(row.id) { androidx.compose.runtime.mutableIntStateOf(0) }
    LaunchedEffect(row.id) {
        while (true) {
            androidx.compose.runtime.withFrameNanos { }
            val r = rowNow
            val now = live.value?.takeIf { it.messageId == r.id }
            val source = when {
                now != null -> com.clarity.life.core.LiveText.visible(now.text)
                // Between the end of the stream and the stored reply arriving, keep what is on screen.
                r.status == "streaming" -> full
                else -> r.content
            }
            if (source != full) full = source
            val step = com.clarity.life.core.LiveText.step(full.length - shown)
            if (step > 0) {
                shown += step
                onGrow()
            } else if (shown > full.length) {
                shown = full.length // the stored reply is shorter: its card lines were taken out
            }
            if (now == null && r.status != "streaming" && shown >= full.length) break
        }
    }
    if (full.isEmpty()) {
        if (row.status == "streaming") ThinkingLine()
    } else {
        Text(styled(full.take(shown)), style = MaterialTheme.typography.bodyLarge, fontSize = 17.sp, lineHeight = 26.sp, color = clarity.text)
    }
}

@Composable
private fun ThinkingLine() {
    // Three dots that fill in turn, so a long think still looks alive.
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "thinking")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(1200, easing = androidx.compose.animation.core.LinearEasing),
        ),
        label = "dots",
    )
    val dots = ".".repeat(phase.toInt() + 1)
    Text(
        "Thinking$dots",
        style = MaterialTheme.typography.bodyLarge,
        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
        color = clarity.muted,
    )
}

/** Details of one message: when it was written and, for the coach, which model and thinking level. */
@Composable
private fun MessageDetails(row: MessageRow, onClose: () -> Unit) {
    val his = row.role == "user"
    val at = java.time.Instant.ofEpochMilli(row.createdAt).atZone(java.time.ZoneId.systemDefault())
    val time = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm").format(at)
    val lines = buildList {
        add((if (his) "Sent" else "Written") to time)
        if (!his) {
            val label = row.model
            val model = label?.substringBefore(", thinking ")
            val level = label?.substringAfter(", thinking ", "")?.takeIf { it.isNotEmpty() }
            add("Model" to (model ?: "Not recorded (written before this was kept)"))
            if (level != null) add("Thinking" to level.replaceFirstChar { it.uppercase() })
            row.tokensIn?.let { add("Tokens read" to "%,d".format(it)) }
            row.tokensOut?.let { add("Tokens written" to "%,d".format(it)) }
            val status = when (row.status) {
                "complete" -> "Complete"
                "streaming" -> "Still being written"
                "interrupted" -> "Cut off"
                "failed" -> "Did not arrive"
                else -> row.status
            }
            add("Status" to status)
        }
        add("Length" to "${row.content.length} characters")
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        containerColor = clarity.surface,
        title = { Text(if (his) "Your message" else "Coach reply", color = clarity.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                lines.forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(k, style = MaterialTheme.typography.bodyMedium, color = clarity.muted)
                        Spacer(Modifier.widthIn(min = 12.dp))
                        Text(v, style = MaterialTheme.typography.bodyMedium, color = clarity.text)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close", color = clarity.yellow) } },
    )
}

@Composable
private fun QuietLineWithAction(line: String, action: String, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(line, style = MaterialTheme.typography.bodySmall, color = clarity.danger)
        TextButton(onClick = onAction) { Text(action, color = clarity.yellow) }
    }
}
