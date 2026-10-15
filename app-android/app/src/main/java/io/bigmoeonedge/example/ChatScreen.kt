package io.bigmoeonedge.example

import android.content.Intent
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.androidlm.research.PlacesText
import org.androidlm.research.Translation
import org.androidlm.research.android.AndroidLocator
import org.androidlm.research.android.CorpusFiles
import java.io.File

/** Questions a new chat offers, one per kind of thing the app knows well. */
private val SUGGESTIONS = listOf(
    "Best vegan restaurants in Lisbon",
    "What is EIP-4844 and what are blobs in Ethereum?",
    "I just got bitten by a snake while hiking, two hours from the nearest road. What do I do right now?",
    "How do I get from Lisbon airport to the city center?",
)

/**
 * AndroidLM's main screen, laid out like a chat app: a menu of saved chats on the left, the
 * conversation in the middle (questions as bubbles, answers as messages), and the message box at
 * the bottom, whose button sends or, while an answer is being written, stops it. A new chat shows
 * what the app can answer and a few questions to start with.
 */
@Composable
fun ChatScreen(
    settings: AppSettings,
    corpus: CorpusFiles?,
    models: List<File>,
    scanning: Boolean,
    modelIdx: Int,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenModel: () -> Unit,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val ui by RunBus.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }

    var prompt by rememberSaveable { mutableStateOf("") }
    val research = ui.research
    val history = ui.researchHistory
    // Research mode is in effect when it is switched on AND there is a corpus to search.
    val researchOn = settings.researchMode && corpus != null
    val model = models.getOrNull(modelIdx.coerceIn(0, (models.size - 1).coerceAtLeast(0)))
    // a chat may change while no answer is being written (the next question would land in it)
    val canSwitch = !ui.generating && research?.running != true

    // A "near me" question needs the phone's position: location access is asked for then, and the
    // question runs whatever the answer (without it, the answer says to name the city).
    var awaitingLocation by remember { mutableStateOf<String?>(null) }
    val locationAsk = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val q = awaitingLocation
        awaitingLocation = null
        if (q != null && model != null) launchResearch(context, model, q, settings, RunBus.state.value.sessionSig)
    }

    fun send(text: String) {
        val q = text.trim()
        if (q.isEmpty() || model == null) return
        // Drop focus so the soft keyboard retracts: the answer streams into the space it covered.
        focusManager.clearFocus()
        if (researchOn) {
            val here = PlacesText.parse(q, emptyList())?.here == true || Translation.mentionsHere(q)
            if (here && !AndroidLocator.permitted(context)) {
                awaitingLocation = q
                locationAsk.launch(AndroidLocator.PERMISSIONS)
            } else {
                launchResearch(context, model, q, settings, ui.sessionSig)
            }
        } else {
            // First message of a conversation clears the KV; a follow-up continues it.
            launchPrompt(context, model, q, settings, ui.sessionSig, clearKv = ui.transcript.isEmpty())
        }
        prompt = ""
    }

    fun newChat() {
        RunBus.update {
            it.copy(chatId = null, research = null, researchHistory = emptyList(), transcript = emptyList(),
                answer = "", reasoning = "", summary = "", error = null)
        }
        prompt = ""
    }

    // the saved chats, read when the menu opens and after a change
    var chats by remember { mutableStateOf<List<ChatMeta>>(emptyList()) }
    var chatsKey by remember { mutableStateOf(0) }
    LaunchedEffect(drawerState.isOpen, chatsKey) {
        if (drawerState.isOpen) chats = withContext(Dispatchers.IO) { ChatStore.list(context) }
    }
    var deleting by remember { mutableStateOf<ChatMeta?>(null) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(Modifier.width(310.dp)) {
                ChatDrawer(
                    chats = chats,
                    currentId = ui.chatId,
                    canSwitch = canSwitch,
                    onNewChat = { newChat(); scope.launch { drawerState.close() } },
                    onOpen = { c ->
                        scope.launch {
                            val turns = withContext(Dispatchers.IO) { ChatStore.load(context, c.id) }
                            if (turns != null && canSwitch) {
                                RunBus.update {
                                    it.copy(chatId = c.id, research = null, researchHistory = turns, transcript = emptyList(),
                                        answer = "", reasoning = "", summary = "", error = null)
                                }
                            }
                            drawerState.close()
                        }
                    },
                    onDelete = { deleting = it },
                    onOpenModel = { scope.launch { drawerState.close() }; onOpenModel() },
                    onOpenSettings = { scope.launch { drawerState.close() }; onOpenSettings() },
                )
            }
        },
    ) {
        // adjustResize handles the legacy path; imePadding covers edge-to-edge, where the window no
        // longer shrinks for the keyboard. Without it the message box draws behind the IME.
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
            ChatTopBar(
                title = if (history.isNotEmpty() || research != null) ChatStore.titleOf(history + listOfNotNull(research))
                else if (ui.transcript.isNotEmpty()) "Chat without sources" else "AndroidLM",
                subtitle = when {
                    scanning -> "Looking for the model…"
                    models.isEmpty() -> "No model yet: see Set up"
                    ui.loading -> "Loading the model…"
                    corpus == null && settings.researchMode -> "No offline library yet: see Set up"
                    !settings.researchMode -> "Offline · no sources (Research is off)"
                    else -> "Offline · " + friendlyModelName(model?.name ?: "")
                },
                canNew = canSwitch && (history.isNotEmpty() || research != null || ui.transcript.isNotEmpty()),
                onMenu = { focusManager.clearFocus(); scope.launch { drawerState.open() } },
                onNewChat = { newChat() },
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                ChatList(
                    ui = ui,
                    researchOn = researchOn,
                    scanning = scanning,
                    hasModel = model != null,
                    onRefresh = onRefresh,
                    onOpenModel = onOpenModel,
                    onSuggestion = { send(it) },
                )
            }
            Composer(
                prompt = prompt,
                onPrompt = { prompt = it },
                placeholder = if (researchOn) "Ask anything" else "Message (no sources)",
                // (a question asked while only the last answer's source check runs stops the check;
                // one asked while the model loads runs when it is ready)
                canSend = model != null && prompt.isNotBlank() &&
                    (if (researchOn) !ui.busy || research?.checking == true || ui.state == EngineState.LOADING else !ui.busy),
                showStop = ui.generating && !(research?.checking == true && prompt.isNotBlank()),
                onSend = { send(prompt) },
                onStop = { context.startService(Intent(context, RunService::class.java).setAction(RunService.ACTION_CANCEL)) },
            )
        }
    }

    deleting?.let { c ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this chat?") },
            text = { Text("\"${c.title}\" will be deleted from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    ChatStore.delete(context, c.id)
                    if (c.id == RunBus.state.value.chatId && canSwitch) newChat()
                    chats = chats.filter { it.id != c.id }
                    deleting = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ChatTopBar(title: String, subtitle: String, canNew: Boolean, onMenu: () -> Unit, onNewChat: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onMenu) { Icon(Icons.Filled.Menu, contentDescription = "Chats and settings") }
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onNewChat, enabled = canNew) { Icon(Icons.Filled.Add, contentDescription = "New chat") }
        }
    }
}

@Composable
private fun ChatDrawer(
    chats: List<ChatMeta>,
    currentId: String?,
    canSwitch: Boolean,
    onNewChat: () -> Unit,
    onOpen: (ChatMeta) -> Unit,
    onDelete: (ChatMeta) -> Unit,
    onOpenModel: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(Modifier.fillMaxHeight().statusBarsPadding().padding(vertical = 8.dp)) {
        Text("AndroidLM", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
        NavigationDrawerItem(
            label = { Text("New chat") },
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            selected = false,
            onClick = { if (canSwitch) onNewChat() },
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Text("Chats", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 28.dp, top = 12.dp, bottom = 4.dp))
        if (!canSwitch) {
            Text("Other chats open once this answer is done.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp))
        }
        LazyColumn(Modifier.weight(1f)) {
            if (chats.isEmpty()) {
                item {
                    Text("Your chats are saved on this phone and listed here.", fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp))
                }
            }
            items(chats, key = { it.id }) { c ->
                val selected = c.id == currentId
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                        .background(
                            if (selected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent,
                            RoundedCornerShape(24.dp),
                        )
                        .clickable(enabled = canSwitch && !selected) { onOpen(c) }
                        .padding(start = 16.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(c.title.ifBlank { "Untitled" }, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val now = System.currentTimeMillis()
                        Text(
                            if (now - c.updatedMs < DateUtils.MINUTE_IN_MILLIS) "Just now"
                            else DateUtils.getRelativeTimeSpanString(c.updatedMs, now, DateUtils.MINUTE_IN_MILLIS).toString(),
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // (not the chat whose answer is being written: the run would save it again)
                    IconButton(onClick = { onDelete(c) }, enabled = canSwitch || !selected) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete chat", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        NavigationDrawerItem(
            label = { Text("Model and files") },
            icon = { Icon(Icons.Filled.Info, contentDescription = null) },
            selected = false,
            onClick = onOpenModel,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        NavigationDrawerItem(
            label = { Text("Settings") },
            icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
            selected = false,
            onClick = onOpenSettings,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}

/**
 * The conversation: every turn of the chat in full, oldest first, and the one being answered last;
 * in a chat without sources, its turns. A new chat shows the welcome instead.
 */
@Composable
private fun ChatList(
    ui: UiState,
    researchOn: Boolean,
    scanning: Boolean,
    hasModel: Boolean,
    onRefresh: () -> Unit,
    onOpenModel: () -> Unit,
    onSuggestion: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    val research = ui.research
    val history = ui.researchHistory
    val liveShown = ui.answer.isNotEmpty() || ui.reasoning.isNotEmpty()
    val errorShown = ui.error != null && research == null
    val empty = history.isEmpty() && research == null && ui.transcript.isEmpty() && !liveShown && !errorShown
    // research turns are two items each (question, answer); chat turns one each
    val total = if (empty) 1 else 2 * history.size + (if (research != null) 2 else 0) + ui.transcript.size +
        (if (liveShown) 1 else 0) + (if (errorShown) 1 else 0)

    // Follow the tail only while the user is parked at the bottom. A long answer streams for a
    // long time, and scrolling back to re-read it must not fight a per-token scroll command:
    // dragging the list detaches the follow, coming back to the bottom re-arms it.
    var followTail by remember { mutableStateOf(true) }
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || (last.index == info.totalItemsCount - 1 && last.offset + last.size <= info.viewportEndOffset)
        }
    }
    var userScroll by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect {
            if (it is DragInteraction.Start) { followTail = false; userScroll = true }
        }
    }
    // Re-arm on settle, not the moment the bottom is touched: while an answer streams the bottom
    // keeps moving away, so only where a scroll actually comes to rest says what the user wants.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling && userScroll) { followTail = atBottom; userScroll = false }
        }
    }
    // A new question, or another chat opened, is where the user wants to look: follow from the end.
    LaunchedEffect(research?.question, research?.runId, ui.transcript.size, ui.chatId, history.size) {
        followTail = true
    }
    LaunchedEffect(total, ui.answer.length, ui.reasoning.length, followTail,
        research?.answer?.length, research?.check?.length, research?.phase, research?.sources?.size,
        ui.prefill?.total, ui.telemetry.step > 0) {
        // A long answer is taller than the viewport, so aligning the item's top would park the view
        // on its beginning; the large offset pins the list to the newest text instead.
        if (followTail && !empty) runCatching { listState.scrollToItem(total - 1, Int.MAX_VALUE) }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (empty) {
            item(key = "welcome") { Welcome(researchOn, scanning, hasModel, onRefresh, onOpenModel, onSuggestion) }
        }
        history.forEachIndexed { i, r ->
            val key = "${ui.chatId}-$i"
            item(key = "q-$key") { UserBubble(r.question) }
            item(key = "a-$key") {
                AssistantTurn(r, key, loading = false, prefill = null, telemetry = ui.telemetry, generating = false)
            }
        }
        if (research != null) {
            val key = "${ui.chatId}-${history.size}"
            item(key = "q-live") { UserBubble(research.question) }
            item(key = "a-live") {
                AssistantTurn(research, key, loading = ui.loading, prefill = ui.prefill, telemetry = ui.telemetry,
                    generating = ui.generating)
            }
        }
        // a chat without sources (Research off)
        items(ui.transcript.size) { i -> TurnView(ui.transcript[i]) }
        if (liveShown) {
            item(key = "live") { TurnView(ChatTurn("assistant", ui.answer, reasoning = ui.reasoning), reasoningExpanded = true) }
        }
        if (errorShown) {
            item(key = "error") {
                Text(ui.error ?: "", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
            }
        }
    }

    // Once the follow is detached, the way back to a still-growing answer is a long drag.
    if (!followTail && !atBottom && !empty) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            val scope = rememberCoroutineScope()
            SmallFloatingActionButton(
                onClick = { scope.launch { listState.animateScrollToItem(total - 1, Int.MAX_VALUE) } },
                modifier = Modifier.padding(bottom = 12.dp),
            ) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Jump to latest") }
        }
    }
}

/** A new chat: what the app answers from, the setup while files are missing, and questions to start with. */
@Composable
private fun Welcome(
    researchOn: Boolean,
    scanning: Boolean,
    hasModel: Boolean,
    onRefresh: () -> Unit,
    onOpenModel: () -> Unit,
    onSuggestion: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("What would you like to know?", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Text(
            if (researchOn || !hasModel) "Answers come from a 35B model and an offline library on this phone: all of English " +
                "Wikipedia, travel guides, 25 million places, and Ethereum's and NIST's specifications. Nothing leaves the phone."
            else "Research is off: the model answers from memory alone, with no sources, and this chat is not saved. " +
                "It can be switched on in Model and files.",
            fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // the files the app reads, while one it needs is missing
        SetupCard(scanning = scanning, onImported = onRefresh, essentialOnly = true)
        if (!scanning && !hasModel) {
            OutlinedButton(onClick = onOpenModel) { Text("Model and files") }
        }
        if (researchOn && hasModel) {
            Text("Try", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp))
            SUGGESTIONS.forEach { q ->
                OutlinedCard(onClick = { onSuggestion(q) }, modifier = Modifier.fillMaxWidth()) {
                    Text(q, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp))
                }
            }
            Text(
                "An answer takes about 2 minutes, longer when the phone is warm. Follow-up questions " +
                    "(\"What about Porto?\") are read with the previous answer.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The message box at the bottom: the text field and a round button that sends, or stops the answer. */
@Composable
private fun Composer(
    prompt: String,
    onPrompt: (String) -> Unit,
    placeholder: String,
    canSend: Boolean,
    showStop: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = prompt,
                onValueChange = onPrompt,
                placeholder = { Text(placeholder) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(24.dp),
                maxLines = 6,
            )
            if (showStop) {
                FilledIconButton(onClick = onStop, modifier = Modifier.padding(bottom = 4.dp).size(48.dp)) {
                    // a stop square
                    Box(Modifier.size(14.dp).background(MaterialTheme.colorScheme.onPrimary, RoundedCornerShape(2.dp)))
                }
            } else {
                FilledIconButton(onClick = onSend, enabled = canSend, modifier = Modifier.padding(bottom = 4.dp).size(48.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}
