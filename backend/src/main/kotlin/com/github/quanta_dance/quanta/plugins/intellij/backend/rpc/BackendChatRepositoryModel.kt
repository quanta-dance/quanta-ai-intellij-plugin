package com.github.quanta_dance.quanta.plugins.intellij.backend.rpc

import com.github.quanta_dance.quanta.plugins.intellij.backend.chat.ChatConversationService
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.ChatMessageDto
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.flow.Flow

@Service(Service.Level.PROJECT)
class BackendChatRepositoryModel(
    private val project: Project,
) {
    companion object {
        fun getInstance(project: Project): BackendChatRepositoryModel =
            project.getService(BackendChatRepositoryModel::class.java)
    }

    private val conversationService = project.getService(ChatConversationService::class.java)

    fun getMessagesFlow(): Flow<List<ChatMessageDto>> = conversationService.messagesFlow()

    suspend fun sendMessage(messageContent: String) = conversationService.sendUserMessage(messageContent)
}