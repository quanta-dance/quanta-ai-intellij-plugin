// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools.agent

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.quanta_dance.quanta.plugins.intellij.backend.services.AcpDelegationTaskService
import com.github.quanta_dance.quanta.plugins.intellij.shared.tools.ToolInterface
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/** Sends a focused follow-up to a retained ACP collaboration session. */
@JsonClassDescription(
    "Send a focused message to a live ACP delegation on its existing session. Use it to share a relevant " +
        "finding, redirect the external agent, or ask a follow-up while internal agents continue in parallel. " +
        "Do not use it for routine progress polling; use GetAcpDelegationStatusTool only when a current status is needed.",
)
class SendAcpDelegationMessageTool : ToolInterface<Map<String, Any>> {
    @field:JsonPropertyDescription("ACP delegation ID returned by DelegateToAcpAgentTool")
    var delegationId: String = ""

    @field:JsonPropertyDescription("Focused message for the ACP agent's existing collaboration session")
    var message: String = ""

    override fun execute(project: Project): Map<String, Any> {
        val id = delegationId.trim()
        if (id.isBlank()) return error("delegationId is required.")
        if (message.isBlank()) return error("message is required.")
        val task =
            runCatching { project.service<AcpDelegationTaskService>().sendMessage(id, message) }
                .getOrElse { error -> return error(error.message ?: "Could not send ACP follow-up.") }
                ?: return error("Unknown ACP delegation ID '$id'.")
        return mapOf(
            "delegationId" to task.delegationId,
            "status" to task.status.name.lowercase(),
            "sessionId" to (task.sessionId ?: ""),
            "agent" to mapOf("id" to task.agent.id, "name" to task.agent.name),
            "message" to "Follow-up queued for the existing ACP collaboration session.",
        )
    }

    private fun error(message: String): Map<String, Any> = mapOf("status" to "error", "message" to message)
}
