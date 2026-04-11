package com.github.quanta_dance.quanta.plugins.intellij.backend.rpc

import com.github.quanta_dance.quanta.plugins.intellij.backend.repository.ChatMessageFactory
import com.github.quanta_dance.quanta.plugins.intellij.backend.repository.OpenAIBackendChatResponder
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ChatMessage
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.ChatMessageDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.toChatMessageDto
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

@Service(Service.Level.PROJECT)
class BackendChatRepositoryModel {
    companion object {
        fun getInstance(project: Project): BackendChatRepositoryModel {
            return project.getService(BackendChatRepositoryModel::class.java)
        }
    }

    private val chatMessageFactory = ChatMessageFactory("Quanta AI", "AI Manager")
    private val aiResponder = OpenAIBackendChatResponder()
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())

    fun getMessagesFlow(): Flow<List<ChatMessageDto>> {
        return _messages.map { messagesList ->
            messagesList.map { message -> message.toChatMessageDto() }
        }
    }

    suspend fun sendMessage(messageContent: String) {
        withContext(Dispatchers.IO) {
            try {
                _messages.value += chatMessageFactory.createUserMessage(messageContent)
                val history = _messages.value.map { message ->
                    val role = if (message.isMyMessage) "user" else "assistant"
                    OpenAIBackendChatResponder.ChatTurn(role = role, content = message.content)
                }
                val responseText =
                    aiResponder.generateResponse(history)
                _messages.value += chatMessageFactory.createAIMessage(responseText)
            } catch (e: Exception) {
                if (e is CancellationException) {
                    throw e
                }
                _messages.value += chatMessageFactory.createAIMessage(
                    "Sorry, I couldn't reach OpenAI from the backend. Please check backend configuration."
                )
            }
        }
    }
}