// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.tools.catalog

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.quanta_dance.quanta.plugins.intellij.mcp.McpClientService
import com.github.quanta_dance.quanta.plugins.intellij.services.ToolScopeService
import com.github.quanta_dance.quanta.plugins.intellij.tools.ToolInterface
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

@JsonClassDescription("Request tool scope for current or future turns (sticky). Names use built-in class simple names and MCP 'server.method'.")
class SetToolScopeTool : ToolInterface<Map<String, Any>> {
    @field:JsonPropertyDescription("Built-in tool simple names to enable")
    var currentTurnBuiltIns: List<String>? = null

    @field:JsonPropertyDescription("MCP methods to enable for current turn, format: server.method")
    var currentTurnMcp: List<String>? = null

    @field:JsonPropertyDescription("Enable all methods for these MCP servers for the selected scope")
    var mcpServers: List<String>? = null

    @field:JsonPropertyDescription("If true, apply as sticky (persists for subsequent turns)")
    var sticky: Boolean = false

    override fun execute(project: Project): Map<String, Any> {
        val scope = project.service<ToolScopeService>()
        val mcp = project.service<McpClientService>()
        val resolver: (String) -> Collection<String> = { server -> mcp.getTools(server).map { "$server.${it.name}" } }
        val res = scope.setScope(currentTurnBuiltIns, currentTurnMcp, mcpServers, sticky, resolver)
        return mapOf("status" to "ok") + res
    }
}
