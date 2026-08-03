// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools.mcp

import com.fasterxml.jackson.annotation.JsonClassDescription
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

        val configuredCount = mcp.getConfiguredCount()
        result["configuredCount"] = configuredCount

        if (servers.isEmpty()) {
            result["servers"] = emptyList<Any>()
            result["message"] =
                when {
                    configError != null -> {
                        "MCP config loaded, but no servers are attached online yet. " +
                            "Check the config error and restart or reload the MCP settings."
                    }

                    configuredCount > 0 -> {
                        "MCP servers are configured, but none are attached online yet."
                    }

                    else -> {
                        "No MCP servers are configured. Add them to mcp-servers.json and reload the settings."
                    }
                }
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
        val onlineServerCount = servers.count { mcp.getServerStatus(it).connected }
        result["message"] =
            "Configured MCP servers: $configuredCount; attached MCP servers: ${servers.size}; " +
            "online tool-capable servers: $onlineServerCount"

        return result
    }
}
