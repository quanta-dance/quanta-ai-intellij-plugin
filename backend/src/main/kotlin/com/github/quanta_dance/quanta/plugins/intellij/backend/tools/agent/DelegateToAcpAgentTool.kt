// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools.agent

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.quanta_dance.quanta.plugins.intellij.backend.services.AcpAgentDelegationService
import com.github.quanta_dance.quanta.plugins.intellij.backend.services.AcpAgentDiscoveryService
import com.github.quanta_dance.quanta.plugins.intellij.backend.settings.BackendRuntimeSettingsService
import com.github.quanta_dance.quanta.plugins.intellij.shared.tools.ToolInterface
import com.intellij.openapi.project.Project

/**
 * Delegates one bounded, read-only investigation to an ACP-compatible external agent.
 *
 * The selected agent must be discovered first with [DiscoverAcpAgentsTool]. The transport remains
 * open only for this delegation's ACP initialize/session/prompt lifecycle and is closed afterward.
 */
@JsonClassDescription(
    "Delegate one bounded, read-only investigation to a discovered ACP agent. Call DiscoverAcpAgentsTool first " +
            "and pass one returned agent ID. The external agent's findings are returned for you to verify and act on; " +
            "do not use this tool for editing, commands that mutate state, credentials, or destructive work.",
)
class DelegateToAcpAgentTool : ToolInterface<Map<String, Any>> {
    @field:JsonPropertyDescription("ID of an ACP agent returned by DiscoverAcpAgentsTool")
    var agentId: String = ""

    @field:JsonPropertyDescription("A focused investigation task for the ACP agent. Must be read-only.")
    var task: String = ""

    @field:JsonPropertyDescription("Maximum time to wait for the delegation in milliseconds, from 1,000 to 120,000. Default: 60,000.")
    var timeoutMillis: Long = AcpAgentDelegationService.DEFAULT_TIMEOUT_MILLIS

    override fun execute(project: Project): Map<String, Any> {
        if (agentId.isBlank()) return error("agentId is required. Call DiscoverAcpAgentsTool first.")
        if (task.isBlank()) return error("task is required.")
        if (timeoutMillis !in MIN_TIMEOUT_MILLIS..MAX_TIMEOUT_MILLIS) {
            return error("timeoutMillis must be between $MIN_TIMEOUT_MILLIS and $MAX_TIMEOUT_MILLIS.")
        }
        val agents =
            AcpAgentDiscoveryService(
                manualAgents = BackendRuntimeSettingsService.instance.settings.manualAcpAgents,
            ).discover()
        val agent = agents.firstOrNull { it.id == agentId }
            ?: return error("Unknown or unavailable ACP agent ID '$agentId'. Refresh discovery and try again.")
        return runCatching {
            AcpAgentDelegationService()
                .delegate(agent, task, project.basePath, timeoutMillis)
                .toToolResult()
        }.getOrElse { error ->
            error("ACP delegation to ${agent.name} failed: ${error.message ?: error::class.simpleName}")
        }
    }

    private fun AcpAgentDelegationService.AcpDelegationResult.toToolResult(): Map<String, Any> =
        buildMap {
            put("status", status)
            put("agent", mapOf("id" to agent.id, "name" to agent.name, "version" to agent.version))
            sessionId?.let { put("sessionId", it) }
            summary?.let { put("summary", it) }
            put("updateCount", updateCount)
            message?.let { put("message", it) }
        }

    private fun error(message: String): Map<String, Any> = mapOf("status" to "error", "message" to message)

    companion object {
        private const val MIN_TIMEOUT_MILLIS = 1_000L
        private const val MAX_TIMEOUT_MILLIS = 120_000L
    }
}
