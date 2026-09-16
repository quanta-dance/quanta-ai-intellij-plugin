// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools.agent

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.quanta_dance.quanta.plugins.intellij.backend.services.AcpDelegationTaskService
import com.github.quanta_dance.quanta.plugins.intellij.shared.tools.ToolInterface
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/** Cancels a queued or running background ACP delegation. */
@JsonClassDescription("Cancel a queued or running background ACP delegation by its delegationId.")
class CancelAcpDelegationTool : ToolInterface<Map<String, Any>> {
    @field:JsonPropertyDescription("ACP delegation ID returned by DelegateToAcpAgentTool")
    var delegationId: String = ""

    override fun execute(project: Project): Map<String, Any> {
        val id = delegationId.trim()
        if (id.isBlank()) return mapOf("status" to "error", "message" to "delegationId is required.")
        val task =
            project.service<AcpDelegationTaskService>().cancel(id)
                ?: return mapOf("status" to "error", "message" to "Unknown ACP delegation ID '$id'.")
        return mapOf(
            "delegationId" to task.delegationId,
            "status" to task.status.name.lowercase(),
            "message" to (task.message ?: "ACP delegation is already complete."),
        )
    }
}
