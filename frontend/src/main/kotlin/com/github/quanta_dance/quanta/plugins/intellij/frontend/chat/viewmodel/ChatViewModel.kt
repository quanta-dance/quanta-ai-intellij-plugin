package com.github.quanta_dance.quanta.plugins.intellij.frontend.chat.viewmodel

import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ChatMessage
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.ChatSessionDto
import com.intellij.openapi.Disposable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

interface ChatViewModelApi : Disposable {
    val chatMessagesFlow: StateFlow<List<ChatMessage>>
    val sessionsFlow: StateFlow<List<ChatSessionDto>>

    fun onPromptInputChanged(input: String)

    fun onSendMessage()

    fun onAbortSendingMessage()

    fun onCreateNewSession()

    fun onActivateSession(sessionId: String)

    fun onDeleteSession(sessionId: String)

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

    private val _promptInputState = MutableStateFlow<MessageInputState>(MessageInputState.Disabled)
    override val promptInputState: StateFlow<MessageInputState> = _promptInputState.asStateFlow()

    private val searchChatMessagesHandler: SearchChatMessagesHandler = SearchChatMessagesHandlerImpl(
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
        val currentPromptInputState = _promptInputState.value
        _promptInputState.value = when {
            currentPromptInputState is MessageInputState.Sending -> MessageInputState.Sending(input)
            input.isEmpty() -> MessageInputState.Disabled
            else -> MessageInputState.Enabled(input)
        }
    }

    override fun onSendMessage() {
        currentSendMessageJob = coroutineScope.launch {
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

    override fun searchChatMessagesHandler(): SearchChatMessagesHandler = searchChatMessagesHandler

    private fun getCurrentInputTextIfNotEmpty(): String? =
        _promptInputState.value.inputText.takeIf { it.isNotBlank() }

    private fun emitPromptInputState(state: MessageInputState) {
        _promptInputState.value = state
    }

    override fun dispose() = Unit
}
