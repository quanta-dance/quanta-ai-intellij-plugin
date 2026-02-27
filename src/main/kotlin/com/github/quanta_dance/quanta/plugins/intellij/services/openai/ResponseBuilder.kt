// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.services.openai

import com.github.quanta_dance.quanta.plugins.intellij.mcp.DynamicMcpToolProvider
import com.github.quanta_dance.quanta.plugins.intellij.mcp.McpClientService
import com.github.quanta_dance.quanta.plugins.intellij.models.OpenAIResponse
import com.github.quanta_dance.quanta.plugins.intellij.services.ToolWindowService
import com.github.quanta_dance.quanta.plugins.intellij.settings.Instructions
import com.github.quanta_dance.quanta.plugins.intellij.settings.QuantaAISettingsState
import com.github.quanta_dance.quanta.plugins.intellij.tools.ToolsRegistry.toolsFor
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
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

    private fun continuationInstructionsReminder(): String =
        """
        Follow the previously provided system and developer instructions for this conversation.
        Keep responses concise and actionable.
        """.trimIndent()


    private fun addSelectedBuiltInTools(
        builder: StructuredResponseCreateParams.Builder<OpenAIResponse>,
        allowedBuiltInNames: Set<String>?,
    ): Int {
        val all = toolsFor(project)
        val filtered =
            when {
                // null = allow all built-ins
                allowedBuiltInNames == null -> all
                // empty set = allow none
                allowedBuiltInNames.isEmpty() -> emptyList()
                else -> all.filter { cls -> allowedBuiltInNames.contains(cls.simpleName) }
            }
        filtered.forEach { builder.addTool(it) }
        return filtered.size
    }


    private fun addAllMcpTools(builder: StructuredResponseCreateParams.Builder<OpenAIResponse>) {
        val mcp = project.service<McpClientService>()
        val dyn = DynamicMcpToolProvider
        val tools = dyn.buildTools(mcp)
        tools.forEach { t ->
            try {
                builder.addTool(t)
            } catch (_: Throwable) {
            }
        }
    }

    private fun addSelectedMcpTools(
        builder: StructuredResponseCreateParams.Builder<OpenAIResponse>,
        allowedMcpNames: Set<String>?,
        // server.method
    ): Int {
        val mcp = project.service<McpClientService>()
        val dyn = DynamicMcpToolProvider
        val tools = dyn.buildTools(mcp)
        var added = 0
        when {
            // null = allow all MCP tools
            allowedMcpNames == null ->
                tools.forEach { t ->
                    try {
                        builder.addTool(t)
                        added++
                    } catch (_: Throwable) {
                    }
                }
            // empty set = allow none
            allowedMcpNames.isEmpty() -> {}
            else -> {
                tools.forEach { t ->
                    try {
                        val fn = t.asFunction().name()
                        val pair = dyn.resolve(fn)
                        if (pair != null) {
                            val name = pair.first + "." + pair.second
                            if (allowedMcpNames.contains(name)) {
                                builder.addTool(t)
                                added++
                            }
                        }
                    } catch (_: Throwable) {
                    }
                }
            }
        }
        return added
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
        val effectiveModel =
            overrideModel?.let { ModelSelector.normalize(it) } ?: ModelSelector.effectiveModel(currentModel)

        val effectiveInstructions =
            when {
                // Always honor explicit override (used for summary generation, etc.)
                overrideInstructions != null -> overrideInstructions
                // New thread: send full instructions
                previousId.isNullOrBlank() -> mergedInstructions()
                // Continuation: send a short reminder to reduce per-request tokens
                else -> continuationInstructionsReminder()
            }

        val builder =
            ResponseCreateParams.builder()
                .instructions(effectiveInstructions)
                .inputOfResponse(inputs)
                .reasoning(Reasoning.builder().effort(ReasoningEffort.LOW).build())
                .maxOutputTokens(QuantaAISettingsState.instance.state.maxTokens)
                .text(OpenAIResponse::class.java)
                .model(ChatModel.of(effectiveModel))
        if (!previousId.isNullOrBlank()) builder.previousResponseId(previousId)


        val builtInToolsCount = addSelectedBuiltInTools(builder, allowedBuiltInNames)
        val mcpToolsCount = if (includeMcp) addSelectedMcpTools(builder, allowedMcpNames) else 0

        try {
            if (QuantaAISettingsState.instance.state.debugEnabled) {
                val approxInputChars =
                    try {
                        inputs.sumOf { it.toString().length }
                    } catch (_: Throwable) {
                        -1
                    }
                val previousIdNull = previousId.isNullOrBlank()
                val msg =
                    "model=$effectiveModel previousIdNull=$previousIdNull " +
                            "instrChars=${effectiveInstructions.length} inputItems=${inputs.size} inputApproxChars=$approxInputChars " +
                            "builtInTools=$builtInToolsCount mcpTools=$mcpToolsCount"

                project.service<ToolWindowService>().addDebugMessage("request_meta", msg)
                thisLogger().info("RequestMeta: $msg")
            }
        } catch (_: Throwable) {
        }

        return builder

    }
}
