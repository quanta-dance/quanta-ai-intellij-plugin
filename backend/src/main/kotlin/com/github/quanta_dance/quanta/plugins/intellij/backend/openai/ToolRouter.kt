// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.openai

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.mcp.DynamicMcpToolProvider
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.mcp.McpClientService
import com.github.quanta_dance.quanta.plugins.intellij.backend.logging.QDLog
import com.github.quanta_dance.quanta.plugins.intellij.services.ToolWindowService
import com.github.quanta_dance.quanta.plugins.intellij.services.ui.Notifications
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.openai.models.responses.ResponseFunctionToolCall

class ToolRouter(
    private val project: Project,
    private val toolInvoker: ToolInvoker,
    private val mapper: ObjectMapper,
) {
    private val log = Logger.getInstance(ToolRouter::class.java)

    fun route(functionCall: ResponseFunctionToolCall): Any {
        val name = functionCall.name()
        try {
            QDLog.debug(log) { "Tool route: name=$name" }
            project.service<ToolWindowService>().addDebugMessage("tool_route", "name=$name")
        } catch (_: Throwable) {
        }

        // Try dynamic MCP resolution (name is the tool id as exposed to OpenAI)
        DynamicMcpToolProvider.resolve(name)?.let { (server, method) ->
            val argsJson = functionCall.arguments()
            val argsMap: Map<String, Any?> = parseArgs(argsJson)
            val out = project.service<McpClientService>().invokeTool(server, method, argsMap, null)
            return mapOf("output" to out)
        }
        // Fallback: dotted name server.method
        if (name.contains('.')) {
            val idx = name.indexOf('.')
            val server = name.substring(0, idx)
            val method = name.substring(idx + 1)
            val argsJson = functionCall.arguments()
            val argsMap: Map<String, Any?> = parseArgs(argsJson)
            val out = project.service<McpClientService>().invokeTool(server, method, argsMap, null)
            return mapOf("output" to out)
        }
        // Built-in tool route
        return call(functionCall)
    }

    private fun call(functionCall: ResponseFunctionToolCall): Any {
        return try {
            val result = toolInvoker.invoke(project, functionCall)
            when (result) {
                is String -> mapOf("text" to result)
                else -> result
            }
        } catch (e: Throwable) {
            if (e is ToolFriendlyException) {
                val message = e.message?.takeIf { it.isNotBlank() } ?: "Tool failed"
                QDLog.info(log) { "Tool call failed (friendly): name=${functionCall.name()} code=${e.code} err=$message" }
                return mapOf(
                    "status" to "error",
                    "tool" to functionCall.name(),
                    "code" to e.code,
                    "message" to message,
                    "errorText" to message,
                    "summary" to message,
                    "retriable" to e.retriable,
                )
            }
            if (e is ProcessCanceledException || e is java.util.concurrent.CancellationException) {
                val cancelMessage =
                    e.message?.takeIf { it.isNotBlank() }
                        ?: "Execution of ${functionCall.name()} was cancelled before completion. Retry may succeed."
                QDLog.info(log) { "Tool call cancelled: name=${functionCall.name()} err=$cancelMessage" }
                return mapOf(
                    "status" to "error",
                    "tool" to functionCall.name(),
                    "code" to "cancelled",
                    "message" to cancelMessage,
                    "errorText" to cancelMessage,
                    "summary" to cancelMessage,
                    "retriable" to true,
                )
            }
            try {
                QDLog.warn(log, { "Tool call failed: name=${functionCall.name()} err=${e.message}" }, e)
            } catch (_: Throwable) {
            }
            log.error("Tool '${functionCall.name()}' failed: ${e.message}", e)
            Notifications.show(project, e.message.orEmpty(), NotificationType.ERROR)
            mapOf(
                "status" to "error",
                "tool" to functionCall.name(),
                "code" to "unhandled_exception",
                "message" to (e.message ?: "Unhandled exception"),
                "errorText" to (e.message ?: "Unhandled exception"),
            )
        }
    }

    private fun parseArgs(argsJson: String): Map<String, Any?> =
        try {
            mapper.readValue(argsJson, object : TypeReference<Map<String, Any?>>() {})
        } catch (_: Throwable) {
            emptyMap()
        }
}
