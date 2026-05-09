package com.github.quanta_dance.quanta.plugins.intellij.backend.chat

import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ChatMessage
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolExecutionItem
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolExecutionStatus
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.ChatSessionDto
import com.intellij.openapi.components.*
import java.time.LocalDateTime
import java.util.*

@Service(Service.Level.PROJECT)
@State(
    name = "QuantaChatConversationState",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE)],
)
class ChatConversationStateService : PersistentStateComponent<ChatConversationStateService.State> {
    data class PersistedToolItem(
        var callId: String = "",
        var toolName: String = "",
        var displayText: String = "",
        var status: String = ToolExecutionStatus.EXECUTING.name,
        var filePath: String? = null,
        var errorText: String? = null,
        var detailText: String? = null,
    )

    data class PersistedChatMessage(
        var id: String = "",
        var content: String = "",
        var author: String = "",
        var isMyMessage: Boolean = false,
        var timestamp: String = "",
        var type: String = ChatMessage.ChatMessageType.TEXT.name,
        var voiceSummary: String? = null,
        var toolItems: MutableList<PersistedToolItem> = mutableListOf(),
    )

    data class PersistedChatSession(
        var id: String = UUID.randomUUID().toString(),
        var title: String = "New Chat",
        var updatedAtEpochMs: Long = System.currentTimeMillis(),
        var lastResponseId: String? = null,
        var messages: MutableList<PersistedChatMessage> = mutableListOf(),
    )

    data class State(
        var activeSessionId: String? = null,
        var sessions: MutableList<PersistedChatSession> = mutableListOf(),
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
        ensureSessionExists()
    }

    fun ensureSessionExists(): String {
        if (state.sessions.isEmpty()) {
            val session = PersistedChatSession(
                title = "Chat 1",
                updatedAtEpochMs = System.currentTimeMillis(),
            )
            state.sessions.add(session)
            state.activeSessionId = session.id
        }
        if (state.activeSessionId == null || state.sessions.none { it.id == state.activeSessionId }) {
            state.activeSessionId = state.sessions.first().id
        }
        return state.activeSessionId!!
    }

    fun getActiveSessionId(): String = ensureSessionExists()

    fun listSessions(): List<ChatSessionDto> {
        ensureSessionExists()
        val activeId = state.activeSessionId
        return state.sessions
            .sortedByDescending { it.updatedAtEpochMs }
            .map {
                ChatSessionDto(
                    id = it.id,
                    title = it.title,
                    updatedAtEpochMs = it.updatedAtEpochMs,
                    isActive = it.id == activeId,
                )
            }
    }

    fun createSession(title: String? = null): String {
        val nextIndex = state.sessions.size + 1
        val session = PersistedChatSession(
            title = title?.ifBlank { null } ?: "Chat $nextIndex",
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        state.sessions.add(0, session)
        state.activeSessionId = session.id
        return session.id
    }

    fun activateSession(sessionId: String): Boolean {
        ensureSessionExists()
        if (state.sessions.none { it.id == sessionId }) return false
        state.activeSessionId = sessionId
        return true
    }

    fun deleteSession(sessionId: String): String {
        ensureSessionExists()
        state.sessions.removeAll { it.id == sessionId }
        if (state.sessions.isEmpty()) {
            val newId = createSession()
            return newId
        }
        if (state.activeSessionId == sessionId) {
            state.activeSessionId = state.sessions.first().id
        }
        return ensureSessionExists()
    }

    fun loadActiveMessages(): List<ChatMessage> =
        getActiveSession()?.messages.orEmpty().mapNotNull { saved ->
            runCatching {
                ChatMessage(
                    id = saved.id,
                    content = saved.content,
                    author = saved.author,
                    isMyMessage = saved.isMyMessage,
                    timestamp = LocalDateTime.parse(saved.timestamp),
                    type = ChatMessage.ChatMessageType.valueOf(saved.type),
                    voiceSummary = saved.voiceSummary,
                    toolItems =
                        saved.toolItems.map { item ->
                            ToolExecutionItem(
                                callId = item.callId,
                                toolName = item.toolName,
                                displayText = item.displayText,
                                status = ToolExecutionStatus.valueOf(item.status),
                                filePath = item.filePath,
                                errorText = item.errorText,
                                detailText = item.detailText,
                            )
                        },
                )
            }.getOrNull()
        }

    fun getActiveLastResponseId(): String? = getActiveSession()?.lastResponseId

    fun saveActiveMessages(
        messages: List<ChatMessage>,
        lastResponseId: String?,
    ) {
        val session = getOrCreateActiveSession()
        session.messages =
            messages.map { message ->
                PersistedChatMessage(
                    id = message.id,
                    content = message.content,
                    author = message.author,
                    isMyMessage = message.isMyMessage,
                    timestamp = message.timestamp.toString(),
                    type = message.type.name,
                    voiceSummary = message.voiceSummary,
                    toolItems =
                        message.toolItems.map { item ->
                            PersistedToolItem(
                                callId = item.callId,
                                toolName = item.toolName,
                                displayText = item.displayText,
                                status = item.status.name,
                                filePath = item.filePath,
                                errorText = item.errorText,
                                detailText = item.detailText,
                            )
                        }.toMutableList(),
                )
            }.toMutableList()
        session.lastResponseId = lastResponseId
        session.updatedAtEpochMs = System.currentTimeMillis()
        if (session.title.startsWith("Chat ")) {
            val firstUser = messages.firstOrNull { it.isMyMessage }?.content?.trim().orEmpty()
            if (firstUser.isNotBlank()) {
                session.title = firstUser.take(40)
            }
        }
    }

    fun clearActiveSession() {
        val session = getOrCreateActiveSession()
        session.messages.clear()
        session.lastResponseId = null
        session.updatedAtEpochMs = System.currentTimeMillis()
    }

    private fun getActiveSession(): PersistedChatSession? {
        val id = ensureSessionExists()
        return state.sessions.firstOrNull { it.id == id }
    }

    private fun getOrCreateActiveSession(): PersistedChatSession {
        return getActiveSession() ?: run {
            val id = createSession()
            state.sessions.first { it.id == id }
        }
    }
}
