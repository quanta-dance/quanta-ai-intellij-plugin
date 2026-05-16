// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools.agent

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.quanta_dance.quanta.plugins.intellij.backend.services.AgentManagerService
import com.github.quanta_dance.quanta.plugins.intellij.shared.tools.ToolInterface
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

@JsonClassDescription("Remove an existing agent by id or by a unique role name.")
class AgentRemoveTool : ToolInterface<Map<String, Any>> {
    @field:JsonPropertyDescription("Agent id or unique role name to remove")
    var agentId: String = ""

    override fun execute(project: Project): Map<String, Any> {
        val svc = project.service<AgentManagerService>()
        val resolvedId = svc.resolveAgentId(agentId)
            ?: return mapOf(
                "status" to "error",
                "message" to "unknown or ambiguous agent id/role",
            )

        val ok = svc.removeAgent(resolvedId)
        return if (ok) {
            mapOf("status" to "ok", "agent_id" to resolvedId)
        } else {
            mapOf("status" to "error", "message" to "unknown or ambiguous agent id/role")
        }
    }
}
