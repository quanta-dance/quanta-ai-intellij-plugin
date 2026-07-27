// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools.mcp

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.quanta_dance.quanta.plugins.intellij.shared.tools.ToolInterface
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

@JsonClassDescription(
    "List MCP servers configured in mcp-servers.json with their connection status. " +
        "Always returns status for every configured server so you can diagnose connection problems.",
)
class McpListServersTool : ToolInterface<Map<String, Any>> {
    override fun execute(project: Project): Map<String, Any> {
        val mcp = project.service<McpClientService>()
        val servers = mcp.listServers()
        val result = linkedMapOf<String, Any>()

        val configError = mcp.getConfigLoadError()
        if (configError != null) {
            result["configError"] = configError
        }

        if (servers.isEmpty()) {
            result["configuredCount"] = mcp.getConfiguredCount()
            result["servers"] = emptyList<Any>()
            return result
        }

        result["servers"] =
            servers.map { name ->
                val status = mcp.getServerStatus(name)
                val entry = linkedMapOf<String, Any?>("name" to name, "connected" to status.connected)
                if (status.connected) entry["toolCount"] = status.toolCount
                if (status.error != null) entry["error"] = status.error
                entry
            }

        return result
    }
}
