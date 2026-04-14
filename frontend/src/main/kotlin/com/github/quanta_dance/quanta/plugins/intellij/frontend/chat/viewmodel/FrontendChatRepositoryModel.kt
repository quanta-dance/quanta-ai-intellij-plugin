package com.github.quanta_dance.quanta.plugins.intellij.frontend.chat.viewmodel

import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ChatMessage
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
            com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.ChatRepositoryRpcApi
                .getInstance()
                .getMessagesFlow(project.projectId())
                .collect { valueFromBackend ->
                    emit(valueFromBackend.map { messageDto -> messageDto.toChatMessage() })
                }
        }
    }.stateIn(coroutineScope, initialValue = emptyList(), started = SharingStarted.Lazily)

    override suspend fun sendMessage(messageContent: String) {
        com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.ChatRepositoryRpcApi
            .getInstance()
            .sendMessage(project.projectId(), messageContent)
    }
}
