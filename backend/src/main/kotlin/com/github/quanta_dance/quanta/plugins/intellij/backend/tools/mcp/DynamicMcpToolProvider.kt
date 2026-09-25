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
import io.modelcontextprotocol.spec.McpSchema.Tool as McpTool

/**
 * Builds OpenAI FunctionTool definitions for every discovered MCP tool method.
 * Names must match ^[a-zA-Z0-9_-]+$, so we use mcp_<server>_<method> (sanitized).
 * Provides resolve(name) -> (server, method) for routing.
 */
object DynamicMcpToolProvider {
    private data class CachedTools(
        val tools: List<Tool>,
        val mappings: Map<String, Pair<String, String>>,
    )

    private val logger = Logger.getInstance(DynamicMcpToolProvider::class.java)
    private val nameMap: ConcurrentHashMap<String, Pair<String, String>> = ConcurrentHashMap()
    private val toolDefinitionCache = ConcurrentHashMap<String, CachedTools>()

    private fun sanitize(segment: String): String = segment.replace(Regex("[^A-Za-z0-9_-]"), "_")

    private fun buildName(
        server: String,
        method: String,
    ): String = "mcp_" + sanitize(server) + "_" + sanitize(method)

    /**
     * Builds from the MCP client's already-discovered snapshot only. The expensive OpenAI schema
     * conversion is memoized until a server's cached tool metadata changes.
     */
    fun buildTools(
        mcp: McpClientService,
        allowedToolNames: Set<String>? = null,
    ): List<Tool> {
        val normalizedAllowedNames =
            allowedToolNames
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toSortedSet()
        val cachedByServer = mcp.cachedToolsByServer()
        val cacheKey =
            buildString {
                append(System.identityHashCode(mcp)).append('|')
                append(normalizedAllowedNames?.joinToString(",") ?: "*").append('|')
                cachedByServer.forEach { (server, tools) ->
                    append(server).append(':')
                    tools.forEach { tool ->
                        append(tool.name())
                            .append(':')
                            .append(tool.description())
                            .append(':')
                            .append(tool.inputSchema().hashCode())
                            .append(';')
                    }
                    append('|')
                }
            }
        val cached =
            toolDefinitionCache.computeIfAbsent(cacheKey) {
                buildCachedTools(cachedByServer, normalizedAllowedNames)
            }
        nameMap.clear()
        nameMap.putAll(cached.mappings)
        return cached.tools
    }

    private fun buildCachedTools(
        toolsByServer: Map<String, List<McpTool>>,
        allowedToolNames: Set<String>?,
    ): CachedTools {
        val tools = mutableListOf<Tool>()
        val mappings = linkedMapOf<String, Pair<String, String>>()
        toolsByServer.forEach { (server, serverTools) ->
            serverTools.forEach { tool ->
                val method = tool.name()
                val functionName = buildName(server, method)
                val dottedName = "$server.$method"
                if (
                    allowedToolNames != null &&
                    server !in allowedToolNames &&
                    functionName !in allowedToolNames &&
                    dottedName !in allowedToolNames
                ) {
                    return@forEach
                }
                val parameters = hashMapOf<String, JsonValue>()
                val inputSchema = tool.inputSchema()
                val properties = inputSchema["properties"] as? Map<String, Any?> ?: emptyMap()
                properties.forEach { (propertyName, definition) ->
                    parameters[propertyName] = JsonValue.fromJsonNode(jacksonObjectMapper().valueToTree(definition))
                }
                val required = inputSchema["required"] as? List<String> ?: emptyList()
                val functionTool =
                    FunctionTool
                        .builder()
                        .name(functionName)
                        .description("MCP method '$method' on server '$server'. ${tool.description().orEmpty()}")
                        .parameters(
                            FunctionTool.Parameters
                                .builder()
                                .putAdditionalProperty("type", JsonValue.from("object"))
                                .putAdditionalProperty("properties", JsonValue.from(parameters))
                                .putAdditionalProperty("required", JsonValue.from(required))
                                .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                                .build(),
                        ).strict(false)
                        .build()
                try {
                    functionTool.validate()
                    tools += Tool.ofFunction(functionTool)
                    mappings[functionName] = server to method
                } catch (error: Throwable) {
                    QDLog.error(logger, { "$functionName is invalid" }, error)
                }
            }
        }
        return CachedTools(tools = tools, mappings = mappings)
    }

    fun resolve(name: String): Pair<String, String>? = nameMap[name]
}
