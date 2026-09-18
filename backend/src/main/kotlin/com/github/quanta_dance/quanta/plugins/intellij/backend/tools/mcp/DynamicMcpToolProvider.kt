// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools.mcp

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.quanta_dance.quanta.plugins.intellij.backend.logging.QDLog
import com.intellij.openapi.diagnostic.Logger
import com.openai.core.JsonValue
import com.openai.models.responses.FunctionTool
import com.openai.models.responses.Tool
import java.util.concurrent.ConcurrentHashMap

/**
 * Builds OpenAI FunctionTool definitions for every discovered MCP tool method.
 * Names must match ^[a-zA-Z0-9_-]+$, so we use mcp_<server>_<method> (sanitized).
 * Provides resolve(name) -> (server, method) for routing.
 */
object DynamicMcpToolProvider {
    private val logger = Logger.getInstance(DynamicMcpToolProvider::class.java)
    private val nameMap: ConcurrentHashMap<String, Pair<String, String>> = ConcurrentHashMap()

    private fun sanitize(segment: String): String = segment.replace(Regex("[^A-Za-z0-9_-]"), "_")

    private fun buildName(
        server: String,
        method: String,
    ): String = "mcp_" + sanitize(server) + "_" + sanitize(method)

    fun buildTools(
        mcp: McpClientService,
        allowedToolNames: Set<String>? = null,
    ): List<Tool> {
        val normalizedAllowedNames =
            allowedToolNames
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toSet()

        val out = mutableListOf<Tool>()
        nameMap.clear()
        val servers = mcp.listServers()
        for (server in servers) {
            val tools = mcp.getTools(server)
            if (tools.isEmpty()) continue
            for (t in tools) {
                val method = t.name()
                val fnName = buildName(server, method)
                val dottedName = "$server.$method"
                if (
                    normalizedAllowedNames != null &&
                    server !in normalizedAllowedNames &&
                    fnName !in normalizedAllowedNames &&
                    dottedName !in normalizedAllowedNames
                ) {
                    continue
                }
                nameMap[fnName] = server to method

                val description =
                    buildString {
                        append("MCP method '")
                            .append(method)
                            .append("' on server '")
                            .append(server)
                            .append("'. ")
                        t.description()?.let { if (it.isNotBlank()) append(it).append(' ') }
                    }

                val map: MutableMap<String, JsonValue> = hashMapOf()
                val inputSchema = t.inputSchema()
                val properties = inputSchema["properties"] as? Map<String, Any?> ?: emptyMap()
                properties.forEach { (propertyName, definition) ->
                    map[propertyName] = JsonValue.fromJsonNode(jacksonObjectMapper().valueToTree(definition))
                }
                val required = inputSchema["required"] as? List<String> ?: emptyList()

                val fnTool =
                    FunctionTool
                        .builder()
                        .name(fnName)
                        .description(description)
                        .parameters(
                            FunctionTool.Parameters
                                .builder()
                                .putAdditionalProperty("type", JsonValue.from("object"))
                                .putAdditionalProperty("properties", JsonValue.from(map))
                                .putAdditionalProperty("required", JsonValue.from(required))
                                .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                                .build(),
                        ).strict(false)
                        .build()

                try {
                    fnTool.validate()
                    out += Tool.ofFunction(fnTool)
                } catch (e: Throwable) {
                    QDLog.error(logger, { fnTool.name() + " is invalid" }, e)
                }
            }
        }
        return out
    }

    fun resolve(name: String): Pair<String, String>? = nameMap[name]
}
