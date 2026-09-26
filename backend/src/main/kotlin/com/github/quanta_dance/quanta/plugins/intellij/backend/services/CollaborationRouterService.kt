// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import com.github.quanta_dance.quanta.plugins.intellij.backend.chat.AgentChannelStateService
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.CollaborationAvailabilityDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.CollaborationCapabilityDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.CollaborationIntentDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.CollaborationMessageDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.CollaborationParticipantDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.CollaborationParticipantKindDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.CollaborationTaskStatusDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.CollaborationTaskUpdateDto
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
import java.util.concurrent.ConcurrentHashMap

/**
 * Routes transport-neutral collaboration messages to local-agent or ACP adapters.
 *
 * The caller sees one task/update lifecycle regardless of whether a recipient is a local agent or
 * an explicitly authorized ACP peer. This service intentionally does not append uncorrelated events
 * to the visible manager chat; consumers decide whether a correlated update belongs to a task owner.
 */
@Service(Service.Level.PROJECT)
class CollaborationRouterService(
    private val project: Project,
) : Disposable {
    data class DispatchResult(
        val accepted: Boolean,
        val message: String,
        val taskId: String? = null,
    )

    private data class RoutedTask(
        val taskId: String,
        val senderId: String,
        val recipientId: String,
        val correlationId: String?,
        val transportTaskId: String,
        val kind: CollaborationParticipantKindDto,
    )

    private val events = PropertyChangeSupport(this)
    private val routedTasksByTaskId = ConcurrentHashMap<String, RoutedTask>()
    private val routedTaskIdByTransportTaskId = ConcurrentHashMap<String, String>()
    private val terminalTaskIds = ConcurrentHashMap.newKeySet<String>()
    private val acpTaskListener =
        PropertyChangeListener { event ->
            if (event.propertyName != "acp_delegation") return@PropertyChangeListener
            val snapshot = event.newValue as? AcpDelegationTaskService.TaskSnapshot ?: return@PropertyChangeListener
            onAcpTaskUpdate(snapshot)
        }

    init {
        project.service<AcpDelegationTaskService>().addPropertyChangeListener(acpTaskListener)
    }

    fun addPropertyChangeListener(listener: PropertyChangeListener) = events.addPropertyChangeListener(listener)

    fun removePropertyChangeListener(listener: PropertyChangeListener) = events.removePropertyChangeListener(listener)

    fun roster(): List<CollaborationParticipantDto> = project.service<CollaborationRosterService>().participants()

    fun dispatch(message: CollaborationMessageDto): DispatchResult {
        if (message.text.isBlank() && message.intent != CollaborationIntentDto.CANCEL) {
            return DispatchResult(false, "Collaboration message text is required")
        }
        val sender =
            project.service<CollaborationRosterService>().find(message.senderId)
                ?: return DispatchResult(false, "Unknown collaboration sender: ${message.senderId}")
        val recipient =
            project.service<CollaborationRosterService>().find(message.recipientId)
                ?: return DispatchResult(
                    false,
                    "Unknown or unauthorized collaboration recipient: ${message.recipientId}",
                )
        val requiredCapability = capabilityFor(message.intent)
        if (requiredCapability !in recipient.capabilities) {
            return DispatchResult(
                false,
                "${recipient.displayName} does not support ${message.intent.name.lowercase()} messages",
            )
        }
        if (recipient.availability == CollaborationAvailabilityDto.OFFLINE && message.intent != CollaborationIntentDto.CANCEL) {
            return DispatchResult(false, "${recipient.displayName} is offline")
        }
        return when (message.intent) {
            CollaborationIntentDto.TASK -> dispatchTask(sender, recipient, message)

            CollaborationIntentDto.NOTIFICATION -> dispatchNotification(sender, recipient, message)

            CollaborationIntentDto.RESULT,
            CollaborationIntentDto.STATUS,
            -> dispatchManagerUpdate(sender, recipient, message)

            CollaborationIntentDto.CANCEL -> dispatchCancellation(message)
        }
    }

    private fun dispatchTask(
        sender: CollaborationParticipantDto,
        recipient: CollaborationParticipantDto,
        message: CollaborationMessageDto,
    ): DispatchResult =
        when (recipient.kind) {
            CollaborationParticipantKindDto.LOCAL_AGENT -> {
                dispatchLocalTask(sender, recipient, message)
            }

            CollaborationParticipantKindDto.ACP_AGENT -> {
                dispatchAcpTask(sender, recipient, message)
            }

            CollaborationParticipantKindDto.MANAGER -> {
                DispatchResult(
                    false,
                    "The manager accepts correlated results and status, not tasks",
                )
            }
        }

    private fun dispatchLocalTask(
        sender: CollaborationParticipantDto,
        recipient: CollaborationParticipantDto,
        message: CollaborationMessageDto,
    ): DispatchResult {
        val localAgentId =
            recipient.transportId ?: return DispatchResult(false, "Local participant has no agent transport ID")
        val channel = project.service<AgentChannelStateService>()
        val task =
            channel.createTask(
                title = "${recipient.displayName}: ${message.text.take(96)}",
                requestText = message.text,
                assignedAgentIds = listOf(localAgentId),
                assignedRoles = listOf(recipient.displayName),
                createdByRole = sender.displayName,
                autoStart = false,
            )
        registerTask(
            RoutedTask(
                taskId = task.id,
                senderId = sender.id,
                recipientId = recipient.id,
                correlationId = message.correlationId,
                transportTaskId = task.id,
                kind = recipient.kind,
            ),
        )
        publishUpdate(
            task.id,
            sender.id,
            recipient.id,
            CollaborationTaskStatusDto.QUEUED,
            message.correlationId,
        )
        project
            .service<AgentManagerService>()
            .sendMessageAsync(localAgentId, message.text, task.id)
            .whenComplete { result, error ->
                publishTerminal(
                    task.id,
                    result?.let {
                        CollaborationTaskUpdateDto(
                            taskId = task.id,
                            senderId = recipient.id,
                            recipientId = sender.id,
                            status = if (it.ok) CollaborationTaskStatusDto.COMPLETED else CollaborationTaskStatusDto.FAILED,
                            text = it.text,
                            error = it.error,
                            correlationId = message.correlationId,
                            updatedAtEpochMs = System.currentTimeMillis(),
                        )
                    }
                        ?: CollaborationTaskUpdateDto(
                            taskId = task.id,
                            senderId = recipient.id,
                            recipientId = sender.id,
                            status = CollaborationTaskStatusDto.FAILED,
                            error = error?.message ?: "Local agent task did not start",
                            correlationId = message.correlationId,
                            updatedAtEpochMs = System.currentTimeMillis(),
                        ),
                )
            }
        return DispatchResult(true, "Task queued for ${recipient.displayName}", task.id)
    }

    private fun dispatchAcpTask(
        sender: CollaborationParticipantDto,
        recipient: CollaborationParticipantDto,
        message: CollaborationMessageDto,
    ): DispatchResult {
        val acpAgentId = recipient.transportId ?: return DispatchResult(false, "ACP participant has no transport ID")
        val agent =
            project.service<AcpAgentRosterService>().find(acpAgentId)
                ?: return DispatchResult(false, "${recipient.displayName} is no longer available")
        return runCatching {
            val snapshot =
                project
                    .service<AcpDelegationTaskService>()
                    .start(agent, message.text, project.basePath)
            registerTask(
                RoutedTask(
                    taskId = snapshot.delegationId,
                    senderId = sender.id,
                    recipientId = recipient.id,
                    correlationId = message.correlationId,
                    transportTaskId = snapshot.delegationId,
                    kind = recipient.kind,
                ),
            )
            publishUpdate(
                snapshot.delegationId,
                sender.id,
                recipient.id,
                CollaborationTaskStatusDto.QUEUED,
                message.correlationId,
            )
            DispatchResult(true, "Task queued for ${recipient.displayName}", snapshot.delegationId)
        }.getOrElse { error ->
            DispatchResult(
                false,
                "Could not start ACP task: ${error.message ?: error::class.simpleName}",
            )
        }
    }

    private fun dispatchNotification(
        sender: CollaborationParticipantDto,
        recipient: CollaborationParticipantDto,
        message: CollaborationMessageDto,
    ): DispatchResult {
        if (recipient.kind != CollaborationParticipantKindDto.LOCAL_AGENT) {
            return DispatchResult(false, "Notifications are currently supported only by local participants")
        }
        val targetId =
            recipient.transportId ?: return DispatchResult(false, "Local participant has no agent transport ID")
        val delivered =
            project.service<AgentManagerService>().postInboxMessage(targetId, sender.displayName, message.text)
        return if (delivered) {
            DispatchResult(true, "Notification delivered to ${recipient.displayName}")
        } else {
            DispatchResult(false, "Could not deliver notification to ${recipient.displayName}")
        }
    }

    private fun dispatchManagerUpdate(
        sender: CollaborationParticipantDto,
        recipient: CollaborationParticipantDto,
        message: CollaborationMessageDto,
    ): DispatchResult {
        if (recipient.kind != CollaborationParticipantKindDto.MANAGER) {
            return DispatchResult(false, "${message.intent.name.lowercase()} messages require a manager recipient")
        }
        if (message.correlationId.isNullOrBlank()) {
            return DispatchResult(false, "Manager updates require a correlation ID")
        }
        if (!project.service<AgentManagerService>().reportToManager(sender.displayName, message.text)) {
            return DispatchResult(false, "Could not deliver the manager update")
        }
        events.firePropertyChange("collaboration_manager_update", null, message)
        return DispatchResult(
            true,
            "${message.intent.name.lowercase().replaceFirstChar(Char::uppercase)} delivered to the manager",
        )
    }

    private fun dispatchCancellation(message: CollaborationMessageDto): DispatchResult {
        val taskId = message.taskId ?: return DispatchResult(false, "Cancellation requires taskId")
        val routedTask =
            routedTasksByTaskId[taskId] ?: return DispatchResult(false, "Unknown collaboration task: $taskId")
        return when (routedTask.kind) {
            CollaborationParticipantKindDto.ACP_AGENT -> {
                project.service<AcpDelegationTaskService>().cancel(routedTask.transportTaskId)
                publishTerminal(
                    taskId,
                    CollaborationTaskUpdateDto(
                        taskId = taskId,
                        senderId = routedTask.recipientId,
                        recipientId = routedTask.senderId,
                        status = CollaborationTaskStatusDto.CANCELLED,
                        text = "Task cancelled",
                        correlationId = routedTask.correlationId,
                        updatedAtEpochMs = System.currentTimeMillis(),
                    ),
                )
                DispatchResult(true, "Task cancelled", taskId)
            }

            CollaborationParticipantKindDto.LOCAL_AGENT -> {
                DispatchResult(
                    false,
                    "Cancellation is not yet available for local agent tasks",
                )
            }

            CollaborationParticipantKindDto.MANAGER -> {
                DispatchResult(
                    false,
                    "Manager tasks cannot be cancelled through collaboration routing",
                )
            }
        }
    }

    private fun registerTask(task: RoutedTask) {
        routedTasksByTaskId[task.taskId] = task
        routedTaskIdByTransportTaskId[task.transportTaskId] = task.taskId
    }

    private fun onAcpTaskUpdate(snapshot: AcpDelegationTaskService.TaskSnapshot) {
        val taskId = routedTaskIdByTransportTaskId[snapshot.delegationId] ?: return
        val routedTask = routedTasksByTaskId[taskId] ?: return
        val status =
            when (snapshot.status) {
                AcpDelegationTaskService.Status.QUEUED -> CollaborationTaskStatusDto.QUEUED

                AcpDelegationTaskService.Status.RUNNING -> CollaborationTaskStatusDto.RUNNING

                AcpDelegationTaskService.Status.WAITING_FOR_AUTHENTICATION,
                AcpDelegationTaskService.Status.WAITING_FOR_PERMISSION,
                AcpDelegationTaskService.Status.WAITING_FOR_USER_INPUT,
                -> CollaborationTaskStatusDto.WAITING_FOR_DEPENDENCIES

                AcpDelegationTaskService.Status.COMPLETED -> CollaborationTaskStatusDto.COMPLETED

                AcpDelegationTaskService.Status.FAILED -> CollaborationTaskStatusDto.FAILED

                AcpDelegationTaskService.Status.CANCELLED -> CollaborationTaskStatusDto.CANCELLED
            }
        val update =
            CollaborationTaskUpdateDto(
                taskId = taskId,
                senderId = routedTask.recipientId,
                recipientId = routedTask.senderId,
                status = status,
                text = snapshot.summary,
                error = snapshot.message.takeIf { status == CollaborationTaskStatusDto.FAILED },
                correlationId = routedTask.correlationId,
                updatedAtEpochMs = System.currentTimeMillis(),
            )
        if (status in TERMINAL_STATUSES) publishTerminal(taskId, update) else publish(update)
    }

    private fun publishUpdate(
        taskId: String,
        senderId: String,
        recipientId: String,
        status: CollaborationTaskStatusDto,
        correlationId: String?,
    ) {
        publish(
            CollaborationTaskUpdateDto(
                taskId = taskId,
                senderId = senderId,
                recipientId = recipientId,
                status = status,
                correlationId = correlationId,
                updatedAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    private fun publishTerminal(
        taskId: String,
        update: CollaborationTaskUpdateDto,
    ) {
        if (!terminalTaskIds.add(taskId)) return
        publish(update)
        routedTasksByTaskId.remove(taskId)?.let { routed ->
            routedTaskIdByTransportTaskId.remove(routed.transportTaskId)
        }
    }

    private fun publish(update: CollaborationTaskUpdateDto) {
        events.firePropertyChange("collaboration_task_update", null, update)
    }

    private fun capabilityFor(intent: CollaborationIntentDto): CollaborationCapabilityDto =
        when (intent) {
            CollaborationIntentDto.NOTIFICATION -> CollaborationCapabilityDto.NOTIFICATIONS
            CollaborationIntentDto.TASK -> CollaborationCapabilityDto.TASKS
            CollaborationIntentDto.RESULT -> CollaborationCapabilityDto.RESULTS
            CollaborationIntentDto.STATUS -> CollaborationCapabilityDto.STATUS_UPDATES
            CollaborationIntentDto.CANCEL -> CollaborationCapabilityDto.CANCELLATION
        }

    override fun dispose() {
        project.service<AcpDelegationTaskService>().removePropertyChangeListener(acpTaskListener)
        routedTasksByTaskId.clear()
        routedTaskIdByTransportTaskId.clear()
        terminalTaskIds.clear()
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
