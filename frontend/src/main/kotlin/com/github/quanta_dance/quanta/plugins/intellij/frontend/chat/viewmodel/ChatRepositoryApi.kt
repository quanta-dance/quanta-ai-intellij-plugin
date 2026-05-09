package com.github.quanta_dance.quanta.plugins.intellij.frontend.chat.viewmodel

import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ChatMessage
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.ChatPlanStatusDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.ChatSessionDto
import kotlinx.coroutines.flow.StateFlow

interface ChatRepositoryApi {
    val messagesFlow: StateFlow<List<ChatMessage>>
    val sessionsFlow: StateFlow<List<ChatSessionDto>>
    val planStatusFlow: StateFlow<ChatPlanStatusDto>

    suspend fun sendMessage(messageContent: String)
    suspend fun createNewSession()
    suspend fun activateSession(sessionId: String)
    suspend fun deleteSession(sessionId: String)
}