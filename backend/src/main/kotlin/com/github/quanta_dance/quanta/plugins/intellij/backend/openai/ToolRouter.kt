// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.openai

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.quanta_dance.quanta.plugins.intellij.backend.chat.ChatConversationStateService
import com.github.quanta_dance.quanta.plugins.intellij.backend.logging.QDLog
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.mcp.DynamicMcpToolProvider
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.mcp.McpClientService
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
        } catch (_: Throwable) {
        }

        // Try dynamic MCP resolution (name is the tool id as exposed to OpenAI).
        DynamicMcpToolProvider.resolve(name)?.let { (server, method) ->
            return invokeMcpTool(server, method, functionCall.arguments())
        }
        // Fallback: dotted name server.method.
        if (name.contains('.')) {
            val index = name.indexOf('.')
            return invokeMcpTool(name.substring(0, index), name.substring(index + 1), functionCall.arguments())
        }
        // Built-in tool route
        return call(functionCall)
    }

    private fun invokeMcpTool(
        server: String,
        method: String,
        arguments: String,
    ): Map<String, String> {
        val sessions = project.service<ChatConversationStateService>()
        val activeSessionId = sessions.getActiveSessionId()
        if (!sessions.isMcpServerEnabled(activeSessionId, server)) {
            return mapOf("output" to "MCP server '$server' is disabled for the current chat.")
        }
        val output = project.service<McpClientService>().invokeTool(server, method, parseArgs(arguments), null)
        return mapOf("output" to output)
    }

    private fun call(functionCall: ResponseFunctionToolCall): Any {
        return try {
            val result = toolInvoker.invoke(project, functionCall)
            when (result) {
                is String -> mapOf("text" to result)
                else -> result
            }
        } catch (e: Throwable) {
            val toolFriendly = e.findCause<ToolFriendlyException>()
            if (toolFriendly != null) {
                val message = toolFriendly.message?.takeIf { it.isNotBlank() } ?: "Tool failed"
                QDLog.info(log) {
                    "Tool call failed (friendly): name=${functionCall.name()} code=${toolFriendly.code} err=$message"
                }
                return mapOf(
                    "status" to "error",
                    "tool" to functionCall.name(),
                    "code" to toolFriendly.code,
                    "message" to message,
                    "errorText" to message,
                    "summary" to message,
                    "retriable" to toolFriendly.retriable,
                )
            }

            val cancelled =
                e.findCause<ProcessCanceledException>()
                    ?: e.findCause<java.util.concurrent.CancellationException>()
            if (cancelled != null) {
                val cancelMessage = normalizeCancellationMessage(functionCall.name(), cancelled)
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
            mapOf(
                "status" to "error",
                "tool" to functionCall.name(),
                "code" to "unhandled_exception",
                "message" to (e.message ?: "Unhandled exception"),
                "errorText" to (e.message ?: "Unhandled exception"),
            )
        }
    }

    private fun normalizeCancellationMessage(
        toolName: String,
        cancelled: Throwable,
    ): String {
        val raw = cancelled.message?.trim().orEmpty()
        if (raw.isBlank() || raw.equals("Cancelled by Message.Cancel", ignoreCase = true)) {
            return "Execution of $toolName was cancelled before completion."
        }
        return raw
    }

    private inline fun <reified T : Throwable> Throwable.findCause(): T? {
        var current: Throwable? = this
        val visited = HashSet<Throwable>()
        while (current != null && visited.add(current)) {
            if (current is T) return current
            current = current.cause
        }
        return null
    }

    private fun parseArgs(argsJson: String): Map<String, Any?> =
        try {
            mapper.readValue(argsJson, object : TypeReference<Map<String, Any?>>() {})
        } catch (_: Throwable) {
            emptyMap()
        }
}
