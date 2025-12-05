// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.tools

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.quanta_dance.quanta.plugins.intellij.mcp.McpClientService
import com.github.quanta_dance.quanta.plugins.intellij.tools.ToolsRegistry.toolsFor
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

@JsonClassDescription("Return a compact tools catalog: built-in tool names and MCP servers/tools.")
class ListToolsCatalogTool : ToolInterface<Map<String, Any>> {
    @field:JsonPropertyDescription("If true, include tools-by-server map for MCP")
    var includeMcpDetails: Boolean = true

    override fun execute(project: Project): Map<String, Any> {
        val builtInNames = toolsFor(project).map { it.simpleName }.sorted()
        val mcp = project.service<McpClientService>()
        val servers = mcp.listServers().sorted()
        val toolsByServer = if (includeMcpDetails) servers.associateWith { s -> mcp.getTools(s).map { it.name }.sorted() } else emptyMap()
        return mapOf(
            "builtIns" to builtInNames,
            "mcp" to mapOf(
                "servers" to servers,
                "toolsByServer" to toolsByServer,
            ),
        )
    }
}
