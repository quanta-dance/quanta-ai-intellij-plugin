// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools.agent

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.quanta_dance.quanta.plugins.intellij.backend.chat.ChatConversationStateService
import com.github.quanta_dance.quanta.plugins.intellij.backend.services.AcpAgentDiscoveryService
import com.github.quanta_dance.quanta.plugins.intellij.backend.services.AcpDelegationService
import com.github.quanta_dance.quanta.plugins.intellij.backend.services.AcpDelegationTaskService
import com.github.quanta_dance.quanta.plugins.intellij.backend.settings.BackendRuntimeSettingsService
import com.github.quanta_dance.quanta.plugins.intellij.shared.tools.ToolInterface
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * Starts an independent ACP collaboration task that retains its session for follow-up messages.
 *
 * The selected agent must be discovered first with [DiscoverAcpAgentsTool]. This tool returns as
 * soon as the background task is queued. Use [SendAcpDelegationMessageTool] to coordinate with
 * the live ACP session, [GetAcpDelegationStatusTool] for explicit status inspection, or
 * [CancelAcpDelegationTool] to stop it.
 */
@JsonClassDescription(
    "Start an independent background task with an ACP agent that the user explicitly enabled for the current chat. " +
        "Call DiscoverAcpAgentsTool to inspect availability, but do not assume discovery grants access. If the agent is not " +
        "enabled, ask the user to add it from the Agentic team roster. This returns a delegationId immediately; continue " +
        "independent work and use SendAcpDelegationMessageTool to coordinate the live ACP teammate.",
)
class DelegateToAcpAgentTool : ToolInterface<Map<String, Any>> {
    @field:JsonPropertyDescription("ID of an ACP agent returned by DiscoverAcpAgentsTool")
    var agentId: String = ""

    @field:JsonPropertyDescription("A focused task for the independent ACP agent.")
    var task: String = ""

    @field:JsonPropertyDescription("Maximum background delegation time in milliseconds, from 1,000 to 120,000. Default: 60,000.")
    var timeoutMillis: Long = AcpDelegationService.DEFAULT_TIMEOUT_MILLIS

    override fun execute(project: Project): Map<String, Any> {
        if (agentId.isBlank()) return error("agentId is required. Call DiscoverAcpAgentsTool first.")
        if (task.isBlank()) return error("task is required.")
        if (timeoutMillis !in MIN_TIMEOUT_MILLIS..MAX_TIMEOUT_MILLIS) {
            return error("timeoutMillis must be between $MIN_TIMEOUT_MILLIS and $MAX_TIMEOUT_MILLIS.")
        }
        if (BackendRuntimeSettingsService.instance.settings.agenticEnabled != true) {
            return mapOf(
                "status" to "agentic_mode_required",
                "message" to "ACP teammates can only be used while agentic team mode is enabled.",
            )
        }
        val chatState = project.service<ChatConversationStateService>()
        val sessionId = chatState.getActiveSessionId()
        if (!chatState.isAcpAgentAllowed(sessionId, agentId)) {
            return mapOf(
                "status" to "authorization_required",
                "agentId" to agentId,
                "message" to
                    "This external ACP agent is not enabled for the current chat. Ask the user to add it in the Agentic team roster.",
            )
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
