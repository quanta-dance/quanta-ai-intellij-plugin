// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.chat

import com.github.quanta_dance.quanta.plugins.intellij.backend.chat.agents.AgentInboxService
import com.github.quanta_dance.quanta.plugins.intellij.backend.chat.agents.AgentLifecycleService
import com.github.quanta_dance.quanta.plugins.intellij.backend.chat.agents.AgentRegistryService
import com.github.quanta_dance.quanta.plugins.intellij.backend.chat.agents.AgentWakeService
import com.github.quanta_dance.quanta.plugins.intellij.backend.repository.ChatMessageFactory
import com.github.quanta_dance.quanta.plugins.intellij.backend.repository.OpenAIBackendChatResponder
import com.github.quanta_dance.quanta.plugins.intellij.backend.repository.OpenAIBackendChatResponder.ChatTurn
import com.github.quanta_dance.quanta.plugins.intellij.project.CurrentFileContextProvider
import com.github.quanta_dance.quanta.plugins.intellij.services.OpenAIService
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ChatMessage
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ChatMessage.ChatMessageType.AI_THINKING
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.ChatMessageDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.ChatSessionDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.toChatMessageDto
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.openai.models.responses.EasyInputMessage
import com.openai.models.responses.ResponseInputItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

@Service(Service.Level.PROJECT)
class ChatConversationService(
    private val project: Project,
) {
    private val chatMessageFactory = ChatMessageFactory("Quanta AI", "AI Manager")
    private val openAIBackendChatResponder = OpenAIBackendChatResponder()
    private val openAIService: OpenAIService get() = project.service()
    private val registry: AgentRegistryService get() = project.service()
    private val inbox: AgentInboxService get() = project.service()
    private val lifecycle: AgentLifecycleService get() = project.service()
    private val wake: AgentWakeService get() = project.service()
    private val persistence: ChatConversationStateService get() = project.service()
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val _sessions = MutableStateFlow<List<ChatSessionDto>>(emptyList())

    init {
        persistence.ensureSessionExists()
        _messages.value = persistence.loadActiveMessages()
        _sessions.value = persistence.listSessions()
        openAIService.switchToSession(persistence.getActiveSessionId(), persistence.getActiveLastResponseId())
    }

    fun messagesFlow(): Flow<List<ChatMessageDto>> =
        _messages.map { messagesList -> messagesList.map { it.toChatMessageDto() } }

    fun sessionsFlow(): Flow<List<ChatSessionDto>> = _sessions

    fun createNewSession() {
        val sessionId = persistence.createSession()
        _messages.value = emptyList()
        _sessions.value = persistence.listSessions()
        openAIService.switchToSession(sessionId, null)
    }

    fun activateSession(sessionId: String) {
        if (!persistence.activateSession(sessionId)) return
        _messages.value = persistence.loadActiveMessages()
        _sessions.value = persistence.listSessions()
        openAIService.switchToSession(sessionId, persistence.getActiveLastResponseId())
    }

    fun deleteSession(sessionId: String) {
        val nextSessionId = persistence.deleteSession(sessionId)
        _messages.value = persistence.loadActiveMessages()
        _sessions.value = persistence.listSessions()
        openAIService.switchToSession(nextSessionId, persistence.getActiveLastResponseId())
    }

    suspend fun sendUserMessage(messageContent: String) {
        withContext(Dispatchers.IO) {
            var thinkingMessageId: String
            var toolMessageId: String? = null
            var firstAssistantMessageShown = false
            try {
                appendUserMessage(messageContent)
                val inputs = buildRequestInputs()
                thinkingMessageId = appendAiThinkingMessage()
                val (responseText, _) = openAIService.agentTurn(
                    inputs = inputs,
                    previousId = null,
                    agentLabel = "AI Manager",
                    onAssistantMessage = { assistantMessage ->
                        val visibleContent =
                            if (assistantMessage.isReasoning) {
                                "Reasoning\n${assistantMessage.text}"
                            } else {
                                assistantMessage.text
                            }
                        replaceMessage(
                            thinkingMessageId,
                            chatMessageFactory.createAIMessage(
                                content = visibleContent,
                                voiceSummary = assistantMessage.ttsSummary,
                            ),
                        )
                        firstAssistantMessageShown = true
                        thinkingMessageId = appendAiThinkingMessage()
                    },
                    onToolUpdate = { update ->
                        val targetId =
                            toolMessageId ?: appendAiToolMessage(
                                toolItems = emptyList(),
                                beforeMessageId = thinkingMessageId,
                            ).also { toolMessageId = it }
                        val existingItems = currentToolItems(targetId)
                        val mergedItems = mergeToolItems(existingItems, update.item)
                        replaceMessage(
                            targetId,
                            chatMessageFactory.createAIToolMessage(mergedItems),
                        )
                    },
                )
                if (!firstAssistantMessageShown) {
                    replaceMessage(
                        thinkingMessageId,
                        chatMessageFactory.createAIMessage(responseText),
                    )
                } else {
                    clearThinkingMessages()
                }
                persistMessages()
            } catch (e: Throwable) {
                if (e is CancellationException) {
                    clearThinkingMessages()
                    throw e
                }
                val errorText = buildString {
                    append("Backend error: ")
                    append(e::class.java.simpleName)
                    val message = e.message?.trim().orEmpty()
                    if (message.isNotEmpty()) {
                        append(" - ").append(message)
                    }
                    append(e.stackTrace.joinToString("\n"))
                }
                clearThinkingMessages()
                appendAiMessage(errorText)
            }
        }
    }

    private fun buildRequestInputs(): MutableList<ResponseInputItem> =
        buildList {
            buildContextMessage()?.let { contextMessage ->
                add(
                    ResponseInputItem.ofEasyInputMessage(
                        EasyInputMessage.builder()
                            .role(EasyInputMessage.Role.SYSTEM)
                            .content(contextMessage)
                            .build(),
                    ),
                )
            }
            buildHistory().forEach { turn ->
                add(
                    ResponseInputItem.ofEasyInputMessage(
                        EasyInputMessage.builder()
                            .role(if (turn.role == "user") EasyInputMessage.Role.USER else EasyInputMessage.Role.ASSISTANT)
                            .content(turn.content)
                            .build(),
                    ),
                )
            }
        }.toMutableList()

    private fun appendUserMessage(messageContent: String) {
        _messages.value += chatMessageFactory.createUserMessage(messageContent)
        persistMessages()
    }

    private fun appendAiMessage(messageContent: String) {
        _messages.value += chatMessageFactory.createAIMessage(messageContent)
        persistMessages()
    }

    private fun appendAiToolMessage(
        toolItems: List<com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolExecutionItem>,
        beforeMessageId: String? = null,
    ): String {
        val message = chatMessageFactory.createAIToolMessage(toolItems)
        _messages.value =
            if (beforeMessageId == null) {
                _messages.value + message
            } else {
                val idx = _messages.value.indexOfFirst { it.id == beforeMessageId }
                if (idx < 0) {
                    _messages.value + message
                } else {
                    buildList {
                        addAll(_messages.value.take(idx))
                        add(message)
                        addAll(_messages.value.drop(idx))
                    }
                }
            }
        persistMessages()
        return message.id
    }

    private fun currentToolItems(messageId: String): List<com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolExecutionItem> =
        _messages.value.firstOrNull { it.id == messageId }?.toolItems.orEmpty()

    private fun mergeToolItems(
        existing: List<com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolExecutionItem>,
        incoming: com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolExecutionItem,
    ): List<com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolExecutionItem> {
        val idx = existing.indexOfFirst { it.callId == incoming.callId }
        return if (idx >= 0) {
            existing.toMutableList().apply { this[idx] = incoming }
        } else {
            existing + incoming
        }
    }

    private fun appendAiThinkingMessage(): String {
        val message =
            ChatMessage(
                content = "AI is thinking…",
                author = "AI Manager",
                type = AI_THINKING,
            )
        _messages.value += message
        persistMessages()
        return message.id
    }

    private fun replaceMessage(
        messageId: String,
        newMessage: ChatMessage,
    ) {
        _messages.value =
            _messages.value.map { message ->
                if (message.id == messageId) {
                    newMessage.copy(id = messageId)
                } else {
                    message
                }
            }
        persistMessages()
    }

    private fun clearThinkingMessages() {
        _messages.value = _messages.value.filterNot { it.type == AI_THINKING }
        persistMessages()
    }

    private fun buildHistory(): List<ChatTurn> =
        _messages.value
            .filterNot { it.type == AI_THINKING }
            .map { message ->
                val role = if (message.isMyMessage) "user" else "assistant"
                ChatTurn(role = role, content = message.content)
            }

    private fun buildContextMessage(): String? {
        val ctx = runCatching { CurrentFileContextProvider(project).getCurrent() }.getOrNull() ?: return null
        val header =
            "Current file open: ${ctx.filePathRelative}, file version: ${ctx.version} - " +
                    "you must always reread file if version changed"

        return buildString {
            append(header)
            val caretLine = ctx.caretLine
            val caretCol = ctx.caretColumn
            if (caretLine != null && caretCol != null) {
                append(
                    """
                    User Caret position in the file ${ctx.filePathRelative} - Line: ${'$'}caretLine, Column (Offset): ${'$'}caretCol
                    """.trimIndent(),
                )
            }
            if (ctx.selectedText != null) {
                append("\nSelected text:\n")
                append(ctx.selectedText)
            }
        }
    }

    fun clearConversation() {
        _messages.value = emptyList()
        persistence.clearActiveSession()
        persistMessages()
    }

    fun compactConversationWithBrief(brief: String) {
        val notice =
            if (brief.isBlank()) {
                "Conversation compacted. Continuing from session memory."
            } else {
                "Conversation compacted. Continuing from session memory.\n\n${brief.take(1200)}"
            }
        _messages.value = listOf(chatMessageFactory.createAIMessage(notice))
        persistMessages()
    }

    private fun persistMessages() {
        persistence.saveActiveMessages(_messages.value, openAIService.getLastResponseId())
        _sessions.value = persistence.listSessions()
    }
}
