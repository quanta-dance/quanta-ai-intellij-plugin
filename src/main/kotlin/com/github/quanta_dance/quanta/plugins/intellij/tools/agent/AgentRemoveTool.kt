// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.tools.agent

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.quanta_dance.quanta.plugins.intellij.services.AgentManagerService
import com.github.quanta_dance.quanta.plugins.intellij.tools.ToolInterface
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

@JsonClassDescription("Remove an existing agent by id.")
class AgentRemoveTool : ToolInterface<Map<String, Any>> {
    @field:JsonPropertyDescription("Agent id to remove")
    var agentId: String = ""

    override fun execute(project: Project): Map<String, Any> {
        val ok = project.service<AgentManagerService>().removeAgent(agentId)
        return if (ok) mapOf("status" to "ok", "agent_id" to agentId) else mapOf("status" to "error", "message" to "unknown agent id")
    }
}
