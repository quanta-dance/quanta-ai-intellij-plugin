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
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())

    fun messagesFlow(): Flow<List<ChatMessageDto>> =
        _messages.map { messagesList -> messagesList.map { it.toChatMessageDto() } }

    suspend fun sendUserMessage(messageContent: String) {
        withContext(Dispatchers.IO) {
            var thinkingMessageId: String
            var firstAssistantMessageShown = false
            try {
                appendUserMessage(messageContent)
                val inputs = buildRequestInputs()
                thinkingMessageId = appendAiThinkingMessage()
                val (responseText, _) = openAIService.agentTurn(
                    inputs = inputs,
                    previousId = null,
                    agentLabel = "AI Manager",
                    onAssistantMessage = { txt ->
                        replaceMessage(thinkingMessageId, chatMessageFactory.createAIMessage(txt))
                        firstAssistantMessageShown = true
                        thinkingMessageId = appendAiThinkingMessage()
                    },
                )
                if (!firstAssistantMessageShown) {
                    replaceMessage(thinkingMessageId, chatMessageFactory.createAIMessage(responseText))
                } else {
                    clearThinkingMessages()
                }
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
    }

    private fun appendAiMessage(messageContent: String) {
        _messages.value += chatMessageFactory.createAIMessage(messageContent)
    }

    private fun appendAiThinkingMessage(): String {
        val message =
            ChatMessage(
                content = "AI is thinking…",
                author = "AI Manager",
                type = AI_THINKING,
            )
        _messages.value += message
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
    }

    private fun clearThinkingMessages() {
        _messages.value = _messages.value.filterNot { it.type == AI_THINKING }
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
    }
}
