package com.github.quanta_dance.quanta.plugins.intellij.backend.chat

import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.*
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.*

@Service(Service.Level.PROJECT)
class AgentChannelStateService(
    private val project: Project,
) {
    private val persistence: ChatConversationStateService = project.getService(ChatConversationStateService::class.java)

    private val _events = MutableStateFlow(persistence.loadActiveChannelEvents())
    val eventsFlow: StateFlow<List<AgentChannelEventDto>> = _events.asStateFlow()

    private val _tasks = MutableStateFlow(persistence.loadActiveDelegatedTasks())
    val tasksFlow: StateFlow<List<DelegatedTaskDto>> = _tasks.asStateFlow()

    fun reloadFromPersistence() {
        _events.value = persistence.loadActiveChannelEvents()
        _tasks.value = persistence.loadActiveDelegatedTasks()
    }

    fun appendEvent(
        kind: AgentChannelEventKindDto,
        authorType: AgentChannelAuthorTypeDto,
        text: String,
        authorId: String? = null,
        authorRole: String? = null,
        visibility: AgentChannelVisibilityDto = AgentChannelVisibilityDto.CHANNEL,
        relatedTaskId: String? = null,
        relatedMessageId: String? = null,
        threadId: String? = null,
    ): AgentChannelEventDto {
        val event = AgentChannelEventDto(
            id = UUID.randomUUID().toString(),
            sessionId = persistence.getActiveSessionId(),
            threadId = threadId,
            parentEventId = null,
            relatedTaskId = relatedTaskId,
            relatedMessageId = relatedMessageId,
            kind = kind,
            authorType = authorType,
            authorId = authorId,
            authorRole = authorRole,
            visibility = visibility,
            text = text,
            createdAtEpochMs = System.currentTimeMillis(),
        )
        _events.value = _events.value + event
        persistence.saveActiveChannelEvents(_events.value)
        return event
    }

    fun upsertTask(task: DelegatedTaskDto): DelegatedTaskDto {
        val updated = task.copy(updatedAtEpochMs = System.currentTimeMillis())
        val idx = _tasks.value.indexOfFirst { it.id == updated.id }
        _tasks.value = if (idx >= 0) {
            _tasks.value.toMutableList().apply { this[idx] = updated }
        } else {
            _tasks.value + updated
        }
        persistence.saveActiveDelegatedTasks(_tasks.value)
        return updated
    }

    fun createTask(
        title: String,
        assignedAgentIds: List<String>,
        assignedRoles: List<String>,
        createdByRole: String = "Manager",
        relatedMessageId: String? = null,
        dependsOnTaskIds: List<String> = emptyList(),
    ): DelegatedTaskDto {
        val now = System.currentTimeMillis()
        val initialStatus =
            if (areDependenciesSatisfied(dependsOnTaskIds)) DelegatedTaskStatusDto.QUEUED else DelegatedTaskStatusDto.BLOCKED
        val task = DelegatedTaskDto(
            id = UUID.randomUUID().toString(),
            title = title,
            createdByRole = createdByRole,
            assignedAgentIds = assignedAgentIds,
            assignedRoles = assignedRoles,
            dependsOnTaskIds = dependsOnTaskIds,
            status = initialStatus,
            relatedMessageId = relatedMessageId,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
        )
        return upsertTask(task)
    }

    fun areDependenciesSatisfied(dependsOnTaskIds: List<String>): Boolean =
        dependsOnTaskIds.all { depId ->
            _tasks.value.firstOrNull { it.id == depId }?.status == DelegatedTaskStatusDto.DONE
        }

    fun readyQueuedTasks(): List<DelegatedTaskDto> =
        _tasks.value.filter { task ->
            task.status == DelegatedTaskStatusDto.QUEUED && areDependenciesSatisfied(task.dependsOnTaskIds)
        }

    fun updateTaskStatus(
        taskId: String,
        status: DelegatedTaskStatusDto,
        summary: String? = null,
        result: String? = null,
    ) {
        val current = _tasks.value.firstOrNull { it.id == taskId } ?: return
        upsertTask(
            current.copy(
                status = status,
                summary = summary ?: current.summary,
                result = result ?: current.result,
            )
        )
        if (status == DelegatedTaskStatusDto.DONE) {
            promoteSatisfiedBlockedTasks()
        }
    }

    private fun promoteSatisfiedBlockedTasks() {
        _tasks.value = _tasks.value.map { task ->
            if (task.status == DelegatedTaskStatusDto.BLOCKED && areDependenciesSatisfied(task.dependsOnTaskIds)) {
                task.copy(
                    status = DelegatedTaskStatusDto.QUEUED,
                    summary = "Dependencies satisfied",
                    updatedAtEpochMs = System.currentTimeMillis(),
                )
            } else {
                task
            }
        }
        persistence.saveActiveDelegatedTasks(_tasks.value)
    }
}
