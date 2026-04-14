// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.chat

import com.github.quanta_dance.quanta.plugins.intellij.backend.chat.agents.AgentInboxService
import com.github.quanta_dance.quanta.plugins.intellij.backend.chat.agents.AgentLifecycleService
import com.github.quanta_dance.quanta.plugins.intellij.backend.chat.agents.AgentRegistryService
import com.github.quanta_dance.quanta.plugins.intellij.backend.chat.agents.AgentWakeService
import com.github.quanta_dance.quanta.plugins.intellij.backend.repository.ChatMessageFactory
import com.github.quanta_dance.quanta.plugins.intellij.backend.repository.OpenAIBackendChatResponder
import com.github.quanta_dance.quanta.plugins.intellij.project.CurrentFileContextProvider
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ChatMessage
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.ChatMessageDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.toChatMessageDto
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
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
    private val registry: AgentRegistryService get() = project.service()
    private val inbox: AgentInboxService get() = project.service()
    private val lifecycle: AgentLifecycleService get() = project.service()
    private val wake: AgentWakeService get() = project.service()
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())

    fun messagesFlow(): Flow<List<ChatMessageDto>> =
        _messages.map { messagesList -> messagesList.map { it.toChatMessageDto() } }

    suspend fun sendUserMessage(messageContent: String) {
        withContext(Dispatchers.IO) {
            try {
                _messages.value += chatMessageFactory.createUserMessage(messageContent)
                val history = _messages.value.map { message ->
                    val role = if (message.isMyMessage) "user" else "assistant"
                    OpenAIBackendChatResponder.ChatTurn(role = role, content = message.content)
                }
                val contextMessage = buildContextMessage()
                val responseText =
                    openAIBackendChatResponder.generateResponse(
                        messages = history,
                        contextMessage = contextMessage,
                    )
                _messages.value += chatMessageFactory.createAIMessage(responseText)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _messages.value += chatMessageFactory.createAIMessage(
                    "Sorry, I couldn't reach OpenAI from the backend. Please check backend configuration."
                )
            }
        }
    }

    private fun buildContextMessage(): String? {
        val ctx = runCatching { CurrentFileContextProvider(project).getCurrent() }.getOrNull() ?: return null
        return buildString {
            append("Current IDE context:\n")
            append("- filePath: ").append(ctx.filePathRelative).append('\n')
            append("- version: ").append(ctx.version).append('\n')
            if (ctx.caretLine != null) {
                append("- caret: line ").append(ctx.caretLine).append(", column ").append(ctx.caretColumn ?: 0)
                    .append('\n')
            }
            if (!ctx.selectedText.isNullOrBlank()) {
                append("- selection: lines ")
                    .append(ctx.selectionStartLine ?: 0)
                    .append(':')
                    .append(ctx.selectionStartColumn ?: 0)
                    .append(" to ")
                    .append(ctx.selectionEndLine ?: 0)
                    .append(':')
                    .append(ctx.selectionEndColumn ?: 0)
                    .append('\n')
                append("- selectedText:\n")
                append(ctx.selectedText)
            }
        }
    }
}
