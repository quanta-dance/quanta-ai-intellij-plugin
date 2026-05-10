package com.github.quanta_dance.quanta.plugins.intellij.backend.chat

import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ChatMessage
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolExecutionItem
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolExecutionStatus
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.*
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
        var parentMessageId: String? = null,
    )

    data class PersistedDelegatedTask(
        var id: String = "",
        var title: String = "",
        var requestText: String = "",
        var createdByAgentId: String? = null,
        var createdByRole: String? = null,
        var assignedAgentIds: MutableList<String> = mutableListOf(),
        var assignedRoles: MutableList<String> = mutableListOf(),
        var dependsOnTaskIds: MutableList<String> = mutableListOf(),
        var status: String = DelegatedTaskStatusDto.QUEUED.name,
        var summary: String? = null,
        var result: String? = null,
        var relatedMessageId: String? = null,
        var relatedPlanTask: String? = null,
        var createdAtEpochMs: Long = 0,
        var updatedAtEpochMs: Long = 0,
    )

    data class PersistedChannelEvent(
        var id: String = "",
        var sessionId: String = "",
        var threadId: String? = null,
        var parentEventId: String? = null,
        var relatedTaskId: String? = null,
        var relatedMessageId: String? = null,
        var kind: String = AgentChannelEventKindDto.MANAGER_MESSAGE.name,
        var authorType: String = AgentChannelAuthorTypeDto.MANAGER.name,
        var authorId: String? = null,
        var authorRole: String? = null,
        var visibility: String = AgentChannelVisibilityDto.CHANNEL.name,
        var text: String = "",
        var toolItems: MutableList<PersistedToolItem> = mutableListOf(),
        var status: String? = null,
        var createdAtEpochMs: Long = 0,
    )

    data class PersistedChatSession(
        var id: String = UUID.randomUUID().toString(),
        var title: String = "New Chat",
        var updatedAtEpochMs: Long = System.currentTimeMillis(),
        var lastResponseId: String? = null,
        var messages: MutableList<PersistedChatMessage> = mutableListOf(),
        var delegatedTasks: MutableList<PersistedDelegatedTask> = mutableListOf(),
        var channelEvents: MutableList<PersistedChannelEvent> = mutableListOf(),
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
                    parentMessageId = saved.parentMessageId,
                )
            }.getOrNull()
        }

    fun getActiveLastResponseId(): String? = getActiveSession()?.lastResponseId

    fun loadActiveDelegatedTasks(): List<DelegatedTaskDto> =
        getActiveSession()?.delegatedTasks.orEmpty().map { saved ->
            DelegatedTaskDto(
                id = saved.id,
                title = saved.title,
                requestText = saved.requestText,
                createdByAgentId = saved.createdByAgentId,
                createdByRole = saved.createdByRole,
                assignedAgentIds = saved.assignedAgentIds,
                assignedRoles = saved.assignedRoles,
                dependsOnTaskIds = saved.dependsOnTaskIds,
                status = runCatching { DelegatedTaskStatusDto.valueOf(saved.status) }.getOrDefault(
                    DelegatedTaskStatusDto.QUEUED
                ),
                summary = saved.summary,
                result = saved.result,
                relatedMessageId = saved.relatedMessageId,
                relatedPlanTask = saved.relatedPlanTask,
                createdAtEpochMs = saved.createdAtEpochMs,
                updatedAtEpochMs = saved.updatedAtEpochMs,
            )
        }

    fun saveActiveDelegatedTasks(tasks: List<DelegatedTaskDto>) {
        val session = getOrCreateActiveSession()
        session.delegatedTasks = tasks.map { task ->
            PersistedDelegatedTask(
                id = task.id,
                title = task.title,
                requestText = task.requestText,
                createdByAgentId = task.createdByAgentId,
                createdByRole = task.createdByRole,
                assignedAgentIds = task.assignedAgentIds.toMutableList(),
                assignedRoles = task.assignedRoles.toMutableList(),
                dependsOnTaskIds = task.dependsOnTaskIds.toMutableList(),
                status = task.status.name,
                summary = task.summary,
                result = task.result,
                relatedMessageId = task.relatedMessageId,
                relatedPlanTask = task.relatedPlanTask,
                createdAtEpochMs = task.createdAtEpochMs,
                updatedAtEpochMs = task.updatedAtEpochMs,
            )
        }.toMutableList()
        session.updatedAtEpochMs = System.currentTimeMillis()
    }

    fun loadActiveChannelEvents(): List<AgentChannelEventDto> =
        getActiveSession()?.channelEvents.orEmpty().map { saved ->
            AgentChannelEventDto(
                id = saved.id,
                sessionId = saved.sessionId,
                threadId = saved.threadId,
                parentEventId = saved.parentEventId,
                relatedTaskId = saved.relatedTaskId,
                relatedMessageId = saved.relatedMessageId,
                kind = runCatching { AgentChannelEventKindDto.valueOf(saved.kind) }.getOrDefault(
                    AgentChannelEventKindDto.MANAGER_MESSAGE
                ),
                authorType = runCatching { AgentChannelAuthorTypeDto.valueOf(saved.authorType) }.getOrDefault(
                    AgentChannelAuthorTypeDto.MANAGER
                ),
                authorId = saved.authorId,
                authorRole = saved.authorRole,
                visibility = runCatching { AgentChannelVisibilityDto.valueOf(saved.visibility) }.getOrDefault(
                    AgentChannelVisibilityDto.CHANNEL
                ),
                text = saved.text,
                toolItems = saved.toolItems.map { item ->
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
                status = saved.status?.let { runCatching { DelegatedTaskStatusDto.valueOf(it) }.getOrNull() },
                createdAtEpochMs = saved.createdAtEpochMs,
            )
        }

    fun saveActiveChannelEvents(events: List<AgentChannelEventDto>) {
        val session = getOrCreateActiveSession()
        session.channelEvents = events.map { event ->
            PersistedChannelEvent(
                id = event.id,
                sessionId = event.sessionId,
                threadId = event.threadId,
                parentEventId = event.parentEventId,
                relatedTaskId = event.relatedTaskId,
                relatedMessageId = event.relatedMessageId,
                kind = event.kind.name,
                authorType = event.authorType.name,
                authorId = event.authorId,
                authorRole = event.authorRole,
                visibility = event.visibility.name,
                text = event.text,
                toolItems = event.toolItems.map { item ->
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
                status = event.status?.name,
                createdAtEpochMs = event.createdAtEpochMs,
            )
        }.toMutableList()
        session.updatedAtEpochMs = System.currentTimeMillis()
    }

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
                    parentMessageId = message.parentMessageId,
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
