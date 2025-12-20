// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.tools.agent

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.quanta_dance.quanta.plugins.intellij.services.AgentManagerService
import com.github.quanta_dance.quanta.plugins.intellij.tools.ToolInterface
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

@JsonClassDescription("Create a new role-based agent that can perform tasks and converse in natural language.")
class AgentCreateTool : ToolInterface<Map<String, Any>> {
    @field:JsonPropertyDescription("Agent role name, e.g., tester, reviewer, refactorer")
    var role: String = "agent"

    @field:JsonPropertyDescription("Optional per-agent model id override (e.g., gpt-5-mini, gpt-5, gpt-4o-mini)")
    var model: String? = null

    @field:JsonPropertyDescription("Additional role-specific instructions for this agent")
    var instructions: String? = null

    @field:JsonPropertyDescription("Whether this agent can use MCP tools. Default: true")
    var includeMcp: Boolean = true

    @field:JsonPropertyDescription("Whether this agent can use built-in IDE/file tools. Default: true")
    var allowBuiltInTools: Boolean = true

    @field:JsonPropertyDescription("Optional list of allowed MCP server names for guidance")
    var allowedMcpServers: List<String>? = null

    // New: explicit allow-lists for fine-grained control
    @field:JsonPropertyDescription("Explicit allow-list of built-in tool class simple names for this agent. If set, agent can only use these.")
    var allowedBuiltInNames: Set<String>? = null

    @field:JsonPropertyDescription("Explicit allow-list of MCP tools in 'server.tool' form. If set, agent can only use these.")
    var allowedMcpNames: Set<String>? = null

    override fun execute(project: Project): Map<String, Any> {
        val svc = project.service<AgentManagerService>()
        val id = svc.createAgent(
            AgentManagerService.AgentConfig(
                role = role,
                model = model,
                instructions = instructions,
                includeMcp = includeMcp,
                allowedBuiltInTools = allowBuiltInTools,
                allowedMcpServers = allowedMcpServers,
                allowedBuiltInNames = allowedBuiltInNames,
                allowedMcpNames = allowedMcpNames,
            ),
        )
        return mapOf("agent_id" to id, "role" to role)
    }
}
