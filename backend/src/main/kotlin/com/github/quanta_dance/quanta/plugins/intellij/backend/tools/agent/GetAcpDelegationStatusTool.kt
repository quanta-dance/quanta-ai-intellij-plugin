// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools.agent

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.quanta_dance.quanta.plugins.intellij.backend.services.AcpDelegationTaskService
import com.github.quanta_dance.quanta.plugins.intellij.shared.tools.ToolInterface
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/** Reads the current state or completed result of a background ACP delegation. */
@JsonClassDescription(
    "Get the status or completed findings of background ACP delegations. Provide delegationId for one task; omit it to list all tasks in this IDE project session.",
)
class GetAcpDelegationStatusTool : ToolInterface<Map<String, Any>> {
    @field:JsonPropertyDescription("Optional ACP delegation ID returned by DelegateToAcpAgentTool. Omit to list all tasks.")
    var delegationId: String? = null

    override fun execute(project: Project): Map<String, Any> {
        val service = project.service<AcpDelegationTaskService>()
        val id = delegationId?.trim().orEmpty()
        if (id.isBlank()) return mapOf("status" to "ok", "delegations" to service.list().map { it.toMap() })
        return service.get(id)?.let { mapOf("status" to "ok", "delegation" to it.toMap()) }
            ?: mapOf("status" to "error", "message" to "Unknown ACP delegation ID '$id'.")
    }

    private fun AcpDelegationTaskService.TaskSnapshot.toMap(): Map<String, Any?> =
        mapOf(
            "delegationId" to delegationId,
            "status" to status.name.lowercase(),
            "agent" to mapOf("id" to agent.id, "name" to agent.name, "version" to agent.version),
            "task" to taskTitle,
            "createdAtMillis" to createdAtMillis,
            "completedAtMillis" to completedAtMillis,
            "sessionId" to sessionId,
            "summary" to summary,
            "updateCount" to updateCount,
            "message" to message,
        )
}
