// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.shared.rpc

import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.ChatMessageDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.ChatSessionDto
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
        suspend fun getInstance(): ChatRepositoryRpcApi = RemoteApiProviderService.resolve(remoteApiDescriptor<ChatRepositoryRpcApi>())
    }

    suspend fun getCurrentMessages(projectPath: String): List<ChatMessageDto>

    suspend fun getCurrentSessions(projectPath: String): List<ChatSessionDto>

    suspend fun createNewSession(projectPath: String)

    suspend fun activateSession(
        projectPath: String,
        sessionId: String,
    )

    suspend fun deleteSession(
        projectPath: String,
        sessionId: String,
    )

    suspend fun getAllowedAcpAgentIds(projectPath: String): List<String>

    suspend fun setAcpAgentAllowed(
        projectPath: String,
        agentId: String,
        allowed: Boolean,
    )

    /** Returns MCP server names explicitly disabled for the active chat; all other configured servers are enabled. */
    suspend fun getDisabledMcpServerNames(projectPath: String): List<String>

    suspend fun setMcpServerEnabled(
        projectPath: String,
        serverName: String,
        enabled: Boolean,
    )

    /**
     * Sends a message with the provided content.
     *
     * @param messageContent The content of the message to be sent.
     */
    suspend fun sendMessage(
        projectPath: String,
        messageContent: String,
    )

    suspend fun stopAllAgents(projectPath: String): Int
}
