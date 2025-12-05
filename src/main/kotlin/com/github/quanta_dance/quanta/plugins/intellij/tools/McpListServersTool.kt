// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.tools

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.quanta_dance.quanta.plugins.intellij.mcp.McpClientService
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

@JsonClassDescription("List available MCP servers discovered by the plugin.")
class McpListServersTool : ToolInterface<Map<String, Any>> {
    @field:JsonPropertyDescription("If true, include tool counts per server in the response")
    var includeDetails: Boolean = false

    override fun execute(project: Project): Map<String, Any> {
        val mcp = project.service<McpClientService>()
        val servers = mcp.listServers()
        val base: MutableMap<String, Any> = mutableMapOf("servers" to servers)
        if (includeDetails) {
            val details = servers.associateWith { s -> mcp.getTools(s).size }
            base["details"] = details
        }
        return base
    }
}
