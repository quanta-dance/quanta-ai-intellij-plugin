// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.rpc

import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.ChatRepositoryRpcApi
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.ChatMessageDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.ChatSessionDto

/**
 * Backend implementation of the shared chat repository RPC.
 *
 * It resolves the target backend project and delegates chat-session state, message flows, and send
 * operations to the backend chat repository model.
 */
class BackendChatRepositoryRpcApi : ChatRepositoryRpcApi {
    override suspend fun getCurrentMessages(projectPath: String): List<ChatMessageDto> {
        val backendProject = findBackendProject(projectPath) ?: return emptyList()
        return BackendChatRepositoryModel.getInstance(backendProject).getCurrentMessages()
    }

    override suspend fun getCurrentSessions(projectPath: String): List<ChatSessionDto> {
        val backendProject = findBackendProject(projectPath) ?: return emptyList()
        return BackendChatRepositoryModel.getInstance(backendProject).getCurrentSessions()
    }

    override suspend fun createNewSession(projectPath: String) {
        val backendProject = findBackendProject(projectPath) ?: return
        BackendChatRepositoryModel.getInstance(backendProject).createNewSession()
    }

    override suspend fun activateSession(
        projectPath: String,
        sessionId: String,
    ) {
        val backendProject = findBackendProject(projectPath) ?: return
        BackendChatRepositoryModel.getInstance(backendProject).activateSession(sessionId)
    }

    override suspend fun deleteSession(
        projectPath: String,
        sessionId: String,
    ) {
        val backendProject = findBackendProject(projectPath) ?: return
        BackendChatRepositoryModel.getInstance(backendProject).deleteSession(sessionId)
    }

    override suspend fun sendMessage(
        projectPath: String,
        messageContent: String,
    ) {
        val backendProject = findBackendProject(projectPath) ?: return
        return BackendChatRepositoryModel.getInstance(backendProject).sendMessage(messageContent)
    }

    override suspend fun stopAllAgents(projectPath: String): Int {
        val backendProject = findBackendProject(projectPath) ?: return 0
        return BackendChatRepositoryModel.getInstance(backendProject).stopAllAgents()
    }
}
