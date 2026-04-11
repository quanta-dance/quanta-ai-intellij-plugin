// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.mcp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.quanta_dance.quanta.plugins.intellij.services.QDLog
import com.intellij.openapi.diagnostic.Logger
import com.openai.core.JsonValue
import com.openai.models.responses.FunctionTool
import com.openai.models.responses.Tool
import kotlinx.serialization.json.JsonElement
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

    fun buildTools(mcp: McpClientService): List<Tool> {
        val out = mutableListOf<Tool>()
        nameMap.clear()
        val servers = mcp.listServers()
        for (server in servers) {
            val tools = mcp.getTools(server)
            if (tools.isEmpty()) continue
            for (t in tools) {
                val toolClass = (t as Any)::class.java
                val method =
                    runCatching { toolClass.getMethod("getName").invoke(t) as? String }.getOrNull()
                        ?: runCatching { toolClass.getMethod("name").invoke(t) as? String }.getOrNull()
                        ?: continue
                val fnName = buildName(server, method)
                nameMap[fnName] = server to method

                val description =
                    buildString {
                        append("MCP method '")
                            .append(method)
                            .append("' on server '")
                            .append(server)
                            .append("'. ")
                        val toolDescription =
                            runCatching { toolClass.getMethod("getDescription").invoke(t) as? String }.getOrNull()
                                ?: runCatching { toolClass.getMethod("description").invoke(t) as? String }.getOrNull()
                        toolDescription?.let { if (it.isNotBlank()) append(it).append(' ') }
                    }

                val propertiesAny =
                    runCatching { toolClass.getMethod("getInputSchema").invoke(t) }.getOrNull()
                        ?: runCatching { toolClass.getMethod("inputSchema").invoke(t) }.getOrNull()
                        ?: continue
                val schemaClass = propertiesAny::class.java
                val properties =
                    runCatching {
                        schemaClass.getMethod("getProperties").invoke(propertiesAny) as? Map<*, *>
                    }.getOrNull()
                        ?: emptyMap<Any?, Any?>()
                val required =
                    runCatching {
                        schemaClass.getMethod("getRequired").invoke(propertiesAny) as? Collection<*>
                    }.getOrNull()
                        ?: emptyList<Any?>()

                val map: MutableMap<String, JsonValue> = hashMapOf<String, JsonValue>()

                properties.entries.forEach { entry ->
                    val key = entry.key?.toString() ?: return@forEach
                    val value = entry.value ?: return@forEach
                    val jsonElement = value as? JsonElement ?: return@forEach
                    val jsonNode = runCatching { jsonElementToJsonNode(jsonElement) }.getOrNull() ?: return@forEach
                    map[key] = JsonValue.fromJsonNode(jsonNode)
                }

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
                                .putAdditionalProperty("required", JsonValue.from(t.inputSchema.required.orEmpty()))
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

    fun jsonElementToJsonNode(elem: JsonElement): JsonNode = jacksonObjectMapper().readTree(elem.toString())

    fun resolve(name: String): Pair<String, String>? = nameMap[name]
}
