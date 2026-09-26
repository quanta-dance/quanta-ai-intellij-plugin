// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.chat

import com.github.quanta_dance.quanta.plugins.intellij.backend.services.AgentManagerService
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.beans.PropertyChangeListener
import java.util.concurrent.ConcurrentHashMap

/** Coordinates one asynchronous manager-to-team fan-out and its single fan-in summary. */
@Service(Service.Level.PROJECT)
class TeamDelegationCoordinatorService(
    private val project: Project,
) : Disposable {
    internal data class TaskReport(
        val agentRole: String,
        val text: String,
    )

    internal class GroupTracker(
        taskIds: Collection<String>,
    ) {
        private val pendingTaskIds = taskIds.toMutableSet()
        private val reports = linkedMapOf<String, TaskReport>()
        private var closed = false

        /** Returns all reports exactly once when every registered task has settled. */
        @Synchronized
        fun record(
            taskId: String,
            report: TaskReport,
        ): List<TaskReport>? {
            if (closed || taskId !in pendingTaskIds) return null
            pendingTaskIds.remove(taskId)
            reports[taskId] = report
            if (pendingTaskIds.isNotEmpty()) return null
            closed = true
            return reports.values.toList()
        }
    }

    private data class Group(
        val title: String,
        val tracker: GroupTracker,
        val taskRoles: Map<String, String>,
    )

    private val groupsByTaskId = ConcurrentHashMap<String, Group>()
    private val agentManager = project.service<AgentManagerService>()
    private val taskListener =
        PropertyChangeListener { event ->
            if (event.propertyName != "agent_task_finished") return@PropertyChangeListener
            val result = event.newValue as? AgentManagerService.AgentTaskResult ?: return@PropertyChangeListener
            recordTaskResult(result)
        }

    init {
        agentManager.addPropertyChangeListener(taskListener)
    }

    fun register(
        title: String,
        taskRoles: Map<String, String>,
    ) {
        if (taskRoles.isEmpty()) return
        val group = Group(title = title, tracker = GroupTracker(taskRoles.keys), taskRoles = taskRoles)
        taskRoles.keys.forEach { taskId -> groupsByTaskId[taskId] = group }
    }

    /** Accepts task completion from either the manager event stream or its returned future. */
    fun recordTaskResult(result: AgentManagerService.AgentTaskResult) {
        onTaskFinished(result)
    }

    private fun onTaskFinished(result: AgentManagerService.AgentTaskResult) {
        val taskId = result.taskId ?: return
        val group = groupsByTaskId[taskId] ?: return
        val report =
            TaskReport(
                agentRole = group.taskRoles[taskId] ?: "Agent",
                text = result.text ?: "Failed: ${result.error ?: "unknown error"}",
            )
        val reports = group.tracker.record(taskId, report) ?: return

        group.taskRoles.keys.forEach(groupsByTaskId::remove)
        project.service<ChatConversationService>().scheduleTeamDelegationSummary(
            group.title,
            reports.map { report -> "${report.agentRole}: ${report.text}" },
        )
    }

    override fun dispose() {
        agentManager.removePropertyChangeListener(taskListener)
        groupsByTaskId.clear()
    }
}
