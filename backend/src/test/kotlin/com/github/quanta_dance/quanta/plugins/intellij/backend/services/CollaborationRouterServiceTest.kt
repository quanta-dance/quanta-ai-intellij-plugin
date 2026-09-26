// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import com.github.quanta_dance.quanta.plugins.intellij.backend.chat.AgentChannelStateService
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.AcpAgentDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.CollaborationAvailabilityDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.CollaborationCapabilityDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.CollaborationIntentDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.CollaborationMessageDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.CollaborationParticipantDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.CollaborationParticipantKindDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.CollaborationTaskStatusDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.CollaborationTaskUpdateDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.DelegatedTaskDto
import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.beans.PropertyChangeEvent
import java.beans.PropertyChangeListener
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CollaborationRouterServiceTest {
    @Test
    fun `routes an ACP participant task to a local participant with ordered terminal updates`() {
        val manager = participant("manager:chat", "Quanta AI", CollaborationParticipantKindDto.MANAGER)
        val developer =
            participant("local:developer", "Developer", CollaborationParticipantKindDto.LOCAL_AGENT, "developer")
        val acpPeer =
            participant("acp:orders", "Shared Quanta · orders", CollaborationParticipantKindDto.ACP_AGENT, "orders")
        val fixture = fixture(manager, developer, acpPeer)
        val updates = mutableListOf<CollaborationTaskStatusDto>()
        fixture.router.addPropertyChangeListener(
            PropertyChangeListener { event ->
                if (event.propertyName == "collaboration_task_update") {
                    updates += (event.newValue as CollaborationTaskUpdateDto).status
                }
            },
        )

        val result = fixture.router.dispatch(taskMessage(senderId = acpPeer.id, recipientId = developer.id))

        assertTrue(result.accepted)
        assertEquals("task-1", result.taskId)
        assertEquals(listOf(CollaborationTaskStatusDto.QUEUED, CollaborationTaskStatusDto.COMPLETED), updates)
        fixture.router.dispose()
    }

    @Test
    fun `rejects unsupported notification delivery before selecting a transport`() {
        val manager = participant("manager:chat", "Quanta AI", CollaborationParticipantKindDto.MANAGER)
        val developer =
            participant("local:developer", "Developer", CollaborationParticipantKindDto.LOCAL_AGENT, "developer")
        val acpPeer =
            participant("acp:orders", "Shared Quanta · orders", CollaborationParticipantKindDto.ACP_AGENT, "orders")
        val fixture = fixture(manager, developer, acpPeer)

        val result =
            fixture.router.dispatch(
                taskMessage(
                    senderId = developer.id,
                    recipientId = acpPeer.id,
                    intent = CollaborationIntentDto.NOTIFICATION,
                ),
            )

        assertFalse(result.accepted)
        assertTrue(result.message.contains("does not support notification"))
        fixture.router.dispose()
    }

    @Test
    fun `routes ACP terminal updates through one normalized task lifecycle`() {
        val manager = participant("manager:chat", "Quanta AI", CollaborationParticipantKindDto.MANAGER)
        val developer =
            participant("local:developer", "Developer", CollaborationParticipantKindDto.LOCAL_AGENT, "developer")
        val acpPeer =
            participant("acp:orders", "Shared Quanta · orders", CollaborationParticipantKindDto.ACP_AGENT, "orders")
        val fixture = fixture(manager, developer, acpPeer)
        val updates = mutableListOf<CollaborationTaskStatusDto>()
        fixture.router.addPropertyChangeListener(
            PropertyChangeListener { event ->
                if (event.propertyName == "collaboration_task_update") {
                    updates +=
                        (event.newValue as CollaborationTaskUpdateDto).status
                }
            },
        )

        val dispatch = fixture.router.dispatch(taskMessage(senderId = developer.id, recipientId = acpPeer.id))

        assertTrue(dispatch.accepted)
        assertEquals("acp-task-1", dispatch.taskId)
        fixture.acpTaskListener.captured.propertyChange(
            PropertyChangeEvent(
                this,
                "acp_delegation",
                null,
                acpSnapshot(AcpDelegationTaskService.Status.RUNNING),
            ),
        )
        fixture.acpTaskListener.captured.propertyChange(
            PropertyChangeEvent(
                this,
                "acp_delegation",
                null,
                acpSnapshot(AcpDelegationTaskService.Status.COMPLETED, summary = "Orders contract reviewed"),
            ),
        )

        assertEquals(
            listOf(
                CollaborationTaskStatusDto.QUEUED,
                CollaborationTaskStatusDto.RUNNING,
                CollaborationTaskStatusDto.COMPLETED,
            ),
            updates,
        )
        fixture.router.dispose()
    }

    @Test
    fun `cancels an active ACP task and emits one terminal cancellation`() {
        val manager = participant("manager:chat", "Quanta AI", CollaborationParticipantKindDto.MANAGER)
        val developer =
            participant("local:developer", "Developer", CollaborationParticipantKindDto.LOCAL_AGENT, "developer")
        val acpPeer =
            participant("acp:orders", "Shared Quanta · orders", CollaborationParticipantKindDto.ACP_AGENT, "orders")
        val fixture = fixture(manager, developer, acpPeer)
        val updates = mutableListOf<CollaborationTaskStatusDto>()
        fixture.router.addPropertyChangeListener(
            PropertyChangeListener { event ->
                if (event.propertyName == "collaboration_task_update") {
                    updates +=
                        (event.newValue as CollaborationTaskUpdateDto).status
                }
            },
        )
        val dispatch = fixture.router.dispatch(taskMessage(senderId = developer.id, recipientId = acpPeer.id))

        val cancellation =
            fixture.router.dispatch(
                taskMessage(
                    senderId = developer.id,
                    recipientId = acpPeer.id,
                    intent = CollaborationIntentDto.CANCEL,
                ).copy(taskId = dispatch.taskId, text = ""),
            )

        assertTrue(cancellation.accepted)
        assertEquals(listOf(CollaborationTaskStatusDto.QUEUED, CollaborationTaskStatusDto.CANCELLED), updates)
        verify(exactly = 1) { fixture.acpTasks.cancel("acp-task-1") }
        fixture.router.dispose()
    }

    private fun fixture(vararg participants: CollaborationParticipantDto): RouterFixture {
        val project = mockk<Project>()
        val roster = mockk<CollaborationRosterService>()
        val acpTasks = mockk<AcpDelegationTaskService>(relaxed = true)
        val acpRoster = mockk<AcpAgentRosterService>()
        val channel = mockk<AgentChannelStateService>()
        val agentManager = mockk<AgentManagerService>()
        val acpTaskListener = slot<PropertyChangeListener>()
        val participantList = participants.toList()
        val acpAgent =
            AcpAgentDto(
                id = "orders",
                name = "Shared Quanta · orders",
                command = listOf("quanta-acp"),
                executablePath = "quanta-acp",
                protocolVersion = 1,
            )
        every { project.basePath } returns "/workspace"
        every { project.getService(CollaborationRosterService::class.java) } returns roster
        every { project.getService(AcpDelegationTaskService::class.java) } returns acpTasks
        every { project.getService(AcpAgentRosterService::class.java) } returns acpRoster
        every { project.getService(AgentChannelStateService::class.java) } returns channel
        every { project.getService(AgentManagerService::class.java) } returns agentManager
        every { acpTasks.addPropertyChangeListener(capture(acpTaskListener)) } returns Unit
        every { acpRoster.find("orders") } returns acpAgent
        every {
            acpTasks.start(
                acpAgent,
                any(),
                any(),
                any(),
            )
        } returns acpSnapshot(AcpDelegationTaskService.Status.QUEUED)
        every { roster.find(any()) } answers {
            participantList.firstOrNull { participant -> participant.id == invocation.args[0] as String }
        }
        every {
            channel.createTask(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
            )
        } returns DelegatedTaskDto(id = "task-1", title = "task")
        every { agentManager.sendMessageAsync(any(), any(), any(), any()) } returns
            CompletableFuture.completedFuture(
                AgentManagerService.AgentTaskResult(
                    requestId = "request-1",
                    agentId = "developer",
                    ok = true,
                    text = "Completed",
                    error = null,
                    taskId = "task-1",
                ),
            )
        return RouterFixture(CollaborationRouterService(project), acpTasks, acpTaskListener)
    }

    private fun participant(
        id: String,
        name: String,
        kind: CollaborationParticipantKindDto,
        transportId: String? = null,
    ): CollaborationParticipantDto =
        CollaborationParticipantDto(
            id = id,
            displayName = name,
            kind = kind,
            capabilities =
                when (kind) {
                    CollaborationParticipantKindDto.MANAGER -> {
                        setOf(CollaborationCapabilityDto.RESULTS, CollaborationCapabilityDto.STATUS_UPDATES)
                    }

                    CollaborationParticipantKindDto.LOCAL_AGENT -> {
                        setOf(
                            CollaborationCapabilityDto.NOTIFICATIONS,
                            CollaborationCapabilityDto.TASKS,
                            CollaborationCapabilityDto.RESULTS,
                            CollaborationCapabilityDto.STATUS_UPDATES,
                        )
                    }

                    CollaborationParticipantKindDto.ACP_AGENT -> {
                        setOf(
                            CollaborationCapabilityDto.TASKS,
                            CollaborationCapabilityDto.RESULTS,
                            CollaborationCapabilityDto.STATUS_UPDATES,
                            CollaborationCapabilityDto.CANCELLATION,
                        )
                    }
                },
            availability = CollaborationAvailabilityDto.AVAILABLE,
            transportId = transportId,
        )

    private fun taskMessage(
        senderId: String,
        recipientId: String,
        intent: CollaborationIntentDto = CollaborationIntentDto.TASK,
    ): CollaborationMessageDto =
        CollaborationMessageDto(
            id = "message-1",
            sessionId = "chat",
            senderId = senderId,
            recipientId = recipientId,
            intent = intent,
            text = "Inspect the project",
            createdAtEpochMs = 1,
        )

    private fun acpSnapshot(
        status: AcpDelegationTaskService.Status,
        summary: String? = null,
    ): AcpDelegationTaskService.TaskSnapshot =
        AcpDelegationTaskService.TaskSnapshot(
            delegationId = "acp-task-1",
            chatSessionId = "chat",
            agent =
                AcpAgentDto(
                    id = "orders",
                    name = "Shared Quanta · orders",
                    command = listOf("quanta-acp"),
                    executablePath = "quanta-acp",
                    protocolVersion = 1,
                ),
            taskTitle = "Inspect the project",
            status = status,
            createdAtMillis = 1,
            summary = summary,
        )

    private data class RouterFixture(
        val router: CollaborationRouterService,
        val acpTasks: AcpDelegationTaskService,
        val acpTaskListener: io.mockk.CapturingSlot<PropertyChangeListener>,
    )
}
