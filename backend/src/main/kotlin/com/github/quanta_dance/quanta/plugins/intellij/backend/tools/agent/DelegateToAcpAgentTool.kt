// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools.agent

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.quanta_dance.quanta.plugins.intellij.backend.services.AcpAgentDiscoveryService
import com.github.quanta_dance.quanta.plugins.intellij.backend.services.AcpDelegationService
import com.github.quanta_dance.quanta.plugins.intellij.backend.services.AcpDelegationTaskService
import com.github.quanta_dance.quanta.plugins.intellij.backend.settings.BackendRuntimeSettingsService
import com.github.quanta_dance.quanta.plugins.intellij.shared.tools.ToolInterface
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * Starts one bounded, read-only investigation with an ACP-compatible external agent.
 *
 * The selected agent must be discovered first with [DiscoverAcpAgentsTool]. This tool returns as
 * soon as the background task is queued; use [GetAcpDelegationStatusTool] to inspect its result or
 * [CancelAcpDelegationTool] to stop it. The ACP transport is opened only by the background worker
 * and is always closed when that worker finishes.
 */
@JsonClassDescription(
    "Start a bounded, read-only background investigation with a discovered ACP agent. Call DiscoverAcpAgentsTool " +
        "first and pass one returned agent ID. This returns a delegationId immediately; continue independent work. " +
        "Do not wait or repeatedly poll in this agent turn: check with GetAcpDelegationStatusTool only in a later turn " +
        "or when the user asks, and use CancelAcpDelegationTool to stop it.",
)
class DelegateToAcpAgentTool : ToolInterface<Map<String, Any>> {
    @field:JsonPropertyDescription("ID of an ACP agent returned by DiscoverAcpAgentsTool")
    var agentId: String = ""

    @field:JsonPropertyDescription("A focused investigation task for the ACP agent. Must be read-only.")
    var task: String = ""

    @field:JsonPropertyDescription("Maximum background delegation time in milliseconds, from 1,000 to 120,000. Default: 60,000.")
    var timeoutMillis: Long = AcpDelegationService.DEFAULT_TIMEOUT_MILLIS

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
        val agent =
            agents.firstOrNull { it.id == agentId }
                ?: return error("Unknown or unavailable ACP agent ID '$agentId'. Refresh discovery and try again.")
        return runCatching {
            project
                .service<AcpDelegationTaskService>()
                .start(agent, task, project.basePath, timeoutMillis)
                .toToolResult()
        }.getOrElse { exception ->
            error("Could not start ACP delegation to ${agent.name}: ${exception.message ?: exception::class.simpleName}")
        }
    }

    private fun AcpDelegationTaskService.TaskSnapshot.toToolResult(): Map<String, Any> =
        mapOf(
            "delegationId" to delegationId,
            "status" to status.name.lowercase(),
            "agent" to mapOf("id" to agent.id, "name" to agent.name, "version" to agent.version),
            "task" to taskTitle,
            "message" to "ACP delegation is queued in the background. Continue independent work and check its status later.",
        )

    private fun error(message: String): Map<String, Any> = mapOf("status" to "error", "message" to message)

    companion object {
        private const val MIN_TIMEOUT_MILLIS = 1_000L
        private const val MAX_TIMEOUT_MILLIS = 120_000L
    }
}
