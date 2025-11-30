// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quantadance.quanta.plugins.intellij.mcp

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File

@JsonIgnoreProperties(ignoreUnknown = true)
data class McpServerConfig(
    val command: String? = null,
    val args: List<String> = emptyList(),
    val transport: String? = null,
    val env: Map<String, String>? = null,
    val url: String? = null,
    val headers: Map<String, String>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class McpServersFile(
    val mcpServers: Map<String, McpServerConfig> = emptyMap(),
)

object McpServersConfigLoader {
    private val log = Logger.getInstance(McpServersConfigLoader::class.java)
    private val mapper = jacksonObjectMapper()

    data class LoadResult(
        val file: McpServersFile?,
        val parseError: String? = null,
        val validationWarnings: List<String> = emptyList(),
    )

    fun load(project: Project): McpServersFile {
        val res = loadWithDiagnostics(project)
        return res.file ?: McpServersFile()
    }

    fun loadWithDiagnostics(project: Project): LoadResult {
        val base = project.basePath ?: return LoadResult(McpServersFile())
        val file = File(base, ".quantadance/mcp-servers.json")
        if (!file.exists()) return LoadResult(McpServersFile())
        return try {
            val parsed: McpServersFile = mapper.readValue(file)
            val warnings = validate(parsed)
            LoadResult(parsed, null, warnings)
        } catch (e: Exception) {
            val msg = "Failed to read ${file.name}: ${e.message}"
            log.warn(msg, e)
            // Do not notify here to avoid duplicates; caller is responsible for user-facing alerts
            LoadResult(null, msg, emptyList())
        }
    }

    private fun validate(cfg: McpServersFile): List<String> {
        val issues = mutableListOf<String>()
        cfg.mcpServers.forEach { (name, s) ->
            if ((s.url == null || s.url.isBlank()) && (s.command == null || s.command.isBlank())) {
                issues += "Server '$name' must specify either 'url' or 'command'"
            }
            s.transport?.let { t ->
                val lt = t.lowercase()
                if (s.url != null && lt !in setOf("websocket", "sse", "ws", "wss", "http", "https")) {
                    issues += "Server '$name' has url but unsupported transport '$t'"
                }
                if (s.url == null && lt != "stdio") {
                    issues += "Server '$name' without url must use transport=stdio (got '$t')"
                }
            }
        }
        return issues
    }
}
