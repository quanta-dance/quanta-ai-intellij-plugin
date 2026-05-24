// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.openai

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.quanta_dance.quanta.plugins.intellij.backend.logging.QDLog
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.ToolsRegistry
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.openai.models.responses.ResponseFunctionToolCall

class DefaultToolInvoker : ToolInvoker {
    private val mapper = jacksonObjectMapper()
    private val log = Logger.getInstance(DefaultToolInvoker::class.java)

    override fun invoke(
        project: Project,
        functionCall: ResponseFunctionToolCall,
    ): Any {
        val toolClass =
            ToolsRegistry
                .toolsFor(project)
                .firstOrNull { it.simpleName == functionCall.name() || it.name.endsWith(".${functionCall.name()}") }
                ?: error("Unknown tool '${functionCall.name()}'")

        val argsJson = functionCall.arguments()
        val argsPreview = if (argsJson.length > 800) argsJson.take(800) + "... (truncated)" else argsJson
        QDLog.debug(log) {
            "DefaultToolInvoker.invoke: tool=${functionCall.name()} argsChars=${argsJson.length} args=$argsPreview"
        }

        val tool =
            runCatching {
                mapper.readValue(argsJson, toolClass)
            }.getOrElse { jsonError ->
                QDLog.warn(
                    log,
                    { "DefaultToolInvoker.invoke: JSON binding failed for ${functionCall.name()}: ${jsonError.message}" },
                    jsonError,
                )
                return mapOf(
                    "status" to "error",
                    "tool" to functionCall.name(),
                    "code" to "missing_or_invalid_arguments",
                    "message" to (jsonError.message ?: "Invalid tool arguments"),
                )
            }

        if (tool !is com.github.quanta_dance.quanta.plugins.intellij.shared.tools.ToolInterface<*>) {
            error("Tool '${functionCall.name()}' does not implement ToolInterface")
        }
        @Suppress("UNCHECKED_CAST")
        val typedTool = tool as com.github.quanta_dance.quanta.plugins.intellij.shared.tools.ToolInterface<Any?>
        return typedTool.execute(project) as Any
    }
}
