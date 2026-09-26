// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools.agent

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.quanta_dance.quanta.plugins.intellij.backend.services.AgentManagerService
import com.github.quanta_dance.quanta.plugins.intellij.backend.services.NestedAgentTaskCoordinatorService
import com.github.quanta_dance.quanta.plugins.intellij.shared.tools.ToolInterface
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * Delivers a fire-and-forget notification, a tracked child-task request, or a manager-visible report.
 *
 * Manager-directed reports never enter an agent inbox and never wake an agent. A tracked child-task
 * request returns its final result to the requesting agent's delegated task; use it when the sender
 * needs an answer before it can complete its own assignment.
 */
@JsonClassDescription(
    "Post a notification to another agent, request tracked work from another agent, or report a material update to the manager.",
)
class AgentPostMessageTool : ToolInterface<Map<String, Any>> {
    @field:JsonPropertyDescription(
        "Destination: AGENT for a fire-and-forget inbox notification, AGENT_TASK for a tracked request/reply, or MANAGER for a manager-visible report",
    )
    var destination: AgentMessageDestination = AgentMessageDestination.AGENT

    @field:JsonPropertyDescription("Target agent id. Required when destination is AGENT or AGENT_TASK")
    var toAgentId: String = ""

    @field:JsonPropertyDescription("Optional sender label (for example agent id or role)")
    var from: String? = null

    @field:JsonPropertyDescription("Message text or task request")
    var message: String = ""

    @field:JsonPropertyDescription("Optional kind tag for AGENT inbox notifications")
    var kind: String? = "notification"

    override fun execute(project: Project): Map<String, Any> {
        val svc = project.service<AgentManagerService>()
        when (destination) {
            AgentMessageDestination.MANAGER -> {
                return if (svc.reportToManager(from = from, text = message)) {
                    mapOf("status" to "ok", "destination" to destination.name)
                } else {
                    mapOf("status" to "error", "message" to "failed to report to manager: empty message")
                }
            }

            AgentMessageDestination.AGENT_TASK -> {
                val result = project.service<NestedAgentTaskCoordinatorService>().requestChildTask(toAgentId, message)
                return buildMap {
                    put("status", if (result.ok) "queued" else "error")
                    put("destination", destination.name)
                    put("message", result.message)
                    result.childTaskId?.let { put("childTaskId", it) }
                    result.targetRole?.let { put("targetRole", it) }
                }
            }

            AgentMessageDestination.AGENT -> {
                val ok = svc.postInboxMessage(toAgentId = toAgentId, from = from, text = message, kind = kind)
                return if (ok) {
                    mapOf("status" to "ok", "destination" to destination.name, "toAgentId" to toAgentId)
                } else {
                    mapOf("status" to "error", "message" to "failed to post (unknown agent or empty message)")
                }
            }
        }
    }
}

enum class AgentMessageDestination {
    AGENT,
    AGENT_TASK,
    MANAGER,
}
