// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.rpc

import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.ChatRepositoryRpcApi
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.ChatMessageDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.ChatSessionDto
import com.intellij.platform.project.ProjectId
import com.intellij.platform.project.findProjectOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Backend implementation of the shared chat repository RPC.
 *
 * It resolves the target backend project and delegates chat-session state, message flows, and send
 * operations to the backend chat repository model.
 */
class BackendChatRepositoryRpcApi : ChatRepositoryRpcApi {
    override suspend fun getMessagesFlow(projectId: ProjectId): Flow<List<ChatMessageDto>> {
        val backendProject = projectId.findProjectOrNull() ?: return emptyFlow()
        return BackendChatRepositoryModel.getInstance(backendProject).getMessagesFlow()
    }

    override suspend fun getCurrentMessages(projectId: ProjectId): List<ChatMessageDto> {
        val backendProject = projectId.findProjectOrNull() ?: return emptyList()
        return BackendChatRepositoryModel.getInstance(backendProject).getCurrentMessages()
    }

    override suspend fun getSessionsFlow(projectId: ProjectId): Flow<List<ChatSessionDto>> {
        val backendProject = projectId.findProjectOrNull() ?: return emptyFlow()
        return BackendChatRepositoryModel.getInstance(backendProject).getSessionsFlow()
    }

    override suspend fun getCurrentSessions(projectId: ProjectId): List<ChatSessionDto> {
        val backendProject = projectId.findProjectOrNull() ?: return emptyList()
        return BackendChatRepositoryModel.getInstance(backendProject).getCurrentSessions()
    }

    override suspend fun createNewSession(projectId: ProjectId) {
        val backendProject = projectId.findProjectOrNull() ?: return
        BackendChatRepositoryModel.getInstance(backendProject).createNewSession()
    }

    override suspend fun activateSession(
        projectId: ProjectId,
        sessionId: String,
    ) {
        val backendProject = projectId.findProjectOrNull() ?: return
        BackendChatRepositoryModel.getInstance(backendProject).activateSession(sessionId)
    }

    override suspend fun deleteSession(
        projectId: ProjectId,
        sessionId: String,
    ) {
        val backendProject = projectId.findProjectOrNull() ?: return
        BackendChatRepositoryModel.getInstance(backendProject).deleteSession(sessionId)
    }

    override suspend fun sendMessage(
        projectId: ProjectId,
        messageContent: String,
    ) {
        val backendProject = projectId.findProjectOrNull() ?: return
        return BackendChatRepositoryModel.getInstance(backendProject).sendMessage(messageContent)
    }

    override suspend fun stopAllAgents(projectId: ProjectId): Int {
        val backendProject = projectId.findProjectOrNull() ?: return 0
        return BackendChatRepositoryModel.getInstance(backendProject).stopAllAgents()
    }
}
