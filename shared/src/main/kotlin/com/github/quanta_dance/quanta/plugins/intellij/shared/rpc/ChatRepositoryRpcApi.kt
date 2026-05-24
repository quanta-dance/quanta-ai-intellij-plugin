// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.shared.rpc

import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.ChatMessageDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.ChatSessionDto
import com.intellij.platform.project.ProjectId
import com.intellij.platform.rpc.RemoteApiProviderService
import fleet.rpc.RemoteApi
import fleet.rpc.Rpc
import fleet.rpc.remoteApiDescriptor

/**
 * Shared RPC contract for chat messages and sessions.
 *
 * Frontend UI layers observe these flows to render chat state, while backend services own message
 * persistence, session activation, and OpenAI-backed turn execution.
 */
@Rpc
interface ChatRepositoryRpcApi : RemoteApi<Unit> {
    companion object {
        suspend fun getInstance(): ChatRepositoryRpcApi =
            RemoteApiProviderService.resolve(remoteApiDescriptor<ChatRepositoryRpcApi>())
    }

    suspend fun getCurrentMessages(projectId: ProjectId): List<ChatMessageDto>

    suspend fun getCurrentSessions(projectId: ProjectId): List<ChatSessionDto>

    suspend fun createNewSession(projectId: ProjectId)

    suspend fun activateSession(
        projectId: ProjectId,
        sessionId: String,
    )

    suspend fun deleteSession(
        projectId: ProjectId,
        sessionId: String,
    )

    /**
     * Sends a message with the provided content.
     *
     * @param messageContent The content of the message to be sent.
     */
    suspend fun sendMessage(
        projectId: ProjectId,
        messageContent: String,
    )

    suspend fun stopAllAgents(projectId: ProjectId): Int
}
