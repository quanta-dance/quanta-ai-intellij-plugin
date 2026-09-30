// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.chat

import com.github.quanta_dance.quanta.plugins.intellij.backend.services.CollaborationRouterService
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.CollaborationTaskStatusDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.CollaborationTaskUpdateDto
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.beans.PropertyChangeListener
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Coordinates one asynchronous manager-to-team fan-out and its single fan-in summary. */
@Service(Service.Level.PROJECT)
class TeamDelegationCoordinatorService(
    private val project: Project,
) : Disposable {
    internal data class TaskReport(
        val participantName: String,
        val text: String,
    )

    /** Opaque handle used while the caller atomically registers every member of one team request. */
    class GroupHandle internal constructor(
        internal val id: String,
    )

    /** Thread-safe tracker that cannot complete until registration is explicitly sealed. */
    internal class GroupTracker(
        private val expectedTaskCount: Int,
    ) {
        private val participantNamesByTaskId = linkedMapOf<String, String>()
        private val reportsByTaskId = linkedMapOf<String, TaskReport>()
        private var sealed = false
        private var closed = false

        @Synchronized
        fun register(
            taskId: String,
            participantName: String,
        ): List<TaskReport>? {
            if (closed || participantNamesByTaskId.putIfAbsent(taskId, participantName) != null) return null
            return completeIfReady()
        }

        @Synchronized
        fun record(
            taskId: String,
            text: String,
        ): List<TaskReport>? {
            val participantName = participantNamesByTaskId[taskId] ?: return null
            if (closed || reportsByTaskId.putIfAbsent(taskId, TaskReport(participantName, text)) != null) return null
            return completeIfReady()
        }

        @Synchronized
        fun seal(): List<TaskReport>? {
            if (closed || participantNamesByTaskId.size != expectedTaskCount) return null
            sealed = true
            return completeIfReady()
        }

        @Synchronized
        fun taskIds(): Set<String> = participantNamesByTaskId.keys.toSet()

        private fun completeIfReady(): List<TaskReport>? {
            if (!sealed || closed || reportsByTaskId.size != expectedTaskCount) return null
            closed = true
            return reportsByTaskId.values.toList()
        }
    }

    private data class Group(
        val title: String,
        val tracker: GroupTracker,
    )

    private val groupsById = ConcurrentHashMap<String, Group>()
    private val groupsByTaskId = ConcurrentHashMap<String, Group>()
    private val earlyTerminalReportsByTaskId = ConcurrentHashMap<String, String>()
    private val router = project.service<CollaborationRouterService>()
    private val routerListener =
        PropertyChangeListener { event ->
            if (event.propertyName != "collaboration_task_update") return@PropertyChangeListener
            val update = event.newValue as? CollaborationTaskUpdateDto ?: return@PropertyChangeListener
            if (update.status !in TERMINAL_STATUSES) return@PropertyChangeListener
            recordTaskUpdate(update)
        }

    init {
        router.addPropertyChangeListener(routerListener)
    }

    fun begin(
        title: String,
        expectedTaskCount: Int,
    ): GroupHandle {
        require(expectedTaskCount > 0) { "expectedTaskCount must be positive" }
        val handle = GroupHandle(UUID.randomUUID().toString())
        groupsById[handle.id] = Group(title, GroupTracker(expectedTaskCount))
        return handle
    }

    /** Registers one accepted routed task before its transport is allowed to execute. */
    fun registerTask(
        handle: GroupHandle,
        taskId: String,
        participantName: String,
    ) {
        val group = groupsById[handle.id] ?: return
        groupsByTaskId[taskId] = group
        group.tracker.register(taskId, participantName)?.let { reports -> completeGroup(group, reports) }
        earlyTerminalReportsByTaskId.remove(taskId)?.let { text ->
            group.tracker.record(taskId, text)?.let { reports -> completeGroup(group, reports) }
        }
    }

    /** Records an immediate transport-start failure as one terminal group result. */
    fun registerFailedTask(
        handle: GroupHandle,
        taskId: String,
        participantName: String,
        error: String,
    ) {
        registerTask(handle, taskId, participantName)
        val group = groupsById[handle.id] ?: return
        group.tracker.record(taskId, "Failed: $error")?.let { reports -> completeGroup(group, reports) }
    }

    /** Permits fan-in only after every requested participant has been registered. */
    fun seal(handle: GroupHandle) {
        val group = groupsById[handle.id] ?: return
        group.tracker.seal()?.let { reports -> completeGroup(group, reports) }
    }

    private fun recordTaskUpdate(update: CollaborationTaskUpdateDto) {
        val text = update.text ?: update.error?.let { "Failed: $it" } ?: "Failed: no result"
        val group = groupsByTaskId[update.taskId]
        if (group == null) {
            earlyTerminalReportsByTaskId.putIfAbsent(update.taskId, text)
            return
        }
        group.tracker.record(update.taskId, text)?.let { reports -> completeGroup(group, reports) }
    }

    private fun completeGroup(
        group: Group,
        reports: List<TaskReport>,
    ) {
        group.tracker.taskIds().forEach { taskId ->
            groupsByTaskId.remove(taskId, group)
            earlyTerminalReportsByTaskId.remove(taskId)
        }
        groupsById.entries.removeIf { (_, candidate) -> candidate === group }
        project.service<ChatConversationService>().scheduleTeamDelegationSummary(
            group.title,
            reports.map { report -> "${report.participantName}: ${report.text}" },
        )
    }

    override fun dispose() {
        router.removePropertyChangeListener(routerListener)
        groupsById.clear()
        groupsByTaskId.clear()
        earlyTerminalReportsByTaskId.clear()
    }

    private companion object {
        val TERMINAL_STATUSES =
            setOf(
                CollaborationTaskStatusDto.COMPLETED,
                CollaborationTaskStatusDto.FAILED,
                CollaborationTaskStatusDto.CANCELLED,
                CollaborationTaskStatusDto.INTERRUPTED,
            )
    }
}
