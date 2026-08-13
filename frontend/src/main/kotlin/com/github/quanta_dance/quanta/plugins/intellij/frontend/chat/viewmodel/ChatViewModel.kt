// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.frontend.chat.viewmodel

import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ChatMessage
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.AgentChannelEventDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.AgentInfoDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.ChatPlanStatusDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.ChatSessionDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.DelegatedTaskDto
import com.intellij.openapi.Disposable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

interface ChatViewModelApi : Disposable {
    val chatMessagesFlow: StateFlow<List<ChatMessage>>
    val sessionsFlow: StateFlow<List<ChatSessionDto>>
    val planStatusFlow: StateFlow<ChatPlanStatusDto>
    val agentsFlow: StateFlow<List<AgentInfoDto>>
    val delegatedTasksFlow: StateFlow<List<DelegatedTaskDto>>
    val channelEventsFlow: StateFlow<List<AgentChannelEventDto>>

    fun onPromptInputChanged(input: String)

    fun onSendMessage()

    fun onAbortSendingMessage()

    fun onStopAllAgents()

    fun onCreateNewSession()

    fun onActivateSession(sessionId: String)

    fun onDeleteSession(sessionId: String)

    fun onSetAgenticMode(enabled: Boolean)

    fun onCreateDefaultAgentTeam()

    fun searchChatMessagesHandler(): SearchChatMessagesHandler

    val promptInputState: StateFlow<MessageInputState>
}

class ChatViewModel(
    private val coroutineScope: CoroutineScope,
    private val repository: ChatRepositoryApi,
) : ChatViewModelApi {
    private val _chatMessagesFlow = MutableStateFlow(emptyList<ChatMessage>())
    override val chatMessagesFlow: StateFlow<List<ChatMessage>> = _chatMessagesFlow.asStateFlow()

    private val _sessionsFlow = MutableStateFlow(emptyList<ChatSessionDto>())
    override val sessionsFlow: StateFlow<List<ChatSessionDto>> = _sessionsFlow.asStateFlow()

    override val planStatusFlow: StateFlow<ChatPlanStatusDto> = repository.planStatusFlow
    override val agentsFlow: StateFlow<List<AgentInfoDto>> = repository.agentsFlow
    override val delegatedTasksFlow: StateFlow<List<DelegatedTaskDto>> = repository.delegatedTasksFlow
    override val channelEventsFlow: StateFlow<List<AgentChannelEventDto>> = repository.channelEventsFlow

    private val _promptInputState = MutableStateFlow<MessageInputState>(MessageInputState.Disabled)
    override val promptInputState: StateFlow<MessageInputState> = _promptInputState.asStateFlow()

    private val searchChatMessagesHandler: SearchChatMessagesHandler =
        SearchChatMessagesHandlerImpl(
            coroutineScope = coroutineScope,
            messagesFlow = repository.messagesFlow,
        )

    private var currentSendMessageJob: Job? = null

    init {
        repository.messagesFlow
            .onEach { messages -> _chatMessagesFlow.value = messages }
            .launchIn(coroutineScope)

        repository.sessionsFlow
            .onEach { sessions -> _sessionsFlow.value = sessions }
            .launchIn(coroutineScope)
    }

    override fun onPromptInputChanged(input: String) {
        _promptInputState.value =
            when {
                input.isEmpty() -> {
                    if (_promptInputState.value is MessageInputState.Sending) {
                        MessageInputState.Sending("")
                    } else {
                        MessageInputState.Disabled
                    }
                }

                _promptInputState.value is MessageInputState.Sending -> {
                    MessageInputState.Enabled(input)
                }

                else -> {
                    MessageInputState.Enabled(input)
                }
            }
    }

    override fun onSendMessage() {
        currentSendMessageJob =
            coroutineScope.launch {
                try {
                    val currentUserMessage = getCurrentInputTextIfNotEmpty() ?: return@launch
                    emitPromptInputState(MessageInputState.Sending(""))
                    repository.sendMessage(currentUserMessage)
                    emitPromptInputState(
                        when (val currentInputState = getCurrentInputTextIfNotEmpty()) {
                            null -> MessageInputState.Disabled
                            else -> MessageInputState.Enabled(currentInputState)
                        },
                    )
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    emitPromptInputState(MessageInputState.SendFailed(e.message ?: "Unknown error", e))
                }
            }
    }

    override fun onAbortSendingMessage() {
        currentSendMessageJob?.cancel()
        emitPromptInputState(
            when (val currentPromptInput = getCurrentInputTextIfNotEmpty()) {
                null -> MessageInputState.Disabled
                else -> MessageInputState.Enabled(currentPromptInput)
            },
        )
    }

    override fun onStopAllAgents() {
        coroutineScope.launch {
            repository.stopAllAgents()
        }
    }

    override fun onCreateNewSession() {
        coroutineScope.launch {
            repository.createNewSession()
        }
    }

    override fun onActivateSession(sessionId: String) {
        coroutineScope.launch {
            repository.activateSession(sessionId)
        }
    }

    override fun onDeleteSession(sessionId: String) {
        coroutineScope.launch {
            repository.deleteSession(sessionId)
        }
    }

    override fun onSetAgenticMode(enabled: Boolean) {
        coroutineScope.launch {
            repository.setAgenticMode(enabled)
        }
    }

    override fun onCreateDefaultAgentTeam() {
        coroutineScope.launch {
            repository.createDefaultAgentTeam()
        }
    }

    override fun searchChatMessagesHandler(): SearchChatMessagesHandler = searchChatMessagesHandler

    private fun getCurrentInputTextIfNotEmpty(): String? =
        _promptInputState.value
            .takeIf { it is MessageInputState.Enabled || it is MessageInputState.Sending }
            ?.inputText
            ?.takeIf { input -> input.isNotEmpty() }

    private fun emitPromptInputState(newState: MessageInputState) {
        _promptInputState.value = newState
    }

    override fun dispose() {
        currentSendMessageJob?.cancel()
    }
}
