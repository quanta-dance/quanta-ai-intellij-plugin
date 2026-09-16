// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import com.github.quanta_dance.quanta.plugins.intellij.backend.chat.ChatConversationStateService
import com.github.quanta_dance.quanta.plugins.intellij.backend.logging.QDLog
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.AcpAgentDto
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Runs bounded ACP delegations in the background for one IDE project.
 *
 * Tasks are intentionally in-memory and are cancelled when the project closes. The service records
 * normalized progress and completion state without blocking the main agent's tool-call turn.
 */
@Service(Service.Level.PROJECT)
class AcpDelegationTaskService(
    @Suppress("unused") private val project: Project,
) : Disposable {
    enum class Status {
        QUEUED,
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED,
    }

    data class TaskSnapshot(
        val delegationId: String,
        val chatSessionId: String?,
        val agent: AcpAgentDto,
        val taskTitle: String,
        val status: Status,
        val createdAtMillis: Long,
        val completedAtMillis: Long? = null,
        val sessionId: String? = null,
        val summary: String? = null,
        val updateCount: Int = 0,
        val message: String? = null,
    )

    private data class TaskRecord(
        var snapshot: TaskSnapshot,
        var future: Future<*>? = null,
    )

    private val logger = Logger.getInstance(AcpDelegationTaskService::class.java)
    private val events = PropertyChangeSupport(this)
    private val executor: ExecutorService =
        Executors.newFixedThreadPool(MAX_CONCURRENT_TASKS) { runnable ->
            Thread(runnable, "qd-acp-delegation-${System.nanoTime()}").apply { isDaemon = true }
        }
    private val tasks = ConcurrentHashMap<String, TaskRecord>()

    fun addPropertyChangeListener(listener: PropertyChangeListener) = events.addPropertyChangeListener(listener)

    fun removePropertyChangeListener(listener: PropertyChangeListener) = events.removePropertyChangeListener(listener)

    fun start(
        agent: AcpAgentDto,
        task: String,
        workspacePath: String?,
        timeoutMillis: Long = AcpDelegationService.DEFAULT_TIMEOUT_MILLIS,
    ): TaskSnapshot {
        require(task.isNotBlank()) { "Delegated ACP task must not be empty." }
        require(!workspacePath.isNullOrBlank()) { "ACP delegation requires a project workspace path." }
        val delegationId = UUID.randomUUID().toString()
        val initial =
            TaskSnapshot(
                delegationId = delegationId,
                chatSessionId = project.service<ChatConversationStateService>().getActiveSessionId(),
                agent = agent,
                taskTitle = task.take(MAX_TASK_TITLE_LENGTH),
                status = Status.QUEUED,
                createdAtMillis = System.currentTimeMillis(),
            )
        val record = TaskRecord(snapshot = initial)
        tasks[delegationId] = record
        publish(initial)
        record.future =
            executor.submit {
                update(delegationId, Status.RUNNING)
                val result =
                    runCatching {
                        AcpDelegationService().delegate(agent, task, workspacePath, timeoutMillis)
                    }.getOrElse { error ->
                        AcpDelegationService.AcpDelegationResult(
                            agent = agent,
                            status = "error",
                            message = error.message ?: error::class.simpleName,
                        )
                    }
                complete(delegationId, result)
            }
        QDLog.debug(logger) { "ACP background delegation queued: id=$delegationId agent=${agent.id}" }
        return initial
    }

    fun get(delegationId: String): TaskSnapshot? = tasks[delegationId]?.snapshot

    fun list(): List<TaskSnapshot> = tasks.values.map { it.snapshot }.sortedByDescending { it.createdAtMillis }

    fun cancel(delegationId: String): TaskSnapshot? {
        val record = tasks[delegationId] ?: return null
        synchronized(record) {
            if (record.snapshot.status !in ACTIVE_STATUSES) return record.snapshot
            record.future?.cancel(true)
            val cancelled =
                record.snapshot.copy(
                    status = Status.CANCELLED,
                    completedAtMillis = System.currentTimeMillis(),
                    message = "ACP delegation cancelled.",
                )
            record.snapshot = cancelled
            publish(cancelled)
            QDLog.debug(logger) { "ACP background delegation cancelled: id=$delegationId" }
            return cancelled
        }
    }

    private fun complete(
        delegationId: String,
        result: AcpDelegationService.AcpDelegationResult,
    ) {
        val record = tasks[delegationId] ?: return
        synchronized(record) {
            if (record.snapshot.status == Status.CANCELLED) return
            val completed =
                record.snapshot.copy(
                    status = if (result.status == "completed") Status.COMPLETED else Status.FAILED,
                    completedAtMillis = System.currentTimeMillis(),
                    sessionId = result.sessionId,
                    summary = result.summary,
                    updateCount = result.updateCount,
                    message = result.message,
                )
            record.snapshot = completed
            publish(completed)
            QDLog.debug(logger) {
                "ACP background delegation finished: id=$delegationId status=${completed.status} " +
                    "agent=${completed.agent.id} updates=${completed.updateCount}"
            }
        }
    }

    private fun update(
        delegationId: String,
        status: Status,
    ) {
        val record = tasks[delegationId] ?: return
        synchronized(record) {
            if (record.snapshot.status == Status.CANCELLED) return
            record.snapshot = record.snapshot.copy(status = status)
            publish(record.snapshot)
        }
    }

    private fun publish(snapshot: TaskSnapshot) {
        events.firePropertyChange("acp_delegation", null, snapshot)
    }

    override fun dispose() {
        tasks.keys.toList().forEach(::cancel)
        executor.shutdownNow()
        tasks.clear()
    }

    companion object {
        private const val MAX_CONCURRENT_TASKS = 2
        private const val MAX_TASK_TITLE_LENGTH = 160
        private val ACTIVE_STATUSES = setOf(Status.QUEUED, Status.RUNNING)
    }
}
