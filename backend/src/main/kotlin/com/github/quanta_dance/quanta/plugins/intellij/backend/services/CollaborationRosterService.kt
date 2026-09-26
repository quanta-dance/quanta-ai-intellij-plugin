// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import com.github.quanta_dance.quanta.plugins.intellij.backend.chat.ChatConversationStateService
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.CollaborationAvailabilityDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.CollaborationCapabilityDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.CollaborationParticipantDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.CollaborationParticipantKindDto
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * Builds the session-authorized, transport-neutral collaboration roster.
 *
 * Local agents are project participants. ACP agents are included only after the user explicitly
 * enabled them for the active chat session. Transport IDs remain backend-only adapter details.
 */
@Service(Service.Level.PROJECT)
class CollaborationRosterService(
    private val project: Project,
) {
    fun participants(): List<CollaborationParticipantDto> {
        val chatState = project.service<ChatConversationStateService>()
        val manager = project.service<AgentManagerService>()
        val localAgents = manager.getAgentsSnapshot()
        val allowedAcpIds = chatState.getAllowedAcpAgentIds()
        val acpAgentsById = project.service<AcpAgentRosterService>().agents().associateBy { it.id }
        return buildList {
            add(
                CollaborationParticipantDto(
                    id = managerParticipantId(chatState.getActiveSessionId()),
                    displayName = "Quanta AI",
                    kind = CollaborationParticipantKindDto.MANAGER,
                    capabilities = setOf(CollaborationCapabilityDto.RESULTS, CollaborationCapabilityDto.STATUS_UPDATES),
                    availability = CollaborationAvailabilityDto.AVAILABLE,
                ),
            )
            localAgents
                .sortedBy(AgentManagerService.AgentSnapshot::role)
                .forEach { agent ->
                    add(
                        CollaborationParticipantDto(
                            id = localParticipantId(agent.id),
                            displayName = agent.role,
                            kind = CollaborationParticipantKindDto.LOCAL_AGENT,
                            capabilities =
                                setOf(
                                    CollaborationCapabilityDto.NOTIFICATIONS,
                                    CollaborationCapabilityDto.TASKS,
                                    CollaborationCapabilityDto.RESULTS,
                                    CollaborationCapabilityDto.STATUS_UPDATES,
                                ),
                            availability =
                                if (agent.isWorking) CollaborationAvailabilityDto.BUSY else CollaborationAvailabilityDto.AVAILABLE,
                            transportId = agent.id,
                        ),
                    )
                }
            allowedAcpIds
                .sorted()
                .forEach { agentId ->
                    val agent = acpAgentsById[agentId]
                    add(
                        CollaborationParticipantDto(
                            id = acpParticipantId(agentId),
                            displayName = agent?.name ?: "Shared ACP agent",
                            kind = CollaborationParticipantKindDto.ACP_AGENT,
                            capabilities =
                                setOf(
                                    CollaborationCapabilityDto.TASKS,
                                    CollaborationCapabilityDto.RESULTS,
                                    CollaborationCapabilityDto.STATUS_UPDATES,
                                    CollaborationCapabilityDto.CANCELLATION,
                                ),
                            availability =
                                if (agent ==
                                    null
                                ) {
                                    CollaborationAvailabilityDto.OFFLINE
                                } else {
                                    CollaborationAvailabilityDto.AVAILABLE
                                },
                            transportId = agentId,
                        ),
                    )
                }
        }
    }

    fun find(participantId: String): CollaborationParticipantDto? = participants().firstOrNull { it.id == participantId }

    companion object {
        private const val MANAGER_PREFIX = "manager:"
        private const val LOCAL_PREFIX = "local:"
        private const val ACP_PREFIX = "acp:"

        fun managerParticipantId(chatSessionId: String): String = "$MANAGER_PREFIX$chatSessionId"

        fun localParticipantId(agentId: String): String = "$LOCAL_PREFIX$agentId"

        fun acpParticipantId(agentId: String): String = "$ACP_PREFIX$agentId"
    }
}
