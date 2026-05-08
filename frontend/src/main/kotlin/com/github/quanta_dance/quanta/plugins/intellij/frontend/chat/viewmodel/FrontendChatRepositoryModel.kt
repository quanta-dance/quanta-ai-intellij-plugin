package com.github.quanta_dance.quanta.plugins.intellij.frontend.chat.viewmodel

import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ChatMessage
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.ChatRepositoryRpcApi
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.ChatSessionDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.toChatMessage
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.Service.Level
import com.intellij.openapi.project.Project
import com.intellij.platform.project.projectId
import fleet.rpc.client.durable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

@Service(Level.PROJECT)
class FrontendChatRepositoryModel(
    private val project: Project,
    coroutineScope: CoroutineScope,
) : ChatRepositoryApi {
    companion object {
        fun getInstance(project: Project): FrontendChatRepositoryModel =
            project.getService(FrontendChatRepositoryModel::class.java)
    }

    override val messagesFlow: StateFlow<List<ChatMessage>> = flow {
        durable {
            ChatRepositoryRpcApi
                .getInstance()
                .getMessagesFlow(project.projectId())
                .collect { valueFromBackend ->
                    emit(valueFromBackend.map { messageDto -> messageDto.toChatMessage() })
                }
        }
    }.stateIn(coroutineScope, initialValue = emptyList(), started = SharingStarted.Lazily)

    override val sessionsFlow: StateFlow<List<ChatSessionDto>> = flow {
        durable {
            ChatRepositoryRpcApi
                .getInstance()
                .getSessionsFlow(project.projectId())
                .collect { emit(it) }
        }
    }.stateIn(coroutineScope, initialValue = emptyList(), started = SharingStarted.Lazily)

    override suspend fun sendMessage(messageContent: String) {
        ChatRepositoryRpcApi
            .getInstance()
            .sendMessage(project.projectId(), messageContent)
    }

    override suspend fun createNewSession() {
        ChatRepositoryRpcApi.getInstance().createNewSession(project.projectId())
    }

    override suspend fun activateSession(sessionId: String) {
        ChatRepositoryRpcApi.getInstance().activateSession(project.projectId(), sessionId)
    }

    override suspend fun deleteSession(sessionId: String) {
        ChatRepositoryRpcApi.getInstance().deleteSession(project.projectId(), sessionId)
    }
}