package com.github.quanta_dance.quanta.plugins.intellij.frontend.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerMoveFilter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.github.quanta_dance.quanta.plugins.intellij.frontend.ModularPluginFrontendBundle
import com.github.quanta_dance.quanta.plugins.intellij.frontend.chat.viewmodel.ChatViewModel
import com.github.quanta_dance.quanta.plugins.intellij.frontend.chat.viewmodel.MessageInputState
import com.github.quanta_dance.quanta.plugins.intellij.frontend.settings.FrontendQuantaSettingsState
import com.github.quanta_dance.quanta.plugins.intellij.frontend.ui.*
import com.github.quanta_dance.quanta.plugins.intellij.frontend.voice.FrontendAIVoiceService
import com.github.quanta_dance.quanta.plugins.intellij.frontend.voice.FrontendMicrophoneService
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ChatMessage
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.ChatPlanStatusDto
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.*

@Composable
fun ChatApp(project: Project, viewModel: ChatViewModel, voiceService: FrontendAIVoiceService) {
    val chatMessages by viewModel.chatMessagesFlow.collectAsState(emptyList())
    val sessions by viewModel.sessionsFlow.collectAsState(emptyList())
    val searchState by viewModel.searchChatMessagesHandler().searchStateFlow.collectAsState(SearchState.Idle)
    val messageInputState by viewModel.promptInputState.collectAsState(MessageInputState.Disabled)
    var voiceEnabled by remember { mutableStateOf(FrontendQuantaSettingsState.instance.state.voiceEnabled) }
    var selectedModel by remember { mutableStateOf(FrontendQuantaSettingsState.instance.state.aiChatModel) }
    var availableModels by remember { mutableStateOf(FrontendQuantaSettingsState.instance.state.availableChatModels) }
    val planStatus by viewModel.planStatusFlow.collectAsState(ChatPlanStatusDto())
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

            // Message area
            ChatList(
                project = project,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                chatMessages = chatMessages,
                listState = listState,
                searchState = searchState
            )

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
                onModelSelected = { model ->
                    selectedModel = model
                    FrontendQuantaSettingsState.instance.state.aiChatModel = model
                },
                onToggleMic = { microphoneService.toggleListening() },
                onToggleVoiceFeedback = {
                    voiceEnabled = !voiceEnabled
                    FrontendQuantaSettingsState.instance.state.voiceEnabled = voiceEnabled
                    if (!voiceEnabled) {
                        voiceService.stopTalking()
                    }
                },
                onInputChanged = { viewModel.onPromptInputChanged(it) },
                onSend = { viewModel.onSendMessage() },
                onStop = { viewModel.onAbortSendingMessage() }
            )
        }
    )
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
    searchState: SearchState
) {
    Box(modifier = modifier) {
        if (chatMessages.isEmpty()) {
            // Empty state
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
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    items(chatMessages, key = { it.id }) { message ->
                        MessageBubble(
                            project = project,
                            message = message,
                            modifier = Modifier.fillMaxWidth(),
                            isMatchingSearch = searchState.searchQuery?.let { query -> message.matches(query) }
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