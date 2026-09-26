// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import com.github.quanta_dance.quanta.plugins.intellij.backend.chat.AgentChannelStateService
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.DelegatedTaskStatusDto
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

internal data class NestedTaskReport(
    val role: String,
    val text: String,
)

/** Thread-confined by the coordinator's parent-state lock. */
internal class NestedTaskTracker {
    private val childTaskIds = linkedSetOf<String>()
    private val results = linkedMapOf<String, NestedTaskReport>()

    val isEmpty: Boolean
        get() = childTaskIds.isEmpty()

    val isComplete: Boolean
        get() = childTaskIds.isNotEmpty() && results.size == childTaskIds.size

    fun register(childTaskId: String) {
        childTaskIds += childTaskId
    }

    /** Returns false for an unknown or duplicate completion. */
    fun record(
        childTaskId: String,
        role: String,
        text: String,
    ): Boolean {
        if (childTaskId !in childTaskIds || childTaskId in results) return false
        results[childTaskId] = NestedTaskReport(role, text)
        return true
    }

    fun reports(): List<NestedTaskReport> = results.values.toList()
}

/**
 * Correlates an asynchronous agent-to-agent task with the delegated task that requested it.
 *
 * A parent agent is not blocked while its teammate works. Once every requested child task settles,
 * the parent receives one continuation turn containing the child reports and then completes its
 * original task. Ordinary inbox notifications intentionally do not use this coordinator.
 */
@Service(Service.Level.PROJECT)
class NestedAgentTaskCoordinatorService(
    private val project: Project,
) {
    data class ParentTaskContext(
        val agentId: String,
        val taskId: String,
    )

    private data class ParentState(
        val parentAgentId: String,
        val childTasks: NestedTaskTracker = NestedTaskTracker(),
        var waitingFuture: CompletableFuture<AgentManagerService.AgentTaskResult>? = null,
        var continuationStarted: Boolean = false,
    )

    private val activeParentContext = ThreadLocal<ParentTaskContext?>()
    private val parents = ConcurrentHashMap<String, ParentState>()

    fun <T> withActiveParentTask(
        agentId: String,
        taskId: String,
        block: () -> T,
    ): T {
        val previous = activeParentContext.get()
        activeParentContext.set(ParentTaskContext(agentId, taskId))
        return try {
            block()
        } finally {
            activeParentContext.set(previous)
        }
    }

    /** Starts a tracked child task for the agent currently executing a delegated parent task. */
    fun requestChildTask(
        targetAgentId: String,
        requestText: String,
    ): ChildTaskRequestResult {
        val parent =
            activeParentContext.get()
                ?: return ChildTaskRequestResult.error("Agent task requests are available only while handling a delegated task")
        if (targetAgentId == parent.agentId) {
            return ChildTaskRequestResult.error("An agent cannot request a child task from itself")
        }
        val manager = project.service<AgentManagerService>()
        val target =
            manager.getAgentsSnapshot().firstOrNull { it.id == targetAgentId }
                ?: return ChildTaskRequestResult.error("Unknown target agent: $targetAgentId")
        if (requestText.isBlank()) return ChildTaskRequestResult.error("Task request is empty")

        val channel = project.service<AgentChannelStateService>()
        val childTask =
            channel.createTask(
                title = "${target.role}: ${requestText.take(96)}",
                requestText = requestText,
                assignedAgentIds = listOf(target.id),
                assignedRoles = listOf(target.role),
                createdByRole = "Agent",
                dependsOnTaskIds = emptyList(),
                autoStart = false,
            )
        val state = parents.computeIfAbsent(parent.taskId) { ParentState(parent.agentId) }
        synchronized(state) {
            state.childTasks.register(childTask.id)
        }
        channel.updateTaskStatus(parent.taskId, DelegatedTaskStatusDto.RUNNING, summary = "Waiting for ${target.role}")
        manager.sendMessageAsync(target.id, requestText, childTask.id).whenComplete { result, error ->
            recordChildResult(
                parentTaskId = parent.taskId,
                childTaskId = childTask.id,
                childRole = target.role,
                text = result?.text ?: "Failed: ${result?.error ?: error?.message ?: "unknown error"}",
            )
        }
        return ChildTaskRequestResult.queued(childTask.id, target.role)
    }

    /**
     * Defers completion of [parentTaskId] after its initial handoff turn. Returns true only when
     * there are outstanding child requests; the supplied future is completed by the final parent
     * continuation.
     */
    fun deferParentCompletion(
        parentTaskId: String,
        completion: CompletableFuture<AgentManagerService.AgentTaskResult>,
    ): Boolean {
        val state = parents[parentTaskId] ?: return false
        synchronized(state) {
            if (state.childTasks.isEmpty || state.continuationStarted) return false
            state.waitingFuture = completion
            startContinuationIfReady(parentTaskId, state)
            return true
        }
    }

    private fun recordChildResult(
        parentTaskId: String,
        childTaskId: String,
        childRole: String,
        text: String,
    ) {
        val state = parents[parentTaskId] ?: return
        synchronized(state) {
            if (!state.childTasks.record(childTaskId, childRole, text)) return
            startContinuationIfReady(parentTaskId, state)
        }
    }

    private fun startContinuationIfReady(
        parentTaskId: String,
        state: ParentState,
    ) {
        val parentFuture = state.waitingFuture ?: return
        if (state.continuationStarted || !state.childTasks.isComplete) return
        state.continuationStarted = true
        val reports = state.childTasks.reports().joinToString("\n") { result -> "- ${result.role}: ${result.text}" }
        val continuation =
            "Your requested teammate work is complete. Use these reports to answer the original task directly and naturally. " +
                "Do not mention inbox processing or internal coordination unless it is relevant.\n$reports"
        project
            .service<AgentManagerService>()
            .sendMessageAsync(state.parentAgentId, continuation, parentTaskId)
            .whenComplete {
                result,
                error,
                ->
                parentFuture.complete(
                    result
                        ?: AgentManagerService.AgentTaskResult(
                            requestId = "",
                            agentId = state.parentAgentId,
                            ok = false,
                            text = null,
                            error = error?.message ?: "Parent continuation did not start",
                            taskId = parentTaskId,
                        ),
                )
                parents.remove(parentTaskId, state)
            }
    }

    data class ChildTaskRequestResult(
        val ok: Boolean,
        val message: String,
        val childTaskId: String? = null,
        val targetRole: String? = null,
    ) {
        companion object {
            fun queued(
                childTaskId: String,
                targetRole: String,
            ) = ChildTaskRequestResult(true, "Requested $targetRole asynchronously", childTaskId, targetRole)

            fun error(message: String) = ChildTaskRequestResult(false, message)
        }
    }
}
