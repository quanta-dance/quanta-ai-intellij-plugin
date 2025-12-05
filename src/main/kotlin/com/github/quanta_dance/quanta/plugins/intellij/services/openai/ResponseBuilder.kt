// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.services.openai

import com.github.quanta_dance.quanta.plugins.intellij.mcp.DynamicMcpToolProvider
import com.github.quanta_dance.quanta.plugins.intellij.mcp.McpClientService
import com.github.quanta_dance.quanta.plugins.intellij.models.OpenAIResponse
import com.github.quanta_dance.quanta.plugins.intellij.settings.Instructions
import com.github.quanta_dance.quanta.plugins.intellij.settings.QuantaAISettingsState
import com.github.quanta_dance.quanta.plugins.intellij.tools.ToolsRegistry.toolsFor
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.openai.models.ChatModel
import com.openai.models.Reasoning
import com.openai.models.ReasoningEffort
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseInputItem
import com.openai.models.responses.StructuredResponseCreateParams

class ResponseBuilder(private val project: Project) {

    private fun mergedInstructions(): String {
        val base = Instructions.instructions
        val extra = QuantaAISettingsState.instance.state.extraInstructions?.trim().orEmpty()
        return if (extra.isNotEmpty()) base + "\n\n# User Custom Instructions\n" + extra else base
    }

    private fun addSelectedBuiltInTools(
        builder: StructuredResponseCreateParams.Builder<OpenAIResponse>,
        allowedBuiltInNames: Set<String>?,
    ) {
        val all = toolsFor(project)
        val filtered =
            if (allowedBuiltInNames == null || allowedBuiltInNames.isEmpty()) {
                all.filter { cls -> cls.simpleName == "ListToolsCatalogTool" || cls.simpleName == "SetToolScopeTool" }
            } else {
                all.filter { cls -> allowedBuiltInNames.contains(cls.simpleName) || cls.simpleName == "ListToolsCatalogTool" || cls.simpleName == "SetToolScopeTool" }
            }
        filtered.forEach { builder.addTool(it) }
    }

    private fun addSelectedMcpTools(
        builder: StructuredResponseCreateParams.Builder<OpenAIResponse>,
        allowedMcpNames: Set<String>?, // server.method
    ) {
        if (allowedMcpNames == null || allowedMcpNames.isEmpty()) return
        val mcp = project.service<McpClientService>()
        val dyn = DynamicMcpToolProvider
        val tools = dyn.buildTools(mcp)
        tools.forEach { t ->
            try {
                val fn = t.asFunction().name()
                val pair = dyn.resolve(fn)
                if (pair != null) {
                    val name = pair.first + "." + pair.second
                    if (allowedMcpNames.contains(name)) builder.addTool(t)
                }
            } catch (_: Throwable) {}
        }
    }

    fun createParamsBuilder(
        inputs: MutableList<ResponseInputItem>,
        previousId: String?,
        currentModel: String,
        overrideInstructions: String? = null,
        overrideModel: String? = null,
        allowedToolClassFilter: ((Class<*>) -> Boolean)? = null,
        includeMcp: Boolean = true,
        allowedBuiltInNames: Set<String>? = null,
        allowedMcpNames: Set<String>? = null,
    ): StructuredResponseCreateParams.Builder<OpenAIResponse> {
        val effectiveModel = overrideModel?.let { ModelSelector.normalize(it) } ?: ModelSelector.effectiveModel(currentModel)
        val builder = ResponseCreateParams.builder()
            .instructions(overrideInstructions ?: mergedInstructions())
            .inputOfResponse(inputs)
            .reasoning(Reasoning.builder().effort(ReasoningEffort.LOW).build())
            .maxOutputTokens(QuantaAISettingsState.instance.state.maxTokens)
            .text(OpenAIResponse::class.java)
            .model(ChatModel.of(effectiveModel))
        if (!previousId.isNullOrBlank()) builder.previousResponseId(previousId)

        addSelectedBuiltInTools(builder, allowedBuiltInNames)
        if (includeMcp) addSelectedMcpTools(builder, allowedMcpNames)
        return builder
    }
}
