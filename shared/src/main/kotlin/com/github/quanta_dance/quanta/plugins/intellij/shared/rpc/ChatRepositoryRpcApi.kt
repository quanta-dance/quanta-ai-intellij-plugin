package com.github.quanta_dance.quanta.plugins.intellij.shared.rpc

import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.ChatMessageDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.ChatSessionDto
import com.intellij.platform.project.ProjectId
import com.intellij.platform.rpc.RemoteApiProviderService
import fleet.rpc.RemoteApi
import fleet.rpc.Rpc
import fleet.rpc.remoteApiDescriptor
import kotlinx.coroutines.flow.Flow

/**
 * Shared RPC contract for chat messages and sessions.
 *
 * Frontend UI layers observe these flows to render chat state, while backend services own message
 * persistence, session activation, and OpenAI-backed turn execution.
 */
@Rpc
interface ChatRepositoryRpcApi : RemoteApi<Unit> {
    companion object {
        suspend fun getInstance(): ChatRepositoryRpcApi {
            return RemoteApiProviderService.resolve(remoteApiDescriptor<ChatRepositoryRpcApi>())
        }
    }

    /**
     * Flow that emits a list of chat messages.
     * Updates with new messages as they are received or edited.
     */
    suspend fun getMessagesFlow(projectId: ProjectId): Flow<List<ChatMessageDto>>

    suspend fun getSessionsFlow(projectId: ProjectId): Flow<List<ChatSessionDto>>

    suspend fun createNewSession(projectId: ProjectId)

    suspend fun activateSession(projectId: ProjectId, sessionId: String)

    suspend fun deleteSession(projectId: ProjectId, sessionId: String)

    /**
     * Sends a message with the provided content.
     *
     * @param messageContent The content of the message to be sent.
     */
    suspend fun sendMessage(projectId: ProjectId, messageContent: String)
}