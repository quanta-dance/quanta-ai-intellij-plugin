// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools.agent

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.github.quanta_dance.quanta.plugins.intellij.backend.services.AcpAgentDiscoveryService
import com.github.quanta_dance.quanta.plugins.intellij.backend.settings.BackendRuntimeSettingsService
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.AcpAgentDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.tools.ToolInterface
import com.intellij.openapi.project.Project

/**
 * Lets the main AI discover ACP agents that are accessible to the backend process.
 *
 * Every reported agent has completed the ACP initialize handshake. Discovery probes known PATH
 * commands plus manually configured applications and TCP endpoints.
 */
@JsonClassDescription(
    "Discover ACP-compatible agents from backend PATH commands and manually configured ACP endpoints. " +
        "Returns only agents that successfully complete the ACP initialize handshake.",
)
class DiscoverAcpAgentsTool : ToolInterface<Map<String, Any>> {
    override fun execute(project: Project): Map<String, Any> =
        discoveryResult(
            AcpAgentDiscoveryService(
                manualAgents = BackendRuntimeSettingsService.instance.settings.manualAcpAgents,
            ).discover(),
        )

    internal fun discoveryResult(agents: List<AcpAgentDto>): Map<String, Any> =
        mapOf(
            "agents" to agents,
            "foundCount" to agents.size,
            "message" to
                if (agents.isEmpty()) {
                    "No ACP-compatible agents were found on the backend PATH or in manual ACP settings."
                } else {
                    "Found ${agents.size} ACP-compatible agent(s): ${agents.joinToString { it.name }}."
                },
        )
}
