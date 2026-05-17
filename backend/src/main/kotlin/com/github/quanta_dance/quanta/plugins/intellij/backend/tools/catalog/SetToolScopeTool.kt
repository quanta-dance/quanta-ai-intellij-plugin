// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools.catalog

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.quanta_dance.quanta.plugins.intellij.backend.services.ToolScopeService
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.mcp.McpClientService
import com.github.quanta_dance.quanta.plugins.intellij.shared.tools.ToolInterface
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * Tool entrypoint for changing the active tool scope.
 *
 * It can narrow the allowed built-in tools or MCP methods for the current turn, or make that
 * restriction sticky so it carries into later turns. The implementation exists, but the tool is
 * currently not registered in the active tool registry, so it is effectively disabled.
 */
@JsonClassDescription(
    "Request tool scope for current or future turns (sticky). Names use built-in class simple names and MCP 'server.method'.",
)
class SetToolScopeTool : ToolInterface<Map<String, Any>> {
    @field:JsonPropertyDescription("Built-in tool simple names to enable")
    var currentTurnBuiltIns: List<String>? = null

    @field:JsonPropertyDescription("MCP methods to enable for current turn, format: server.method")
    var currentTurnMcp: List<String>? = null

    @field:JsonPropertyDescription("Enable all methods for these MCP servers for the selected scope")
    var mcpServers: List<String>? = null

    @field:JsonPropertyDescription("If true, apply as sticky (persists for subsequent turns)")
    var sticky: Boolean = false

    /**
     * Resolves requested scope changes and stores them in [ToolScopeService].
     */
    override fun execute(project: Project): Map<String, Any> {
        val scope = project.service<ToolScopeService>()
        val mcp = project.service<McpClientService>()
        val resolver: (String) -> Collection<String> = { server ->
            buildList {
                for (tool in mcp.getTools(server)) {
                    add("$server.${tool.name}")
                }
            }
        }
        val res = scope.setScope(currentTurnBuiltIns, currentTurnMcp, mcpServers, sticky, resolver)
        return mapOf("status" to "ok") + res
    }
}
