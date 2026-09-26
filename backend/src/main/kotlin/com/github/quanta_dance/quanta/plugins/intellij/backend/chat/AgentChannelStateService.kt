// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.chat

import com.github.quanta_dance.quanta.plugins.intellij.backend.services.AgentManagerService
import com.github.quanta_dance.quanta.plugins.intellij.backend.services.BackendExecutionContextsService
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.AgentChannelAuthorTypeDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.AgentChannelEventDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.AgentChannelEventKindDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.AgentChannelVisibilityDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.DelegatedTaskDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.DelegatedTaskStatusDto
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@Service(Service.Level.PROJECT)
class AgentChannelStateService(
    private val project: Project,
) {
    @Suppress("ktlint:standard:backing-property-naming")
    private val persistence: ChatConversationStateService = project.getService(ChatConversationStateService::class.java)
    private val executionContexts: BackendExecutionContextsService =
        project.getService(BackendExecutionContextsService::class.java)
    private val runningTaskIds = Collections.synchronizedSet(mutableSetOf<String>())
    private val eventPersistenceScheduled = AtomicBoolean(false)
    private val taskPersistenceScheduled = AtomicBoolean(false)
    private val eventRevision = AtomicLong(0)
    private val taskRevision = AtomicLong(0)
    private val persistedEventRevision = AtomicLong(0)
    private val persistedTaskRevision = AtomicLong(0)

    @Suppress("ktlint:standard:backing-property-naming")
    private val _events = MutableStateFlow(persistence.loadActiveChannelEvents())
    val eventsFlow: StateFlow<List<AgentChannelEventDto>> = _events.asStateFlow()

    @Suppress("ktlint:standard:backing-property-naming")
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
        val event =
            AgentChannelEventDto(
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
        scheduleEventPersistence()
        return event
    }

    fun upsertTask(task: DelegatedTaskDto): DelegatedTaskDto {
        val updated = task.copy(updatedAtEpochMs = System.currentTimeMillis())
        val idx = _tasks.value.indexOfFirst { it.id == updated.id }
        _tasks.value =
            if (idx >= 0) {
                _tasks.value.toMutableList().apply { this[idx] = updated }
            } else {
                _tasks.value + updated
            }
        scheduleTaskPersistence()
        return updated
    }

    fun createTask(
        title: String,
        requestText: String,
        assignedAgentIds: List<String>,
        assignedRoles: List<String>,
        createdByRole: String = "Manager",
        relatedMessageId: String? = null,
        dependsOnTaskIds: List<String> = emptyList(),
        autoStart: Boolean = true,
    ): DelegatedTaskDto {
        val now = System.currentTimeMillis()
        val initialStatus =
            if (areDependenciesSatisfied(dependsOnTaskIds)) DelegatedTaskStatusDto.QUEUED else DelegatedTaskStatusDto.BLOCKED
        val task =
            DelegatedTaskDto(
                id = UUID.randomUUID().toString(),
                title = title,
                requestText = requestText,
                createdByRole = createdByRole,
                assignedAgentIds = assignedAgentIds,
                assignedRoles = assignedRoles,
                dependsOnTaskIds = dependsOnTaskIds,
                status = initialStatus,
                relatedMessageId = relatedMessageId,
                createdAtEpochMs = now,
                updatedAtEpochMs = now,
            )
        return upsertTask(task).also {
            if (autoStart && it.status == DelegatedTaskStatusDto.QUEUED) {
                triggerReadyTasks()
            }
        }
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
            ),
        )
        if (status == DelegatedTaskStatusDto.DONE) {
            promoteSatisfiedBlockedTasks()
            triggerReadyTasks()
        }
    }

    private fun promoteSatisfiedBlockedTasks() {
        _tasks.value =
            _tasks.value.map { task ->
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
        scheduleTaskPersistence()
    }

    fun triggerReadyTasks() {
        val manager = project.service<AgentManagerService>()
        readyQueuedTasks().forEach { task ->
            val agentId = task.assignedAgentIds.firstOrNull() ?: return@forEach
            if (!runningTaskIds.add(task.id)) return@forEach
            updateTaskStatus(task.id, DelegatedTaskStatusDto.RUNNING, summary = "Started")
            appendEvent(
                kind = AgentChannelEventKindDto.DELEGATION_UPDATED,
                authorType = AgentChannelAuthorTypeDto.MANAGER,
                text = "Auto-running task: ${task.title}",
                relatedTaskId = task.id,
            )
            manager.sendMessageAsync(agentId, task.requestText, task.id).whenComplete { _, _ ->
                runningTaskIds.remove(task.id)
                executionContexts.backgroundAgentScope.launch {
                    triggerReadyTasks()
                }
            }
        }
    }

    fun stopAllAgentWork(reason: String = "Stopped by user"): Int {
        val stoppableStatuses =
            setOf(DelegatedTaskStatusDto.RUNNING, DelegatedTaskStatusDto.QUEUED, DelegatedTaskStatusDto.BLOCKED)
        val affected = _tasks.value.filter { it.status in stoppableStatuses }
        if (affected.isEmpty()) return 0

        val now = System.currentTimeMillis()
        val stoppedIds = affected.map { it.id }.toSet()
        runningTaskIds.removeAll(stoppedIds)
        _tasks.value =
            _tasks.value.map { task ->
                if (task.id in stoppedIds) {
                    task.copy(
                        status = DelegatedTaskStatusDto.FAILED,
                        summary = reason,
                        result = reason,
                        updatedAtEpochMs = now,
                    )
                } else {
                    task
                }
            }
        scheduleTaskPersistence()
        appendEvent(
            kind = AgentChannelEventKindDto.DELEGATION_UPDATED,
            authorType = AgentChannelAuthorTypeDto.SYSTEM,
            text = reason,
        )
        return stoppedIds.size
    }

    private fun scheduleEventPersistence() {
        eventRevision.incrementAndGet()
        if (!eventPersistenceScheduled.compareAndSet(false, true)) return
        executionContexts.chatPublicationScope.launch {
            try {
                delay(STATE_PERSIST_DEBOUNCE_MS)
                while (true) {
                    val revision = eventRevision.get()
                    persistence.saveActiveChannelEvents(_events.value)
                    persistedEventRevision.set(revision)
                    if (eventRevision.get() == revision) break
                }
            } finally {
                eventPersistenceScheduled.set(false)
                if (eventRevision.get() != persistedEventRevision.get()) scheduleEventPersistence()
            }
        }
    }

    private fun scheduleTaskPersistence() {
        taskRevision.incrementAndGet()
        if (!taskPersistenceScheduled.compareAndSet(false, true)) return
        executionContexts.chatPublicationScope.launch {
            try {
                delay(STATE_PERSIST_DEBOUNCE_MS)
                while (true) {
                    val revision = taskRevision.get()
                    persistence.saveActiveDelegatedTasks(_tasks.value)
                    persistedTaskRevision.set(revision)
                    if (taskRevision.get() == revision) break
                }
            } finally {
                taskPersistenceScheduled.set(false)
                if (taskRevision.get() != persistedTaskRevision.get()) scheduleTaskPersistence()
            }
        }
    }

    private companion object {
        const val STATE_PERSIST_DEBOUNCE_MS = 250L
    }
}
