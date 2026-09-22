// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.frontend.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import com.github.quanta_dance.quanta.plugins.intellij.frontend.ModularPluginFrontendBundle
import com.github.quanta_dance.quanta.plugins.intellij.frontend.chat.viewmodel.ChatViewModel
import com.github.quanta_dance.quanta.plugins.intellij.frontend.chat.viewmodel.MessageInputState
import com.github.quanta_dance.quanta.plugins.intellij.frontend.settings.FrontendQuantaSettingsState
import com.github.quanta_dance.quanta.plugins.intellij.frontend.settings.FrontendSettingsSyncStateService
import com.github.quanta_dance.quanta.plugins.intellij.frontend.ui.SearchState
import com.github.quanta_dance.quanta.plugins.intellij.frontend.ui.currentSearchResultIndex
import com.github.quanta_dance.quanta.plugins.intellij.frontend.ui.currentSelectedSearchResultId
import com.github.quanta_dance.quanta.plugins.intellij.frontend.ui.hasResults
import com.github.quanta_dance.quanta.plugins.intellij.frontend.ui.isSearching
import com.github.quanta_dance.quanta.plugins.intellij.frontend.ui.messageBubble
import com.github.quanta_dance.quanta.plugins.intellij.frontend.ui.promptInput
import com.github.quanta_dance.quanta.plugins.intellij.frontend.ui.searchQuery
import com.github.quanta_dance.quanta.plugins.intellij.frontend.ui.totalResults
import com.github.quanta_dance.quanta.plugins.intellij.frontend.voice.FrontendAIVoiceService
import com.github.quanta_dance.quanta.plugins.intellij.frontend.voice.FrontendMicrophoneService
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ChatMessage
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.AcpAgentDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.AgentChannelAuthorTypeDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.AgentChannelEventDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.AgentInfoDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.ChatPlanStatusDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.DelegatedTaskDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.DelegatedTaskStatusDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.McpServerStatusDto
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer

@Composable
fun chatApp(
    project: Project,
    viewModel: ChatViewModel,
    voiceService: FrontendAIVoiceService,
) {
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
    var maxMessageWidth by remember {
        mutableStateOf(
            FrontendQuantaSettingsState.instance.state.maxMessageWidth.coerceIn(
                FrontendQuantaSettingsState.MESSAGE_WIDTH_RANGE,
            ),
        )
    }
    val planStatus by viewModel.planStatusFlow.collectAsState(ChatPlanStatusDto())
    val agents by viewModel.agentsFlow.collectAsState(emptyList())
    val acpAgents by viewModel.acpAgentsFlow.collectAsState(emptyList())
    val acpDiscoveryLoading by viewModel.acpDiscoveryLoadingFlow.collectAsState(false)
    val allowedAcpAgentIds by viewModel.allowedAcpAgentIdsFlow.collectAsState(emptySet())
    val mcpServers by viewModel.mcpServersFlow.collectAsState(emptyList())
    val mcpConfigurationLoading by viewModel.mcpConfigurationLoadingFlow.collectAsState(true)
    val mcpConfigurationError by viewModel.mcpConfigurationErrorFlow.collectAsState(null)
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
    var showAgenticTeamDialog by remember { mutableStateOf(false) }
    var showMcpToolsDialog by remember { mutableStateOf(false) }

    val lastMessageScrollKey =
        remember(chatMessages) {
            chatMessages.lastOrNull()?.let { message ->
                listOf(
                    message.id,
                    message.content,
                    message.type.name,
                    message.toolItems.joinToString(
                        "|",
                    ) { tool -> "${tool.callId}:${tool.status}:${tool.displayText}:${tool.errorText}:${tool.detailText}" },
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
            val configuredMaxMessageWidth =
                settings.maxMessageWidth.coerceIn(FrontendQuantaSettingsState.MESSAGE_WIDTH_RANGE)
            if (maxMessageWidth != configuredMaxMessageWidth) {
                maxMessageWidth = configuredMaxMessageWidth
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

        val summary =
            candidate.voiceSummary
                ?.trim()
                ?.replace(Regex("\\s+"), " ")
                .orEmpty()
        if (summary.isNotEmpty()) {
            lastSpokenMessageId = candidate.id
            voiceService.say(summary)
        }
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(ChatAppColors.Panel.background),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.Start,
        content = {
            // Chat header with search button
            chatHeaderWithSearchBar(
                searchState = searchState,
                onStartSearch = { viewModel.searchChatMessagesHandler().onStartSearch() },
                onStopSearch = { viewModel.searchChatMessagesHandler().onStopSearch() },
                onSearchQueryChange = { query -> viewModel.searchChatMessagesHandler().onSearchQuery(query) },
                onNextResult = { viewModel.searchChatMessagesHandler().onNavigateToNextSearchResult() },
                onPreviousResult = { viewModel.searchChatMessagesHandler().onNavigateToPreviousSearchResult() },
            )

            sessionTabs(
                project = project,
                sessions = sessions,
                onSessionSelected = { sessionId -> viewModel.onActivateSession(sessionId) },
                onSessionDeleted = { sessionId -> viewModel.onDeleteSession(sessionId) },
            )

            chatList(
                project = project,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                chatMessages = chatMessages,
                listState = listState,
                searchState = searchState,
                maxMessageWidth = maxMessageWidth,
            )

            if (agenticEnabled) {
                agentPresenceStrip(
                    modifier = Modifier.fillMaxWidth(),
                    internalAgents = agents,
                    allowedAcpAgents = acpAgents.filter { it.id in allowedAcpAgentIds },
                    delegatedTasks = delegatedTasks,
                    chatMessages = chatMessages,
                    managerBusy = messageInputState is MessageInputState.Sending,
                )
            }

            promptInput(
                modifier =
                    Modifier
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
                    showAgenticTeamDialog = true
                    viewModel.onRefreshAcpAgents()
                },
                onToggleMcpTools = { showMcpToolsDialog = true },
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
                onSync = { scope.launch { settingsSyncService.retryNow() } },
            )

            if (showAgenticTeamDialog) {
                agenticTeamDialog(
                    agenticEnabled = agenticEnabled,
                    internalAgents = agents,
                    acpAgents = acpAgents,
                    acpDiscoveryLoading = acpDiscoveryLoading,
                    allowedAcpAgentIds = allowedAcpAgentIds,
                    onSetAgenticEnabled = { enabled ->
                        agenticEnabled = enabled
                        FrontendQuantaSettingsState.instance.state.agenticEnabled = enabled
                        viewModel.onSetAgenticMode(enabled)
                    },
                    onRefresh = { viewModel.onRefreshAcpAgents() },
                    onSetAcpAllowed = { agent, allowed ->
                        val action = if (allowed) "Add" else "Remove"
                        val message =
                            if (allowed) {
                                "Add ${agent.name} to this chat and turn on agentic team mode?\n\n" +
                                    "This independent external ACP agent may receive task context and access this project " +
                                    "using its own tools.\n\n${agent.transportDescription()}"
                            } else {
                                "Remove ${agent.name} from this chat? Any active work for this agent will be stopped."
                            }
                        val approved =
                            Messages.showYesNoDialog(
                                project,
                                message,
                                "$action External Agent",
                                action,
                                "Cancel",
                                Messages.getQuestionIcon(),
                            ) == Messages.YES
                        if (approved) {
                            val enableAgenticMode = allowed && !agenticEnabled
                            if (enableAgenticMode) {
                                agenticEnabled = true
                                FrontendQuantaSettingsState.instance.state.agenticEnabled = true
                            }
                            viewModel.onSetAcpAgentAllowed(agent.id, allowed, enableAgenticMode)
                        }
                    },
                    onDismiss = { showAgenticTeamDialog = false },
                )
            }

            if (showMcpToolsDialog) {
                mcpToolsDialog(
                    servers = mcpServers,
                    configurationLoading = mcpConfigurationLoading,
                    configurationError = mcpConfigurationError,
                    onSetEnabled = viewModel::onSetMcpServerEnabled,
                    onReconnect = viewModel::onRetryMcpServerConnection,
                    onDismiss = { showMcpToolsDialog = false },
                )
            }
        },
    )
}

@Composable
private fun mcpToolsDialog(
    servers: List<McpServerStatusDto>,
    configurationLoading: Boolean,
    configurationError: String?,
    onSetEnabled: (String, Boolean) -> Unit,
    onReconnect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier =
                Modifier
                    .widthIn(min = 460.dp, max = 620.dp)
                    .background(ChatAppColors.Panel.background, RoundedCornerShape(12.dp))
                    .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("MCP tools", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(
                "Enable only the MCP servers this chat should expose to AI. These choices apply only to this chat.",
                style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp, color = Color.Gray),
            )
            Divider(orientation = Orientation.Horizontal)

            when {
                configurationLoading -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        loadingIndicator()
                        Text(
                            "Syncing MCP configuration and discovering available tools…",
                            style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp, color = Color.Gray),
                        )
                    }
                }

                configurationError != null -> {
                    Text(
                        "MCP configuration could not be loaded: $configurationError",
                        style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp, color = Color(0xFFE0B86A)),
                    )
                }

                servers.isEmpty() -> {
                    Text(
                        "No MCP servers are configured. Configure servers in Settings to make tools available here.",
                        style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp, color = Color.Gray),
                    )
                }

                else -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        servers.forEach { server ->
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(8.dp))
                                        .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(server.name, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        server.connectionDescription(),
                                        style = JewelTheme.defaultTextStyle.copy(fontSize = 11.sp, color = Color.Gray),
                                    )
                                    server.error?.let { error ->
                                        Text(
                                            error,
                                            style =
                                                JewelTheme.defaultTextStyle.copy(
                                                    fontSize = 11.sp,
                                                    color = Color(0xFFE0B86A),
                                                ),
                                        )
                                    }
                                }
                                Column(
                                    horizontalAlignment = Alignment.End,
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    OutlinedButton(onClick = {
                                        onSetEnabled(
                                            server.name,
                                            !server.enabledForCurrentChat,
                                        )
                                    }) {
                                        Text(if (server.enabledForCurrentChat) "Disable" else "Enable")
                                    }
                                    if (server.requiresAuthorization) {
                                        OutlinedButton(
                                            enabled = !server.connecting,
                                            onClick = { onReconnect(server.name) },
                                        ) {
                                            Text(if (server.connecting) "Authorizing…" else "Authorize")
                                        }
                                    } else if (server.error != null && !server.connected) {
                                        OutlinedButton(
                                            enabled = !server.connecting,
                                            onClick = { onReconnect(server.name) },
                                        ) {
                                            Text(if (server.connecting) "Retrying…" else "Retry")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                DefaultButton(onClick = onDismiss) { Text("Done") }
            }
        }
    }
}

@Composable
private fun loadingIndicator() {
    val color = JewelTheme.defaultTextStyle.color
    val transition = rememberInfiniteTransition(label = "loading_indicator")
    val rotation =
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 900, easing = LinearEasing)),
            label = "loading_indicator_rotation",
        )

    Canvas(
        modifier =
            Modifier
                .size(16.dp)
                .rotate(rotation.value),
    ) {
        drawArc(
            color = color,
            startAngle = 20f,
            sweepAngle = 290f,
            useCenter = false,
            style = Stroke(width = 2.dp.toPx()),
        )
    }
}

private fun McpServerStatusDto.connectionDescription(): String =
    when {
        connected -> "Connected · $toolCount tool${if (toolCount == 1) "" else "s"}"
        connecting -> "Connecting or waiting for authorization"
        enabledForCurrentChat -> "Enabled for this chat · Not connected"
        else -> "Disabled for this chat"
    }

@Composable
private fun agenticTeamDialog(
    agenticEnabled: Boolean,
    internalAgents: List<AgentInfoDto>,
    acpAgents: List<AcpAgentDto>,
    acpDiscoveryLoading: Boolean,
    allowedAcpAgentIds: Set<String>,
    onSetAgenticEnabled: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onSetAcpAllowed: (AcpAgentDto, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier =
                Modifier
                    .widthIn(min = 460.dp, max = 620.dp)
                    .background(ChatAppColors.Panel.background, RoundedCornerShape(12.dp))
                    .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Agentic team", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text(
                        "Choose which teammates this chat may use.",
                        style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp, color = Color.Gray),
                    )
                }
                OutlinedButton(onClick = { onSetAgenticEnabled(!agenticEnabled) }) {
                    Text(if (agenticEnabled) "Turn off" else "Turn on")
                }
            }

            Divider(orientation = Orientation.Horizontal)
            Text("Quanta teammates", fontWeight = FontWeight.SemiBold)
            Text(
                if (internalAgents.isEmpty()) "No internal teammates active." else "${internalAgents.size} internal teammate(s) active.",
                style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp, color = Color.Gray),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("External ACP agents", fontWeight = FontWeight.SemiBold)
                OutlinedButton(onClick = onRefresh, enabled = !acpDiscoveryLoading) {
                    Text(if (acpDiscoveryLoading) "Discovering…" else "Refresh")
                }
            }
            when {
                acpDiscoveryLoading -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        loadingIndicator()
                        Text(
                            "Discovering available ACP agents…",
                            style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp, color = Color.Gray),
                        )
                    }
                }

                acpAgents.isEmpty() -> {
                    Text(
                        "No available ACP agents found. Refresh after installing or configuring an agent.",
                        style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp, color = Color.Gray),
                    )
                }

                else -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        acpAgents.forEach { agent ->
                            val allowed = agent.id in allowedAcpAgentIds
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(8.dp))
                                        .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(agent.name, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        agent.transportDescription(),
                                        style = JewelTheme.defaultTextStyle.copy(fontSize = 11.sp, color = Color.Gray),
                                    )
                                    Text(
                                        if (allowed) "Allowed for this chat" else "Available · Not allowed",
                                        style =
                                            JewelTheme.defaultTextStyle.copy(
                                                fontSize = 11.sp,
                                                color = if (allowed) Color(0xFF67C587) else Color(0xFFE0B86A),
                                            ),
                                    )
                                }
                                OutlinedButton(onClick = { onSetAcpAllowed(agent, !allowed) }) {
                                    Text(if (allowed) "Remove" else "Add to chat")
                                }
                            }
                        }
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                DefaultButton(onClick = onDismiss) { Text("Done") }
            }
        }
    }
}

private fun AcpAgentDto.transportDescription(): String =
    if (executablePath.startsWith("tcp://")) {
        val endpoint = executablePath.removePrefix("tcp://")
        if (endpoint.startsWith("localhost:") || endpoint.startsWith("127.0.0.1:") || endpoint.startsWith("[::1]:")) {
            "External · Local TCP · $endpoint"
        } else {
            "External · Remote TCP · $endpoint"
        }
    } else {
        "External · Local application · $executablePath"
    }

@Composable
private fun channelActivityPanel(
    modifier: Modifier = Modifier,
    tasks: List<DelegatedTaskDto>,
    events: List<AgentChannelEventDto>,
) {
    if (tasks.isEmpty() && events.isEmpty()) return
    Column(
        modifier =
            modifier
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
                    modifier =
                        Modifier
                            .size(8.dp)
                            .background(taskStatusColor(task.status), RoundedCornerShape(99.dp)),
                )
                Text(task.title, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Text(task.status.name.lowercase(), fontSize = 11.sp, color = ChatAppColors.Text.disabled)
                if (task.assignedRoles.isNotEmpty()) {
                    Text(
                        task.assignedRoles.joinToString(", "),
                        fontSize = 11.sp,
                        color = ChatAppColors.Text.disabled,
                    )
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
                        modifier =
                            Modifier
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

private fun eventAuthorLabel(event: AgentChannelEventDto): String =
    when (event.authorType) {
        AgentChannelAuthorTypeDto.DIRECTOR -> "Director"
        AgentChannelAuthorTypeDto.MANAGER -> event.authorRole ?: "Manager"
        AgentChannelAuthorTypeDto.AGENT -> event.authorRole ?: "Agent"
        AgentChannelAuthorTypeDto.SYSTEM -> "System"
    }

private fun taskStatusColor(status: DelegatedTaskStatusDto): Color =
    when (status) {
        DelegatedTaskStatusDto.QUEUED -> Color(0xFFEBCB8B)
        DelegatedTaskStatusDto.RUNNING -> Color(0xFF88C0D0)
        DelegatedTaskStatusDto.BLOCKED -> Color(0xFFD08770)
        DelegatedTaskStatusDto.DONE -> Color(0xFFA3BE8C)
        DelegatedTaskStatusDto.FAILED -> Color(0xFFBF616A)
    }

private enum class AgentPresenceState(
    val label: String,
    val color: Color,
) {
    IDLE("Ready", Color(0xFF8FBCBB)),
    WORKING("Working", Color(0xFF88C0D0)),
    WAITING("Waiting", Color(0xFFEBCB8B)),
    FAILED("Needs attention", Color(0xFFBF616A)),
}

private data class AgentPresence(
    val id: String,
    val name: String,
    val type: String,
    val state: AgentPresenceState,
    val details: String,
    val instructions: String,
    val color: Color,
    val external: Boolean = false,
)

private fun DelegatedTaskDto.isAssignedTo(agent: AgentInfoDto): Boolean {
    val normalizedRole = agent.role.trim().lowercase()
    return agent.id in assignedAgentIds || assignedRoles.any { it.trim().lowercase() == normalizedRole }
}

@Composable
private fun agentPresenceStrip(
    modifier: Modifier = Modifier,
    internalAgents: List<AgentInfoDto>,
    allowedAcpAgents: List<AcpAgentDto>,
    delegatedTasks: List<DelegatedTaskDto>,
    chatMessages: List<ChatMessage>,
    managerBusy: Boolean,
) {
    val presences =
        buildList {
            add(
                AgentPresence(
                    id = "main-agent",
                    name = "AI",
                    type = "Main agent",
                    state = if (managerBusy) AgentPresenceState.WORKING else AgentPresenceState.IDLE,
                    details =
                        if (managerBusy) {
                            "The main agent is working on the current response."
                        } else {
                            "The main agent is ready for the next task."
                        },
                    instructions =
                        "Coordinates the chat, delegates focused work to teammates, and verifies the final result.",
                    color = Color(0xFF5E81AC),
                ),
            )
            internalAgents.forEach { agent ->
                val tasks = delegatedTasks.filter { it.isAssignedTo(agent) }
                val state =
                    when {
                        agent.isWorking || tasks.any { it.status == DelegatedTaskStatusDto.RUNNING } -> {
                            AgentPresenceState.WORKING
                        }

                        tasks.any { it.status == DelegatedTaskStatusDto.QUEUED || it.status == DelegatedTaskStatusDto.BLOCKED } -> {
                            AgentPresenceState.WAITING
                        }

                        tasks.any { it.status == DelegatedTaskStatusDto.FAILED } -> {
                            AgentPresenceState.FAILED
                        }

                        else -> {
                            AgentPresenceState.IDLE
                        }
                    }
                val details =
                    buildString {
                        append(agent.model?.takeIf(String::isNotBlank) ?: "Quanta teammate")
                        if (tasks.isNotEmpty()) {
                            append("\n\nCurrent work")
                            tasks.takeLast(3).forEach { task ->
                                append("\n• ").append(task.title).append(" · ").append(task.status.name.lowercase())
                            }
                        } else {
                            append("\n\nNo delegated task is currently assigned.")
                        }
                    }
                add(
                    AgentPresence(
                        id = agent.id,
                        name = agent.role.replaceFirstChar { it.uppercase() },
                        type = "Quanta teammate",
                        state = state,
                        details = details,
                        instructions =
                            agent.instructions?.takeIf(String::isNotBlank)
                                ?: "No custom instructions are configured for this teammate.",
                        color = colorForAgent(agent.id),
                    ),
                )
            }
            allowedAcpAgents.forEach { agent ->
                val latestCard =
                    chatMessages
                        .asReversed()
                        .flatMap { it.toolItems.asReversed() }
                        .firstOrNull { item ->
                            item.toolName == "AcpDelegationCard" && item.displayText.startsWith("${agent.name} ·")
                        }
                val state =
                    when (latestCard?.status) {
                        com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolExecutionStatus.EXECUTING -> {
                            AgentPresenceState.WORKING
                        }

                        com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolExecutionStatus.FAILED -> {
                            AgentPresenceState.FAILED
                        }

                        else -> {
                            AgentPresenceState.IDLE
                        }
                    }
                add(
                    AgentPresence(
                        id = "acp:${agent.id}",
                        name = agent.name,
                        type = "External ACP agent",
                        state = state,
                        details = latestCard?.detailText ?: "Allowed for this chat. No live task is currently running.",
                        instructions =
                            "Independent external ACP agent. It receives delegated task context only after you allow it for this chat.",
                        color = Color(0xFFB48EAD),
                        external = true,
                    ),
                )
            }
        }
    if (presences.isEmpty()) return

    Row(
        modifier =
            modifier
                .horizontalScroll(rememberScrollState())
                // Reserve room for the activity ring so working and idle strips keep the same height.
                .padding(horizontal = 8.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Agents", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = ChatAppColors.Text.disabled)
        presences.forEach { presence -> agentPresenceAvatar(presence) }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun agentPresenceAvatar(presence: AgentPresence) {
    var isAvatarHovered by remember(presence.id) { mutableStateOf(false) }
    var isPopupHovered by remember(presence.id) { mutableStateOf(false) }
    var showStatusPopup by remember(presence.id) { mutableStateOf(false) }
    var showProfileDialog by remember(presence.id) { mutableStateOf(false) }
    val popupGapPx = with(LocalDensity.current) { 8.dp.roundToPx() }
    val popupPositionProvider =
        remember(popupGapPx) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: androidx.compose.ui.unit.IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset {
                    val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
                    val centeredX = anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2
                    val x = centeredX.coerceIn(0, maxX)
                    val aboveY = anchorBounds.top - popupContentSize.height - popupGapPx
                    val maxY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
                    val y =
                        if (aboveY >= 0) {
                            aboveY
                        } else {
                            (anchorBounds.bottom + popupGapPx).coerceAtMost(maxY)
                        }
                    return IntOffset(x, y)
                }
            }
        }

    LaunchedEffect(isAvatarHovered, isPopupHovered) {
        if (isAvatarHovered || isPopupHovered) {
            showStatusPopup = true
        } else {
            delay(150)
            if (!isAvatarHovered && !isPopupHovered) {
                showStatusPopup = false
            }
        }
    }

    val isWorking = presence.state == AgentPresenceState.WORKING
    val activityTransition = rememberInfiniteTransition(label = "agentPresenceActivity")
    val activityPulse by
        activityTransition.animateFloat(
            initialValue = 0.25f,
            targetValue = 0.75f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 900, easing = LinearEasing)),
            label = "agentPresencePulse",
        )

    if (showProfileDialog) {
        agentProfileDialog(
            presence = presence,
            onDismiss = { showProfileDialog = false },
        )
    }

    // This fixed-size surface reserves space for the activity ring even while it is invisible.
    // It keeps the strip height stable and prevents the ring stroke from being clipped.
    Box(
        modifier =
            Modifier
                .size(42.dp)
                .pointerHoverIcon(PointerIcon.Hand)
                .onPointerEvent(PointerEventType.Enter) { isAvatarHovered = true }
                .onPointerEvent(PointerEventType.Exit) { isAvatarHovered = false },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier =
                Modifier
                    .size(42.dp)
                    .alpha(if (isWorking) activityPulse else 0f),
        ) {
            val strokeWidth = 2.dp.toPx()
            drawCircle(
                color = presence.state.color,
                radius = size.minDimension / 2 - strokeWidth / 2 - 1.dp.toPx(),
                style = Stroke(width = strokeWidth),
            )
        }
        if (showStatusPopup) {
            val hoverSummary =
                presence.details
                    .lineSequence()
                    .map(String::trim)
                    .firstOrNull(String::isNotBlank)
                    ?.take(120)
                    ?: "No current activity."
            Popup(
                popupPositionProvider = popupPositionProvider,
                properties = PopupProperties(focusable = false),
            ) {
                Column(
                    modifier =
                        Modifier
                            .zIndex(2f)
                            .widthIn(min = 210.dp, max = 300.dp)
                            .background(ChatAppColors.Panel.background, RoundedCornerShape(8.dp))
                            .border(1.dp, presence.state.color.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .padding(10.dp)
                            .onPointerEvent(PointerEventType.Enter) { isPopupHovered = true }
                            .onPointerEvent(PointerEventType.Exit) { isPopupHovered = false },
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(presence.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${presence.type} · ${presence.state.label}",
                        fontSize = 10.sp,
                        color = presence.state.color,
                    )
                    Text(hoverSummary, fontSize = 11.sp, color = ChatAppColors.Text.disabled)
                    Text(
                        "Click avatar to view profile",
                        fontSize = 10.sp,
                        color = ChatAppColors.Text.disabled,
                    )
                }
            }
        }
        Box(
            modifier =
                Modifier
                    .size(30.dp)
                    .background(
                        presence.color.copy(alpha = if (isWorking) 1f else 0.68f),
                        RoundedCornerShape(999.dp),
                    ).border(
                        width = if (isWorking) 2.dp else 1.dp,
                        color = if (isWorking) presence.state.color else Color.Transparent,
                        shape = RoundedCornerShape(999.dp),
                    ).clickable { showProfileDialog = true },
            contentAlignment = Alignment.Center,
        ) {
            if (presence.external || presence.id == "main-agent") {
                Icon(
                    key = ChatAppIcons.Header.agenticTeam,
                    contentDescription = "${presence.name}, ${presence.state.label}. Hover for status, click to view profile.",
                    modifier = Modifier.size(16.dp),
                    tint = Color.White,
                )
            } else {
                Text(
                    text = presence.name.firstOrNull()?.uppercase() ?: "A",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Box(
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(if (isWorking) 11.dp else 9.dp)
                        .border(1.dp, ChatAppColors.Panel.background, RoundedCornerShape(999.dp))
                        .background(presence.state.color, RoundedCornerShape(999.dp)),
            )
        }
    }
}

@Composable
private fun agentProfileDialog(
    presence: AgentPresence,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier =
                Modifier
                    .widthIn(min = 360.dp, max = 520.dp)
                    .background(ChatAppColors.Panel.background, RoundedCornerShape(12.dp))
                    .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("${presence.name} profile", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(
                "${presence.type} · ${presence.state.label}",
                fontSize = 12.sp,
                color = presence.state.color,
            )
            Divider(orientation = Orientation.Horizontal)
            Text("Current status", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 160.dp)
                        .verticalScroll(rememberScrollState())
                        .background(ChatAppColors.MessageBubble.othersBackground, RoundedCornerShape(8.dp))
                        .padding(10.dp),
            ) {
                Text(presence.details, fontSize = 12.sp)
            }
            Text("Instructions", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState())
                        .background(ChatAppColors.MessageBubble.othersBackground, RoundedCornerShape(8.dp))
                        .padding(10.dp),
            ) {
                Text(presence.instructions, fontSize = 12.sp)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                DefaultButton(onClick = onDismiss) { Text("Close") }
            }
        }
    }
}

private fun colorForAgent(agentId: String): Color {
    val palette =
        listOf(
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
private fun sessionTabs(
    project: Project,
    sessions: List<com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.ChatSessionDto>,
    onSessionSelected: (String) -> Unit,
    onSessionDeleted: (String) -> Unit,
) {
    if (sessions.isEmpty()) return

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        sessions.forEach { session ->
            val active = session.isActive
            Row(
                modifier =
                    Modifier
                        .background(
                            if (active) ChatAppColors.MessageBubble.othersBackground else ChatAppColors.Panel.background,
                            RoundedCornerShape(10.dp),
                        ).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier.clickable { onSessionSelected(session.id) },
                ) {
                    Text(
                        text = session.title,
                        style =
                            JewelTheme.defaultTextStyle.copy(
                                fontSize = 12.sp,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            ),
                    )
                }
                Icon(
                    key = ChatAppIcons.Header.close,
                    contentDescription = "Delete session",
                    modifier =
                        Modifier.size(12.dp).clickable {
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
private fun visiblePlanStatus(
    status: String,
    text: String,
) {
    var hovered by remember { mutableStateOf(false) }
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        if (hovered && text.isNotBlank()) {
            Box(
                modifier =
                    Modifier
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
            modifier =
                Modifier
                    .background(ChatAppColors.MessageBubble.othersBackground, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .onPointerEvent(PointerEventType.Enter) {
                        hovered = true
                    }.onPointerEvent(PointerEventType.Exit) {
                        hovered = false
                    },
            style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
        )
    }
}

@Composable
private fun chatList(
    project: Project,
    modifier: Modifier = Modifier,
    chatMessages: List<ChatMessage>,
    listState: LazyListState,
    searchState: SearchState,
    maxMessageWidth: Int,
) {
    Box(modifier = modifier) {
        if (chatMessages.isEmpty()) {
            emptyChatListPlaceholder()
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
                    items(chatMessages, key = { it.id }) { message ->
                        messageBubble(
                            project = project,
                            message = message,
                            maxMessageWidth = maxMessageWidth,
                            modifier = Modifier.fillMaxWidth(),
                            isMatchingSearch =
                                searchState.searchQuery?.let { query -> message.matches(query) }
                                    ?: false,
                            isHighlightedInSearch = message.id == searchState.currentSelectedSearchResultId,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun agentThread(
    project: Project,
    parentId: String,
    threadMessages: List<ChatMessage>,
    searchState: SearchState,
) {
    var expanded by remember(parentId) { mutableStateOf(false) }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 32.dp, end = 8.dp, top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "Hide agent activity (${threadMessages.size})" else "Show agent activity (${threadMessages.size})")
        }
        if (expanded) {
            threadMessages.forEach { threadMessage ->
                messageBubble(
                    project = project,
                    message = threadMessage,
                    maxMessageWidth =
                        FrontendQuantaSettingsState.instance.state.maxMessageWidth.coerceIn(
                            FrontendQuantaSettingsState.MESSAGE_WIDTH_RANGE,
                        ),
                    modifier = Modifier.fillMaxWidth(),
                    isMatchingSearch =
                        searchState.searchQuery?.let { query -> threadMessage.matches(query) }
                            ?: false,
                    isHighlightedInSearch = threadMessage.id == searchState.currentSelectedSearchResultId,
                )
            }
        }
    }
}

@Composable
private fun emptyChatListPlaceholder(
    placeholderText: String = ModularPluginFrontendBundle.message("chat.start.conversation"),
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = placeholderText,
            style =
                JewelTheme.defaultTextStyle.copy(
                    color = ChatAppColors.Text.disabled,
                    fontSize = 16.sp,
                ),
        )
    }
}

@Composable
private fun chatHeaderWithSearchBar(
    searchState: SearchState,
    onStartSearch: () -> Unit,
    onStopSearch: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onNextResult: () -> Unit,
    onPreviousResult: () -> Unit,
) {
    val showSearchBar = searchState.isSearching

    if (showSearchBar) {
        Divider(Orientation.Horizontal, modifier = Modifier.fillMaxWidth().height(1.dp))
        chatSearchBar(
            searchState = searchState,
            onSearchQueryChange = { query -> onSearchQueryChange(query) },
            onNextResult = { onNextResult() },
            onPreviousResult = { onPreviousResult() },
            onCloseSearch = { onStopSearch() },
        )
        Divider(Orientation.Horizontal, modifier = Modifier.fillMaxWidth().height(1.dp))
    }
}

@Composable
private fun chatSearchBar(
    searchState: SearchState,
    modifier: Modifier = Modifier,
    onSearchQueryChange: (String) -> Unit = {},
    onNextResult: () -> Unit = {},
    onPreviousResult: () -> Unit = {},
    onCloseSearch: () -> Unit = {},
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
        modifier =
            modifier
                .fillMaxWidth()
                .background(ChatAppColors.Panel.background)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Search input field
        TextField(
            state = searchFieldState,
            placeholder = { Text(ModularPluginFrontendBundle.message("chat.search.placeholder")) },
            modifier =
                Modifier
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

                            else -> {
                                false
                            }
                        }
                    },
        )

        // Results counter
        if (hasResults) {
            Text(
                text = "${currentResultIndex + 1}/$totalResults",
                style =
                    JewelTheme.defaultTextStyle.copy(
                        fontSize = 12.sp,
                        color = ChatAppColors.Text.disabled,
                    ),
            )
        } else if (searchQuery.isNotBlank()) {
            Text(
                text = ModularPluginFrontendBundle.message("chat.no.results"),
                style =
                    JewelTheme.defaultTextStyle.copy(
                        fontSize = 12.sp,
                        color = ChatAppColors.Text.disabled,
                    ),
            )
        }

        // Navigation buttons
        DefaultButton(
            onClick = onPreviousResult,
            enabled = hasResults && totalResults > 1,
            modifier = Modifier.widthIn(min = 40.dp),
        ) {
            Text("↑")
        }

        DefaultButton(
            onClick = onNextResult,
            enabled = hasResults && totalResults > 1,
            modifier = Modifier.widthIn(min = 40.dp),
        ) {
            Text("↓")
        }

        // Close button
        IconButton(onClick = onCloseSearch) {
            Icon(
                ChatAppIcons.Header.close,
                contentDescription = ModularPluginFrontendBundle.message("chat.close.search.button"),
            )
        }
    }
}
