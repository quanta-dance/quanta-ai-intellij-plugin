// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.rpc

import com.github.quanta_dance.quanta.plugins.intellij.backend.chat.ChatConversationService
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.ChatMessageDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.ChatSessionDto
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.flow.Flow

/**
 * Backend-side model adapter that exposes conversation flows to RPC implementations.
 *
 * It wraps [ChatConversationService] in a simpler shape for the RPC layer and other backend entry
 * points that only need session/message access.
 */
@Service(Service.Level.PROJECT)
class BackendChatRepositoryModel(
    private val project: Project,
) {
    companion object {
        fun getInstance(project: Project): BackendChatRepositoryModel = project.getService(BackendChatRepositoryModel::class.java)
    }

    private val conversationService = project.getService(ChatConversationService::class.java)

    fun getMessagesFlow(): Flow<List<ChatMessageDto>> = conversationService.messagesFlow()

    fun getCurrentMessages(): List<ChatMessageDto> = conversationService.currentMessages()

    fun getSessionsFlow(): Flow<List<ChatSessionDto>> = conversationService.sessionsFlow()

    fun getCurrentSessions(): List<ChatSessionDto> = conversationService.currentSessions()

    fun createNewSession() = conversationService.createNewSession()

    fun activateSession(sessionId: String) = conversationService.activateSession(sessionId)

    fun deleteSession(sessionId: String) = conversationService.deleteSession(sessionId)

    suspend fun sendMessage(messageContent: String) = conversationService.sendUserMessage(messageContent)

    suspend fun sendScheduledReminder(reminderContext: String) = conversationService.sendScheduledReminder(reminderContext)

    fun stopAllAgents(): Int = conversationService.stopAllAgents()
}
