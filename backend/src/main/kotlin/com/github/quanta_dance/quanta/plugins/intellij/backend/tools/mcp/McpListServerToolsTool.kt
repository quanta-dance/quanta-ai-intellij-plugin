// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools.mcp

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.quanta_dance.quanta.plugins.intellij.mcp.McpClientService
import com.github.quanta_dance.quanta.plugins.intellij.shared.tools.ToolInterface
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

@JsonClassDescription("List available MCP tools for a given server.")
class McpListServerToolsTool : ToolInterface<Map<String, Any>> {
    @field:JsonPropertyDescription("MCP server name")
    var server: String = ""

    override fun execute(project: Project): Map<String, Any> {
        val mcp = project.service<McpClientService>()
        val tools: List<String> = mcp.getTools(server).map { it.name }
        return mapOf("server" to server, "tools" to tools)
    }
}
