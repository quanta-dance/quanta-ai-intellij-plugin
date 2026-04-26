// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.openai

import com.github.quanta_dance.quanta.plugins.intellij.services.ToolWindowService
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.openai.models.responses.ResponseFunctionToolCall
import com.openai.models.responses.ResponseInputItem

@Service(Service.Level.PROJECT)
class ToolExecutionService(
    private val project: Project,
) {
    private val toolRouter = ToolRouter(project, DefaultToolInvoker(), com.fasterxml.jackson.databind.ObjectMapper())

    fun executeToolCall(
        functionCall: ResponseFunctionToolCall,
        agentLabel: String,
    ): ResponseInputItem.FunctionCallOutput {
        project.service<ToolWindowService>().addToolingMessage(agentLabel, "Calling tool: ${functionCall.name()}")
        val functionResult = toolRouter.route(functionCall)
        val safeResult = truncateToolOutput(functionResult) ?: emptyMap<String, Any>()
        return ResponseInputItem.FunctionCallOutput
            .builder()
            .callId(functionCall.callId())
            .outputAsJson(safeResult)
            .build()
    }
}
