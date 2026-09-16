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
        WAITING_FOR_AUTHENTICATION,
        WAITING_FOR_PERMISSION,
        WAITING_FOR_USER_INPUT,
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
        val activity: List<String> = emptyList(),
        val updateCount: Int = 0,
        val message: String? = null,
        val chatMessageId: String? = null,
    )

    /** A concise ACP signal worth sharing with the owning main-agent turn. */
    data class MeaningfulEvent(
        val delegationId: String,
        val chatSessionId: String?,
        val agentName: String,
        val type: Type,
        val text: String,
        val occurredAtMillis: Long = System.currentTimeMillis(),
    ) {
        enum class Type {
            FINDING,
            ACTION_REQUIRED,
            COMPLETED,
            FAILED,
        }
    }

    private data class TaskRecord(
        var snapshot: TaskSnapshot,
        val cancellation: AcpDelegationService.CancellationSignal = AcpDelegationService.CancellationSignal(),
        val reportedEventFingerprints: MutableSet<String> = mutableSetOf(),
        var liveSession: AcpDelegationService.LiveSession? = null,
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
                        val service = AcpDelegationService()
                        val session =
                            service.openSession(
                                agent = agent,
                                workspacePath = workspacePath,
                                timeoutMillis = timeoutMillis,
                                cancellation = record.cancellation,
                                onInteraction = { interaction -> updateInteraction(delegationId, interaction) },
                                onTextUpdate = { update -> appendActivity(delegationId, update) },
                            )
                        synchronized(record) { record.liveSession = session }
                        session.prompt(task)
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

    /** Sends a follow-up prompt through the same retained ACP session. */
    fun sendMessage(
        delegationId: String,
        message: String,
    ): TaskSnapshot? {
        require(message.isNotBlank()) { "ACP follow-up message must not be empty." }
        val record = tasks[delegationId] ?: return null
        synchronized(record) {
            if (record.snapshot.status !in setOf(Status.RUNNING, Status.COMPLETED)) return record.snapshot
            val session =
                record.liveSession ?: return record.snapshot.copy(message = "ACP session is no longer available.")
            update(delegationId, Status.RUNNING)
            record.future =
                executor.submit {
                    val result =
                        runCatching { session.prompt(message) }.getOrElse { error ->
                            AcpDelegationService.AcpDelegationResult(
                                agent = record.snapshot.agent,
                                status = "error",
                                sessionId = session.sessionId,
                                message = error.message ?: error::class.simpleName,
                            )
                        }
                    complete(delegationId, result)
                }
            return record.snapshot
        }
    }

    fun attachChatMessage(
        delegationId: String,
        chatMessageId: String,
    ) {
        val record = tasks[delegationId] ?: return
        synchronized(record) {
            if (record.snapshot.chatMessageId == null) {
                record.snapshot = record.snapshot.copy(chatMessageId = chatMessageId)
            }
        }
    }

    fun list(): List<TaskSnapshot> = tasks.values.map { it.snapshot }.sortedByDescending { it.createdAtMillis }

    fun cancel(delegationId: String): TaskSnapshot? {
        val record = tasks[delegationId] ?: return null
        synchronized(record) {
            if (record.snapshot.status !in ACTIVE_STATUSES) return record.snapshot
            record.cancellation.cancel()
            record.liveSession?.close()
            record.liveSession = null
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
            if (completed.status == Status.FAILED) {
                record.liveSession?.close()
                record.liveSession = null
            }
            publish(completed)
            publishMeaningfulEvent(
                record = record,
                type = if (completed.status == Status.COMPLETED) MeaningfulEvent.Type.COMPLETED else MeaningfulEvent.Type.FAILED,
                text = completed.summary?.takeIf(String::isNotBlank) ?: completed.message.orEmpty(),
            )
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
            record.snapshot = record.snapshot.copy(status = status, message = null)
            publish(record.snapshot)
        }
    }

    private fun appendActivity(
        delegationId: String,
        update: String,
    ) {
        val activity = update.trim().takeIf(String::isNotBlank) ?: return
        val record = tasks[delegationId] ?: return
        synchronized(record) {
            if (record.snapshot.status == Status.CANCELLED || record.snapshot.activity.lastOrNull() == activity) return
            record.snapshot =
                record.snapshot.copy(
                    activity = (record.snapshot.activity + activity).takeLast(MAX_ACTIVITY_ENTRIES),
                )
            publish(record.snapshot)
            classifyFinding(activity)?.let { finding ->
                publishMeaningfulEvent(record, MeaningfulEvent.Type.FINDING, finding)
            }
        }
    }

    private fun updateInteraction(
        delegationId: String,
        interaction: AcpDelegationService.AcpInteraction,
    ) {
        val record = tasks[delegationId] ?: return
        synchronized(record) {
            if (record.snapshot.status == Status.CANCELLED) return
            val status =
                when (interaction.type) {
                    AcpDelegationService.AcpInteraction.Type.AUTHENTICATION -> Status.WAITING_FOR_AUTHENTICATION
                    AcpDelegationService.AcpInteraction.Type.PERMISSION -> Status.WAITING_FOR_PERMISSION
                    AcpDelegationService.AcpInteraction.Type.USER_INPUT -> Status.WAITING_FOR_USER_INPUT
                }
            record.snapshot = record.snapshot.copy(status = status, message = interaction.instructions)
            publish(record.snapshot)
            publishMeaningfulEvent(record, MeaningfulEvent.Type.ACTION_REQUIRED, interaction.instructions)
            QDLog.debug(logger) {
                "ACP delegation requires ${interaction.type.name.lowercase()}: id=$delegationId agent=${record.snapshot.agent.id}"
            }
        }
    }

    private fun publish(snapshot: TaskSnapshot) {
        events.firePropertyChange("acp_delegation", null, snapshot)
    }

    private fun publishMeaningfulEvent(
        record: TaskRecord,
        type: MeaningfulEvent.Type,
        text: String,
    ) {
        val normalizedText = text.trim().replace(Regex("\\s+"), " ").take(MAX_EVENT_TEXT_LENGTH)
        if (normalizedText.isBlank()) return
        val fingerprint = "$type:${normalizedText.lowercase()}"
        if (!record.reportedEventFingerprints.add(fingerprint)) return
        val snapshot = record.snapshot
        events.firePropertyChange(
            "acp_delegation_meaningful_event",
            null,
            MeaningfulEvent(
                delegationId = snapshot.delegationId,
                chatSessionId = snapshot.chatSessionId,
                agentName = snapshot.agent.name,
                type = type,
                text = normalizedText,
            ),
        )
    }

    private fun classifyFinding(activity: String): String? {
        val normalized = activity.trim().replace(Regex("\\s+"), " ")
        if (normalized.length < MIN_FINDING_LENGTH) return null
        val lower = normalized.lowercase()
        return normalized.takeIf { marker -> FINDING_MARKERS.any(lower::contains) && !PROGRESS_PREFIXES.any(lower::startsWith) }
    }

    override fun dispose() {
        tasks.keys.toList().forEach(::cancel)
        executor.shutdownNow()
        tasks.clear()
    }

    companion object {
        private const val MAX_CONCURRENT_TASKS = 2
        private const val MAX_TASK_TITLE_LENGTH = 160
        private const val MAX_ACTIVITY_ENTRIES = 12
        private const val MAX_EVENT_TEXT_LENGTH = 1_200
        private const val MIN_FINDING_LENGTH = 40
        private val FINDING_MARKERS =
            listOf("found", "finding", "root cause", "conclusion", "recommend", "blocked", "issue is", "problem is")
        private val PROGRESS_PREFIXES = listOf("reading ", "searching ", "inspecting ", "checking ", "analyzing ")
        private val ACTIVE_STATUSES =
            setOf(
                Status.QUEUED,
                Status.RUNNING,
                Status.WAITING_FOR_AUTHENTICATION,
                Status.WAITING_FOR_PERMISSION,
                Status.WAITING_FOR_USER_INPUT,
            )
    }
}
