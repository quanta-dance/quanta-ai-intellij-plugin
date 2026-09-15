// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools.mcp

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.intellij.openapi.diagnostic.Logger

@JsonIgnoreProperties(ignoreUnknown = true)
data class McpServerConfig(
    val command: String? = null,
    val args: List<String> = emptyList(),
    val env: Map<String, String>? = null,
    val url: String? = null,
    val headers: Map<String, String>? = null,
    /** Optional public client ID for OAuth servers that do not permit dynamic client registration. */
    val oauthClientId: String? = null,
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

    fun loadJsonWithDiagnostics(
        jsonText: String,
        sourceName: String = "mcp-servers.json",
    ): LoadResult {
        if (jsonText.isBlank()) {
            return LoadResult(McpServersFile())
        }
        return try {
            val parsed: McpServersFile = mapper.readValue(jsonText)
            val warnings = validate(parsed)
            LoadResult(parsed, null, warnings)
        } catch (e: Exception) {
            val msg = "Failed to read $sourceName: ${e.message}"
            log.warn(msg, e)
            LoadResult(null, msg, emptyList())
        }
    }

    private fun validate(cfg: McpServersFile): List<String> =
        cfg.mcpServers
            .filter { (_, server) -> server.url.isNullOrBlank() && server.command.isNullOrBlank() }
            .map { (name, _) -> "Server '$name' must specify either 'url' or 'command'" }
}
