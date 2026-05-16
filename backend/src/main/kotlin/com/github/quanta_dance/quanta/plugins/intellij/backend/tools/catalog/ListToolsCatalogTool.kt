// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools.catalog

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.ToolsRegistry.toolsFor
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.mcp.McpClientService
import com.github.quanta_dance.quanta.plugins.intellij.shared.tools.ToolInterface
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

@JsonClassDescription("Return a compact tools catalog: built-in tool names and MCP servers/tools.")
class ListToolsCatalogTool : ToolInterface<Map<String, Any>> {
    @field:JsonPropertyDescription("If true, include tools-by-server map for MCP")
    var includeMcpDetails: Boolean = true

    override fun execute(project: Project): Map<String, Any> {
        val builtInNames = toolsFor(project).map { toolClass: Class<*> -> toolClass.simpleName }.sorted()
        val mcp = project.service<McpClientService>()
        val servers: List<String> = mcp.listServers().sorted()
        val toolsByServer: Map<String, List<String>> =
            if (includeMcpDetails) {
                servers.associateWith { server: String ->
                    mcp.getTools(server).map { tool -> tool.name }.sorted()
                }
            } else {
                emptyMap()
            }
        return mapOf(
            "builtIns" to builtInNames,
            "mcp" to
                    mapOf(
                        "servers" to servers,
                        "toolsByServer" to toolsByServer,
                    ),
        )
    }
}
