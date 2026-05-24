// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolExecutionItem
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolExecutionStatus
import com.intellij.openapi.application.ApplicationManager
import com.openai.models.responses.ResponseFunctionToolCall

/**
 * Builds chat-facing tool execution items from raw OpenAI function-call payloads.
 *
 * This keeps display formatting and argument-shape heuristics out of [OpenAIService] so the service
 * can focus on orchestration rather than presentation details.
 */
class ToolExecutionPresenter(
    private val mapper: ObjectMapper,
) {
    fun buildToolExecutionItem(
        functionCall: ResponseFunctionToolCall,
        status: ToolExecutionStatus,
        displaySummary: String? = null,
        errorText: String? = null,
        detailText: String? = null,
    ): ToolExecutionItem {
        val toolName = functionCall.name()
        val argsText = runCatching { functionCall.arguments() }.getOrDefault("")
        val argsJson = runCatching { mapper.readTree(argsText) }.getOrNull()
        val filePath = extractFilePath(argsJson)
        val displayText = displaySummary?.trim()?.ifBlank { null } ?: buildToolDisplayText(toolName, filePath, argsJson)
        return ToolExecutionItem(
            callId = functionCall.callId(),
            toolName = toolName,
            displayText = displayText,
            status = status,
            filePath = filePath,
            errorText = errorText,
            detailText = detailText,
        )
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

    private fun buildToolDisplayText(
        toolName: String,
        filePath: String?,
        argsJson: JsonNode?,
    ): String {
        val fileName = filePath?.substringAfterLast('/')?.substringAfterLast('\\')
        val baseText =
            when {
                toolName.contains("SessionPlan", ignoreCase = true) -> {
                    val action =
                        argsJson
                            ?.path("action")
                            ?.asText("")
                            ?.trim()
                            ?.uppercase()
                            .orEmpty()
                    when (action) {
                        "ACTIVATE" -> "Session Plan: Active"
                        "COMPLETE" -> "Session Plan: Update"
                        "DRAFT" -> "Session Plan: Draft"
                        else -> "Session Plan"
                    }
                }

                toolName.contains("ReadFile", ignoreCase = true) && !fileName.isNullOrBlank() -> {
                    "Reading $fileName"
                }

                toolName.contains("OpenFile", ignoreCase = true) && !fileName.isNullOrBlank() -> {
                    "Opening $fileName"
                }

                toolName.contains("SearchInFiles", ignoreCase = true) -> {
                    "Searching files"
                }

                toolName.contains("ListFiles", ignoreCase = true) -> {
                    "Listing files"
                }

                toolName.contains("PatchFile", ignoreCase = true) && !fileName.isNullOrBlank() -> {
                    "Patching $fileName"
                }

                toolName.contains(
                    "CreateOrUpdateFile",
                    ignoreCase = true,
                ) && !fileName.isNullOrBlank() -> {
                    "Updating $fileName"
                }

                toolName.contains("RunGoTests", ignoreCase = true) -> {
                    "Running Go tests"
                }

                else -> humanizeToolName(toolName)
            }

        if (!isDebugMode()) return baseText
        val debugSuffix = buildDebugSuffix(toolName, filePath, argsJson)
        return if (debugSuffix.isBlank()) baseText else "$baseText $debugSuffix"
    }

    private fun buildDebugSuffix(
        toolName: String,
        filePath: String?,
        argsJson: JsonNode?,
    ): String {
        val parts = mutableListOf<String>()
        if (!filePath.isNullOrBlank() && !toolName.contains(
                "ReadFile",
                ignoreCase = true
            ) && !toolName.contains("OpenFile", ignoreCase = true)
        ) {
            parts += "path=$filePath"
        }

        if (toolName.contains("ReadFile", ignoreCase = true)) {
            val from = argsJson?.path("fromLine")?.asInt()
            val toNode = argsJson?.path("toLine")
            val to = if (toNode != null && !toNode.isMissingNode && !toNode.isNull) toNode.asInt() else null
            val maxCharsNode = argsJson?.path("maxChars")
            val maxChars =
                if (maxCharsNode != null && !maxCharsNode.isMissingNode && !maxCharsNode.isNull) maxCharsNode.asInt() else null
            val strategy = argsJson?.path("strategy")?.asText("")?.trim().orEmpty()
            if (!filePath.isNullOrBlank()) parts += "path=$filePath"
            if (from != null || to != null) {
                parts += "range=${from ?: 1}..${to?.toString() ?: "EOF"}"
            }
            if (maxChars != null && maxChars > 0) parts += "maxChars=$maxChars"
            if (strategy.isNotBlank()) parts += "strategy=$strategy"
        }

        if (toolName.contains("ListFiles", ignoreCase = true)) {
            val path = argsJson?.path("path")?.asText("")?.trim().orEmpty().ifBlank { "." }
            parts += "path=$path"
        }

        if (toolName.contains("PatchFile", ignoreCase = true)) {
            val patchCount = argsJson?.path("patches")?.takeIf { it.isArray }?.size() ?: 0
            if (patchCount > 0) parts += "patches=$patchCount"
        }

        if (toolName.contains("CreateOrUpdateFile", ignoreCase = true)) {
            val patchCount = argsJson?.path("patches")?.takeIf { it.isArray }?.size() ?: 0
            if (patchCount > 0) {
                parts += "patches=$patchCount"
            } else {
                val contentNode = argsJson?.path("content")
                if (contentNode != null && !contentNode.isMissingNode && !contentNode.isNull) {
                    parts += "replace=full"
                }
            }
        }

        if (toolName.contains("RunGoTests", ignoreCase = true)) {
            val pkg = argsJson?.path("packages")?.asText("")?.trim().orEmpty().ifBlank { "./..." }
            parts += "packages=$pkg"
            val runRegex = argsJson?.path("runRegex")?.asText("")?.trim().orEmpty()
            if (runRegex.isNotBlank()) parts += "run=$runRegex"
        }

        return parts.joinToString(prefix = "[", postfix = "]", separator = ", ")
            .takeIf { it != "[]" }
            .orEmpty()
    }

    private fun isDebugMode(): Boolean =
        try {
            val app = ApplicationManager.getApplication()
            app != null && app.isInternal
        } catch (_: Throwable) {
            false
        }

    private fun humanizeToolName(toolName: String): String =
        toolName
            .replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .replace('_', ' ')
            .trim()
}
