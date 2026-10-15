package com.clarity.life.ui.talk

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Menu
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clarity.life.ui.theme.clarity
import kotlinx.coroutines.launch

/**
 * Talk, as the 0.1.6 redesign draws it (docs/mockups/redesign/TalkHome.dc.html and TalkChat.dc.html). With no
 * conversation open it shows Talk home ([TalkHome]); in a conversation, coach replies are plain text beside the
 * coach's presence dot, his own messages sit in a white bubble, and a suggestion card sits under the reply that
 * offered it.
 *
 * Nothing on this screen writes to the database by itself: each card button calls a SaveCard command.
 */

@Composable
fun TalkScreen(
    onOpenSettings: () -> Unit,
    onOpenGuide: () -> Unit = {},
    onOpenPromises: () -> Unit = {},
    onOpenPlan: () -> Unit = onOpenPromises,
    model: TalkViewModel = hiltViewModel(),
    planner: com.clarity.life.ui.plan.PlanViewModel = hiltViewModel(),
    reviews: com.clarity.life.ui.plan.ReviewsViewModel = hiltViewModel(),
) {
    val state by model.state.collectAsStateWithLifecycle()
    // Only what the home shows (PlanViewModel.Home), not the whole tracker: the home no longer waits for it.
    val planState by planner.home.collectAsStateWithLifecycle()
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
    val userName by model.userName.collectAsStateWithLifecycle()
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
            logCheckin = model::logCheckin,
            updatePromiseStatus = model::updatePromiseStatus,
            targetName = { id -> state.targetNames[id] },
            promiseText = { id -> state.promiseTexts[id] },
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
                HomeTopBar(
                    date = if (planState.ready) planner.longDay(planState.today) else "",
                    onDrawer = { drawer = !drawer },
                    onGuide = onOpenGuide,
                    onSettings = onOpenSettings,
                )
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

            if (home && !(state.loaded && planState.ready && userName != null)) {
                // Still reading the database (a moment after opening the app): an empty space, not a home
                // drawn from empty values that then jumps to the real one.
                Spacer(Modifier.fillMaxWidth().weight(1f))
            } else if (home) {
                TalkHome(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    planDay = planState.planDay,
                    hasPlan = planState.hasPlan,
                    latest = state.conversations.firstOrNull(),
                    hasKey = state.hasKey,
                    provider = providerName,
                    userName = userName.orEmpty(),
                    onSetUp = onOpenSettings,
                    onOpenGuide = onOpenGuide,
                    focuses = state.focuses,
                    letterCard = {
                        // The letter card, only while a new weekly letter is unread (spec section 2.1). Due promises
                        // and today's steps are on Plan, with a dot on its tab (the owner's decision, 14 Oct 2026).
                        com.clarity.life.ui.plan.LetterCard(reviewState, reviews, onRead = onOpenPlan)
                    },
                    onStart = { type, focusId -> follow = true; model.startFromHome(type, focusId) },
                    onOpen = { id -> follow = true; model.open(id) },
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
/**
 * Talk home's top bar: Conversations on the left, the guide and Settings on the right, and the date centred on
 * the screen, not between one icon on the left and two on the right. Kept out of [TalkScreen] on purpose: that
 * function is already very large, and growing it made Android's verifier reject the compiled screen.
 */
@Composable
private fun HomeTopBar(date: String, onDrawer: () -> Unit, onGuide: () -> Unit, onSettings: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
        Text(
            date,
            style = MaterialTheme.typography.bodyMedium,
            color = clarity.muted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 104.dp),
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDrawer) {
                Icon(Icons.Outlined.Menu, contentDescription = "Conversations", tint = clarity.text)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onGuide) {
                Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = "How Clarity works", tint = clarity.text)
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Outlined.Settings, contentDescription = "Settings", tint = clarity.text)
            }
        }
    }
}
