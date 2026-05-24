// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.ToolsRegistry
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolExecutionItem
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolExecutionStatus
import com.github.quanta_dance.quanta.plugins.intellij.shared.tools.ToolInterface
import com.github.quanta_dance.quanta.plugins.intellij.shared.tools.ToolPresentationProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.openai.models.responses.ResponseFunctionToolCall

/**
 * Builds chat-facing tool execution items from raw OpenAI function-call payloads.
 *
 * Tool-specific presentation should come from the tool itself via [ToolPresentationProvider]. This
 * presenter keeps only generic fallback behavior plus optional debug suffixes.
 */
class ToolExecutionPresenter(
    private val project: Project?,
    private val mapper: ObjectMapper,
) {
    fun buildToolExecutionItem(
        functionCall: ResponseFunctionToolCall,
        status: ToolExecutionStatus,
        displaySummary: String? = null,
        errorText: String? = null,
        detailText: String? = null,
        filePathOverride: String? = null,
    ): ToolExecutionItem {
        val toolName = functionCall.name()
        val argsText = runCatching { functionCall.arguments() }.getOrDefault("")
        val argsJson = runCatching { mapper.readTree(argsText) }.getOrNull()
        val filePath = filePathOverride ?: extractFilePath(argsJson)
        val toolPresentation = resolveToolPresentation(functionCall, status)
        val displayText =
            toolPresentation
                ?.title
                ?.trim()
                .orEmpty()
                .ifBlank { displaySummary?.trim().orEmpty() }
                .ifBlank { buildFallbackDisplayText(toolName, filePath) }
        val effectiveDetailText =
            detailText?.trim()?.ifBlank { null }
                ?: toolPresentation?.detail?.trim()?.ifBlank { null }
        return ToolExecutionItem(
            callId = functionCall.callId(),
            toolName = toolName,
            displayText = displayText,
            status = status,
            filePath = filePath,
            errorText = errorText,
            detailText = effectiveDetailText,
        )
    }

    private fun resolveToolPresentation(
        functionCall: ResponseFunctionToolCall,
        status: ToolExecutionStatus,
    ) = instantiateTool(functionCall)
        ?.let { tool -> (tool as? ToolPresentationProvider)?.presentation(status) }

    private fun instantiateTool(functionCall: ResponseFunctionToolCall): ToolInterface<*>? {
        val availableProject = project ?: return null
        val toolClass =
            ToolsRegistry
                .toolsFor(availableProject)
                .firstOrNull { it.simpleName == functionCall.name() || it.name.endsWith(".${functionCall.name()}") }
                ?: return null
        return runCatching {
            mapper.readValue(functionCall.arguments(), toolClass)
        }.getOrNull() as? ToolInterface<*>
    }

    private fun sanitizeCandidatePath(path: String?): String? {
        val trimmed = path?.trim().orEmpty()
        if (trimmed.isBlank()) return null
        if (trimmed == "?" || trimmed == "??") return null
        if (trimmed.contains('?')) return null
        return trimmed
    }

    private fun extractFilePath(argsJson: JsonNode?): String? {
        if (argsJson == null) return null
        return listOf("filePath", "path", "sourcePath")
            .firstNotNullOfOrNull { key -> sanitizeCandidatePath(argsJson.path(key).asText("")) }
    }

    private fun buildFallbackDisplayText(
        toolName: String,
        filePath: String?,
    ): String {
        val baseText = humanizeToolName(toolName)
        if (!isDebugMode()) return baseText
        val debugSuffix = buildDebugSuffix(toolName, filePath)
        return if (debugSuffix.isBlank()) baseText else "$baseText $debugSuffix"
    }

    private fun buildDebugSuffix(
        toolName: String,
        filePath: String?,
    ): String {
        val parts = mutableListOf<String>()
        if (!filePath.isNullOrBlank()) parts += "path=$filePath"
        return parts
            .joinToString(prefix = "[", postfix = "]", separator = ", ")
            .takeIf { it != "[]" }
            .orEmpty()
    }

    private fun humanizeToolName(toolName: String): String =
        toolName
            .replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .replace('_', ' ')
            .trim()
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

    private fun isDebugMode(): Boolean =
        runCatching {
            ApplicationManager.getApplication()?.isUnitTestMode == true ||
                java.lang.Boolean.getBoolean("quanta.toolWindow.debugLinks")
        }.getOrDefault(false)
}
