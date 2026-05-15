// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.frontend.chat

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerMoveFilter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import com.github.quanta_dance.quanta.plugins.intellij.frontend.ModularPluginFrontendBundle
import com.github.quanta_dance.quanta.plugins.intellij.frontend.chat.viewmodel.ChatViewModel
import com.github.quanta_dance.quanta.plugins.intellij.frontend.chat.viewmodel.MessageInputState
import com.github.quanta_dance.quanta.plugins.intellij.frontend.settings.FrontendQuantaSettingsState
import com.github.quanta_dance.quanta.plugins.intellij.frontend.settings.FrontendSettingsSyncStateService
import com.github.quanta_dance.quanta.plugins.intellij.frontend.ui.*
import com.github.quanta_dance.quanta.plugins.intellij.frontend.voice.FrontendAIVoiceService
import com.github.quanta_dance.quanta.plugins.intellij.frontend.voice.FrontendMicrophoneService
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ChatMessage
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.*
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.*

@Composable
fun ChatApp(project: Project, viewModel: ChatViewModel, voiceService: FrontendAIVoiceService) {
    val chatMessages by viewModel.chatMessagesFlow.collectAsState(emptyList())
    val sessions by viewModel.sessionsFlow.collectAsState(emptyList())
    val searchState by viewModel.searchChatMessagesHandler().searchStateFlow.collectAsState(SearchState.Idle)
    val messageInputState by viewModel.promptInputState.collectAsState(MessageInputState.Disabled)
    val settingsSyncService = project.service<FrontendSettingsSyncStateService>()
    val settingsSyncState by settingsSyncService.stateFlow.collectAsState()
    val scope = rememberCoroutineScope()
    var voiceEnabled by remember { mutableStateOf(FrontendQuantaSettingsState.instance.state.voiceEnabled) }
    var selectedModel by remember { mutableStateOf(FrontendQuantaSettingsState.instance.state.aiChatModel) }
    var availableModels by remember { mutableStateOf(FrontendQuantaSettingsState.instance.state.availableChatModels) }
    var agenticEnabled by remember { mutableStateOf(FrontendQuantaSettingsState.instance.state.agenticEnabled ?: true) }
    val planStatus by viewModel.planStatusFlow.collectAsState(ChatPlanStatusDto())
    val agents by viewModel.agentsFlow.collectAsState(emptyList())
    val delegatedTasks by viewModel.delegatedTasksFlow.collectAsState(emptyList())
    val channelEvents by viewModel.channelEventsFlow.collectAsState(emptyList())
    val hasRunningAgentWork = delegatedTasks.any { it.status == DelegatedTaskStatusDto.RUNNING }
    val activeSession = sessions.firstOrNull { it.isActive }
    val microphoneService = remember(project) { project.service<FrontendMicrophoneService>() }
    val micEnabled by microphoneService.isListening.collectAsState(false)
    val micActive by microphoneService.isVoiceDetected.collectAsState(false)
    val listState = rememberLazyListState()
    val textFieldState = rememberTextFieldState()
    var lastSpokenMessageId by remember { mutableStateOf<String?>(null) }

    val lastMessageScrollKey = remember(chatMessages) {
        chatMessages.lastOrNull()?.let { message ->
            listOf(
                message.id,
                message.content,
                message.type.name,
                message.toolItems.joinToString("|") { tool -> "${tool.callId}:${tool.status}:${tool.displayText}:${tool.errorText}:${tool.detailText}" },
            ).joinToString("#")
        }
    }

    LaunchedEffect(lastMessageScrollKey, chatMessages.size, searchState.isSearching) {
        if (chatMessages.isNotEmpty() && !searchState.isSearching) {
            listState.animateScrollToItem(chatMessages.lastIndex)
        }
    }

    // Auto-scroll to the current search result
    LaunchedEffect(searchState.currentSelectedSearchResultId) {
        val currentResultId = searchState.currentSelectedSearchResultId
        if (currentResultId != null) {
            val messageIndexInList = chatMessages.indexOfFirst { it.id == currentResultId }
            if (messageIndexInList >= 0) {
                listState.animateScrollToItem(messageIndexInList)
            }
        }
    }

    LaunchedEffect(activeSession?.id) {
        lastSpokenMessageId =
            chatMessages
                .asReversed()
                .firstOrNull { message -> !message.isMyMessage && message.isTextMessage() }
                ?.id
        voiceService.stopTalking()
    }

    LaunchedEffect(chatMessages.size) {
        if (lastSpokenMessageId == null) {
            lastSpokenMessageId =
                chatMessages
                    .asReversed()
                    .firstOrNull { message -> !message.isMyMessage && message.isTextMessage() }
                    ?.id
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            val settings = FrontendQuantaSettingsState.instance.state
            if (voiceEnabled != settings.voiceEnabled) {
                voiceEnabled = settings.voiceEnabled
            }
            if (selectedModel != settings.aiChatModel) {
                selectedModel = settings.aiChatModel
            }
            if (availableModels != settings.availableChatModels) {
                availableModels = settings.availableChatModels
            }
            kotlinx.coroutines.delay(300)
        }
    }

    LaunchedEffect(chatMessages, voiceEnabled) {
        if (!voiceEnabled) return@LaunchedEffect

        val candidate =
            chatMessages
                .asReversed()
                .firstOrNull { message -> !message.isMyMessage && message.isTextMessage() }
                ?: return@LaunchedEffect

        if (lastSpokenMessageId == candidate.id) return@LaunchedEffect

        val hasThinkingIndicator = chatMessages.any { it.isAIThinkingMessage() }
        delay(if (hasThinkingIndicator) 900 else 150)

        if (lastSpokenMessageId == candidate.id) return@LaunchedEffect

        val summary = candidate.voiceSummary?.trim()?.replace(Regex("\\s+"), " ").orEmpty()
        if (summary.isNotEmpty()) {
            lastSpokenMessageId = candidate.id
            voiceService.say(summary)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ChatAppColors.Panel.background),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.Start,
        content = {
            // Chat header with search button
            ChatHeaderWithSearchBar(
                searchState = searchState,
                onStartSearch = { viewModel.searchChatMessagesHandler().onStartSearch() },
                onStopSearch = { viewModel.searchChatMessagesHandler().onStopSearch() },
                onSearchQueryChange = { query -> viewModel.searchChatMessagesHandler().onSearchQuery(query) },
                onNextResult = { viewModel.searchChatMessagesHandler().onNavigateToNextSearchResult() },
                onPreviousResult = { viewModel.searchChatMessagesHandler().onNavigateToPreviousSearchResult() }
            )

            SessionTabs(
                project = project,
                sessions = sessions,
                onSessionSelected = { sessionId -> viewModel.onActivateSession(sessionId) },
                onSessionDeleted = { sessionId -> viewModel.onDeleteSession(sessionId) },
            )

            ChatList(
                project = project,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                chatMessages = chatMessages,
                listState = listState,
                searchState = searchState,
            )

            if (agenticEnabled) {
                AgentsPanel(
                    modifier = Modifier.fillMaxWidth(),
                    agents = agents,
                    onCreateDefaultTeam = { viewModel.onCreateDefaultAgentTeam() },
                )
            }

            PromptInput(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 120.dp),
                textFieldState = textFieldState,
                promptInputState = messageInputState,
                voiceEnabled = voiceEnabled,
                micEnabled = micEnabled,
                micActive = micActive,
                currentPlanStatus = planStatus.status,
                currentPlanText = planStatus.text,
                currentModel = selectedModel,
                availableModels = availableModels,
                agenticEnabled = agenticEnabled,
                onModelSelected = { model ->
                    selectedModel = model
                    FrontendQuantaSettingsState.instance.state.aiChatModel = model
                },
                onToggleMic = { microphoneService.toggleListening() },
                onToggleAgenticMode = {
                    val updated = !agenticEnabled
                    agenticEnabled = updated
                    FrontendQuantaSettingsState.instance.state.agenticEnabled = updated
                    viewModel.onSetAgenticMode(updated)
                },
                onToggleVoiceFeedback = {
                    voiceEnabled = !voiceEnabled
                    FrontendQuantaSettingsState.instance.state.voiceEnabled = voiceEnabled
                    if (!voiceEnabled) {
                        voiceService.stopTalking()
                    }
                },
                settingsSyncState = settingsSyncState,
                hasActiveAgentWork = hasRunningAgentWork,
                onInputChanged = { viewModel.onPromptInputChanged(it) },
                onSend = { viewModel.onSendMessage() },
                onStop = { viewModel.onAbortSendingMessage() },
                onStopAgents = { viewModel.onStopAllAgents() },
                onSync = { scope.launch { settingsSyncService.retryNow() } }
            )
        }
    )
}

@Composable
private fun ChannelActivityPanel(
    modifier: Modifier = Modifier,
    tasks: List<DelegatedTaskDto>,
    events: List<AgentChannelEventDto>,
) {
    if (tasks.isEmpty() && events.isEmpty()) return
    Column(
        modifier = modifier
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .background(Color.White.copy(alpha = 0.03f), RoundedCornerShape(8.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Team channel", fontWeight = FontWeight.SemiBold)
        tasks.takeLast(3).forEach { task ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(taskStatusColor(task.status), RoundedCornerShape(99.dp)),
                )
                Text(task.title, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Text(task.status.name.lowercase(), fontSize = 11.sp, color = ChatAppColors.Text.disabled)
                if (task.assignedRoles.isNotEmpty()) {
                    Text(task.assignedRoles.joinToString(", "), fontSize = 11.sp, color = ChatAppColors.Text.disabled)
                }
            }
        }
        events.takeLast(4).forEach { event ->
            if (event.kind.name == "TOOL_ACTIVITY") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(eventAuthorLabel(event), fontSize = 11.sp, color = ChatAppColors.Text.disabled)
                    Box(
                        modifier = Modifier
                            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(999.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(event.text, fontSize = 11.sp)
                    }
                }
            } else {
                Text(
                    text = "${eventAuthorLabel(event)}: ${event.text}",
                    fontSize = 11.sp,
                    color = ChatAppColors.Text.disabled,
                )
            }
        }
    }
}

private fun eventAuthorLabel(event: AgentChannelEventDto): String = when (event.authorType) {
    AgentChannelAuthorTypeDto.DIRECTOR -> "Director"
    AgentChannelAuthorTypeDto.MANAGER -> event.authorRole ?: "Manager"
    AgentChannelAuthorTypeDto.AGENT -> event.authorRole ?: "Agent"
    AgentChannelAuthorTypeDto.SYSTEM -> "System"
}

private fun taskStatusColor(status: DelegatedTaskStatusDto): Color = when (status) {
    DelegatedTaskStatusDto.QUEUED -> Color(0xFFEBCB8B)
    DelegatedTaskStatusDto.RUNNING -> Color(0xFF88C0D0)
    DelegatedTaskStatusDto.BLOCKED -> Color(0xFFD08770)
    DelegatedTaskStatusDto.DONE -> Color(0xFFA3BE8C)
    DelegatedTaskStatusDto.FAILED -> Color(0xFFBF616A)
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun AgentsPanel(
    modifier: Modifier = Modifier,
    agents: List<AgentInfoDto>,
    onCreateDefaultTeam: () -> Unit,
) {
    Column(
        modifier = modifier
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .background(ChatAppColors.Panel.background, RoundedCornerShape(8.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Agents", fontWeight = FontWeight.SemiBold)
        if (agents.isEmpty()) {
            Text(
                text = "No agents created yet.",
                fontSize = 12.sp,
            )
            Text(
                text = "Create the default team: Analitic, Tester, Developer.",
                fontSize = 12.sp,
            )
            DefaultButton(onClick = onCreateDefaultTeam) {
                Text("Create default team")
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                agents.forEach { agent ->
                    val agentColor = colorForAgent(agent.id)
                    val showProfile = remember(agent.id) { mutableStateOf(false) }
                    Box {
                        Row(
                            modifier = Modifier
                                .widthIn(min = 150.dp, max = 220.dp)
                                .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(10.dp))
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            AgentAvatar(
                                agent = agent,
                                color = agentColor,
                                onClick = { showProfile.value = true },
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(agent.role.replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.Medium)
                                Text(
                                    text = agent.model?.takeIf { it.isNotBlank() } ?: "unknown model",
                                    fontSize = 11.sp,
                                )
                                Text(
                                    text = "Profile",
                                    fontSize = 11.sp,
                                    color = Color(0xFF88C0D0),
                                    modifier = Modifier.clickable { showProfile.value = true },
                                )
                            }
                        }

                        if (showProfile.value) {
                            AgentProfileDialog(
                                agent = agent,
                                color = agentColor,
                                onDismiss = { showProfile.value = false },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentAvatar(
    agent: AgentInfoDto,
    color: Color,
    onClick: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .background(color, RoundedCornerShape(999.dp))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = agent.role.firstOrNull()?.uppercase() ?: "A",
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun AgentProfileDialog(
    agent: AgentInfoDto,
    color: Color,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .widthIn(min = 320.dp, max = 520.dp)
                .heightIn(max = 420.dp)
                .background(Color(0xFF1E1E1E), RoundedCornerShape(14.dp))
                .padding(16.dp),
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AgentAvatar(agent = agent, color = color)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            agent.role.replaceFirstChar { it.uppercase() },
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                        Text(agent.model ?: "unknown model", fontSize = 11.sp, color = Color(0xFFB0BEC5))
                    }
                }
                ProfileField("ID", agent.id)
                ProfileField("Model", agent.model ?: "unknown model")
                ProfileField("Role", agent.role)
                if (!agent.instructions.isNullOrBlank()) {
                    ProfileField("Custom instructions", agent.instructions!!)
                }
            }
        }
    }
}

@Composable
private fun ProfileField(
    label: String,
    value: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 10.sp, color = Color(0xFF88C0D0))
        Text(value, fontSize = 11.sp, color = Color.White)
    }
}

private fun colorForAgent(agentId: String): Color {
    val palette = listOf(
        Color(0xFF5E81AC),
        Color(0xFFBF616A),
        Color(0xFFA3BE8C),
        Color(0xFFD08770),
        Color(0xFFB48EAD),
        Color(0xFF88C0D0),
        Color(0xFFEBCB8B),
        Color(0xFF7B88FF),
    )
    return palette[kotlin.math.abs(agentId.hashCode()) % palette.size]
}

@Composable
private fun SessionTabs(
    project: Project,
    sessions: List<com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.ChatSessionDto>,
    onSessionSelected: (String) -> Unit,
    onSessionDeleted: (String) -> Unit,
) {
    if (sessions.isEmpty()) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        sessions.forEach { session ->
            val active = session.isActive
            Row(
                modifier = Modifier
                    .background(
                        if (active) ChatAppColors.MessageBubble.othersBackground else ChatAppColors.Panel.background,
                        RoundedCornerShape(10.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier.clickable { onSessionSelected(session.id) },
                ) {
                    Text(
                        text = session.title,
                        style = JewelTheme.defaultTextStyle.copy(
                            fontSize = 12.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                    )
                }
                Icon(
                    key = ChatAppIcons.Header.close,
                    contentDescription = "Delete session",
                    modifier = Modifier.size(12.dp).clickable {
                        val confirmed =
                            Messages.showYesNoDialog(
                                project,
                                "Delete chat '${session.title}'?",
                                "Delete Chat",
                                "Delete",
                                "Cancel",
                                Messages.getQuestionIcon(),
                            ) == Messages.YES
                        if (confirmed) {
                            onSessionDeleted(session.id)
                        }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun VisiblePlanStatus(
    status: String,
    text: String,
) {
    var hovered by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        if (hovered && text.isNotBlank()) {
            Box(
                modifier = Modifier
                    .offset(y = (-28).dp)
                    .zIndex(1f)
                    .background(ChatAppColors.Panel.background, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(text = text, style = JewelTheme.defaultTextStyle.copy(fontSize = 11.sp))
            }
        }
        Text(
            text = "Plan: $status",
            modifier = Modifier
                .background(ChatAppColors.MessageBubble.othersBackground, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .pointerMoveFilter(
                    onEnter = {
                        hovered = true
                        false
                    },
                    onExit = {
                        hovered = false
                        false
                    },
                ),
            style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
        )
    }
}

@Composable
private fun ChatList(
    project: Project,
    modifier: Modifier = Modifier,
    chatMessages: List<ChatMessage>,
    listState: LazyListState,
    searchState: SearchState,
) {
    val topLevelMessages = remember(chatMessages) { chatMessages.filter { it.parentMessageId == null } }
    val threadMessagesByParent =
        remember(chatMessages) { chatMessages.filter { it.parentMessageId != null }.groupBy { it.parentMessageId } }
    Box(modifier = modifier) {
        if (chatMessages.isEmpty()) {
            EmptyChatListPlaceholder()
        } else {
            VerticallyScrollableContainer(
                modifier = Modifier.fillMaxWidth().safeContentPadding(),
                scrollState = listState,
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(topLevelMessages, key = { it.id }) { message ->
                        Column(modifier = Modifier.fillMaxWidth()) {
                            MessageBubble(
                                project = project,
                                message = message,
                                modifier = Modifier.fillMaxWidth(),
                                isMatchingSearch = searchState.searchQuery?.let { query -> message.matches(query) }
                                    ?: false,
                                isHighlightedInSearch = message.id == searchState.currentSelectedSearchResultId,
                            )
                            val threadMessages = threadMessagesByParent[message.id].orEmpty()
                            if (threadMessages.isNotEmpty()) {
                                AgentThread(
                                    project = project,
                                    parentId = message.id,
                                    threadMessages = threadMessages,
                                    searchState = searchState,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentThread(
    project: Project,
    parentId: String,
    threadMessages: List<ChatMessage>,
    searchState: SearchState,
) {
    var expanded by remember(parentId) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 32.dp, end = 8.dp, top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "Hide agent activity (${threadMessages.size})" else "Show agent activity (${threadMessages.size})")
        }
        if (expanded) {
            threadMessages.forEach { threadMessage ->
                MessageBubble(
                    project = project,
                    message = threadMessage,
                    modifier = Modifier.fillMaxWidth(),
                    isMatchingSearch = searchState.searchQuery?.let { query -> threadMessage.matches(query) } ?: false,
                    isHighlightedInSearch = threadMessage.id == searchState.currentSelectedSearchResultId,
                )
            }
        }
    }
}

@Composable
private fun EmptyChatListPlaceholder(
    placeholderText: String = ModularPluginFrontendBundle.message("chat.start.conversation"),
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = placeholderText,
            style = JewelTheme.defaultTextStyle.copy(
                color = ChatAppColors.Text.disabled,
                fontSize = 16.sp
            )
        )
    }
}

@Composable
private fun ChatHeaderWithSearchBar(
    searchState: SearchState,
    onStartSearch: () -> Unit,
    onStopSearch: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onNextResult: () -> Unit,
    onPreviousResult: () -> Unit
) {
    val showSearchBar = searchState.isSearching

    if (showSearchBar) {
        Divider(Orientation.Horizontal, modifier = Modifier.fillMaxWidth().height(1.dp))
        ChatSearchBar(
            searchState = searchState,
            onSearchQueryChange = { query -> onSearchQueryChange(query) },
            onNextResult = { onNextResult() },
            onPreviousResult = { onPreviousResult() },
            onCloseSearch = { onStopSearch() }
        )
        Divider(Orientation.Horizontal, modifier = Modifier.fillMaxWidth().height(1.dp))
    }
}

@Composable
private fun ChatSearchBar(
    searchState: SearchState,
    modifier: Modifier = Modifier,
    onSearchQueryChange: (String) -> Unit = {},
    onNextResult: () -> Unit = {},
    onPreviousResult: () -> Unit = {},
    onCloseSearch: () -> Unit = {}
) {
    val searchQuery = searchState.searchQuery.orEmpty()
    val hasResults = searchState.hasResults
    val totalResults = searchState.totalResults
    val currentResultIndex = searchState.currentSearchResultIndex

    val searchFieldState = rememberTextFieldState(searchQuery)

    val focusRequester = remember { FocusRequester() }

    // Handle text changes
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()

        snapshotFlow { searchFieldState.text.toString() }
            .distinctUntilChanged()
            .collect { query -> onSearchQueryChange(query) }
    }

    // Handle focus request
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(ChatAppColors.Panel.background)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Search input field
        TextField(
            state = searchFieldState,
            placeholder = { Text(ModularPluginFrontendBundle.message("chat.search.placeholder")) },
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester = focusRequester)
                .onPreviewKeyEvent { keyEvent ->
                    when {
                        keyEvent.key == Key.Escape && keyEvent.type == KeyEventType.KeyDown -> {
                            onCloseSearch()
                            true
                        }

                        keyEvent.key == Key.Enter && keyEvent.type == KeyEventType.KeyDown -> {
                            onNextResult()
                            true
                        }

                        keyEvent.key == Key.F3 && keyEvent.type == KeyEventType.KeyDown -> {
                            if (keyEvent.isShiftPressed) {
                                onPreviousResult()
                            } else {
                                onNextResult()
                            }
                            true
                        }

                        else -> false
                    }
                }
        )

        // Results counter
        if (hasResults) {
            Text(
                text = "${currentResultIndex + 1}/$totalResults",
                style = JewelTheme.defaultTextStyle.copy(
                    fontSize = 12.sp,
                    color = ChatAppColors.Text.disabled
                )
            )
        } else if (searchQuery.isNotBlank()) {
            Text(
                text = ModularPluginFrontendBundle.message("chat.no.results"),
                style = JewelTheme.defaultTextStyle.copy(
                    fontSize = 12.sp,
                    color = ChatAppColors.Text.disabled
                )
            )
        }

        // Navigation buttons
        DefaultButton(
            onClick = onPreviousResult,
            enabled = hasResults && totalResults > 1,
            modifier = Modifier.widthIn(min = 40.dp)
        ) {
            Text("↑")
        }

        DefaultButton(
            onClick = onNextResult,
            enabled = hasResults && totalResults > 1,
            modifier = Modifier.widthIn(min = 40.dp)
        ) {
            Text("↓")
        }

        // Close button
        IconButton(onClick = onCloseSearch) {
            Icon(
                ChatAppIcons.Header.close,
                contentDescription = ModularPluginFrontendBundle.message("chat.close.search.button")
            )
        }
    }
}
