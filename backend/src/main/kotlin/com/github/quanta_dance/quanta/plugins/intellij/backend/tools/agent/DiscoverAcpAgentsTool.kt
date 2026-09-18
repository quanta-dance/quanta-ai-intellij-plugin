// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools.agent

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.github.quanta_dance.quanta.plugins.intellij.backend.chat.ChatConversationStateService
import com.github.quanta_dance.quanta.plugins.intellij.backend.services.AcpAgentDiscoveryService
import com.github.quanta_dance.quanta.plugins.intellij.backend.settings.BackendRuntimeSettingsService
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.AcpAgentDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.tools.ToolInterface
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * Lets the main AI discover ACP agents that are accessible to the backend process.
 *
 * Every reported agent has completed the ACP initialize handshake. Discovery probes known PATH
 * commands plus manually configured applications and TCP endpoints.
 */
@JsonClassDescription(
    "Discover ACP-compatible agents from backend PATH commands and manually configured ACP endpoints. Discovery only " +
        "reports availability; it does not authorize or connect an agent for work. The user must add an external agent " +
        "to the current chat from the Agentic team roster before delegation.",
)
class DiscoverAcpAgentsTool : ToolInterface<Map<String, Any>> {
    override fun execute(project: Project): Map<String, Any> {
        val allowedIds = project.service<ChatConversationStateService>().getAllowedAcpAgentIds()
        return discoveryResult(
            AcpAgentDiscoveryService(
                manualAgents = BackendRuntimeSettingsService.instance.settings.manualAcpAgents,
            ).discover(),
            allowedIds,
        )
    }

    internal fun discoveryResult(
        agents: List<AcpAgentDto>,
        allowedIds: Set<String> = emptySet(),
    ): Map<String, Any> =
        mapOf(
            "agents" to
                agents.map { agent ->
                    mapOf(
                        "id" to agent.id,
                        "name" to agent.name,
                        "command" to agent.command,
                        "executablePath" to agent.executablePath,
                        "version" to agent.version,
                        "protocolVersion" to agent.protocolVersion,
                        "allowedForCurrentChat" to (agent.id in allowedIds),
                    )
                },
            "foundCount" to agents.size,
            "allowedCount" to agents.count { it.id in allowedIds },
            "message" to
                if (agents.isEmpty()) {
                    "No ACP-compatible agents were found on the backend PATH or in manual ACP settings."
                } else {
                    "Found ${agents.size} ACP-compatible agent(s); ${agents.count { it.id in allowedIds }} enabled for this chat."
                },
        )
}
