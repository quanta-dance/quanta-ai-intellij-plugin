// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.services.openai

import com.github.quanta_dance.quanta.plugins.intellij.services.QDLog
import com.github.quanta_dance.quanta.plugins.intellij.services.ToolWindowService
import com.github.quanta_dance.quanta.plugins.intellij.tools.ToolsRegistry
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.openai.models.responses.ResponseFunctionToolCall

class DefaultToolInvoker : ToolInvoker {
    private val log = Logger.getInstance(DefaultToolInvoker::class.java)

    override fun invoke(
        project: Project,
        functionCall: ResponseFunctionToolCall,
    ): Any {
        val name = functionCall.name()
        val available = ToolsRegistry.toolsFor(project)
        try {
            QDLog.debug(log) { "Tool invoke: name=$name available=${available.size}" }
            project.service<ToolWindowService>().addDebugMessage("tool_invoke", "name=$name")
        } catch (_: Throwable) {
        }

        available.forEach { toolClass ->
            if (toolClass.simpleName == name) {
                try {
                    val args = functionCall.arguments(toolClass)
                    val out = args.execute(project)
                    try {
                        QDLog.debug(log) { "Tool ok: name=$name" }
                    } catch (_: Throwable) {
                    }
                    return out
                } catch (e: Throwable) {
                    try {
                        QDLog.warn(log, { "Tool failed: name=$name err=${e.message}" }, e)
                        project.service<ToolWindowService>().addDebugMessage("tool_failed", "name=$name err=${e.message}")
                    } catch (_: Throwable) {
                    }
                    log.error("Tool '$name' failed: ${e.message}", e)
                    throw e
                }
            }
        }
        log.warn("Unknown function tool requested: $name")
        try {
            project.service<ToolWindowService>().addDebugMessage("tool_unknown", "name=$name")
        } catch (_: Throwable) {
        }
        throw IllegalArgumentException("Unknown function: $name")
    }
}
