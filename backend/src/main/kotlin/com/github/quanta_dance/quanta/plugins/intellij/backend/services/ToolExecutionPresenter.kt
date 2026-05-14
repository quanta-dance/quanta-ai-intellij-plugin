// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolExecutionItem
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolExecutionStatus
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
        errorText: String? = null,
        detailText: String? = null,
    ): ToolExecutionItem {
        val toolName = functionCall.name()
        val argsText = runCatching { functionCall.arguments() }.getOrDefault("")
        val argsJson = runCatching { mapper.readTree(argsText) }.getOrNull()
        val filePath = extractFilePath(argsJson)
        val displayText = buildToolDisplayText(toolName, filePath, argsJson)
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

    private fun extractFilePath(argsJson: JsonNode?): String? {
        if (argsJson == null) return null
        val direct = argsJson.path("filePath").asText("").trim()
        if (direct.isNotBlank()) return direct
        val path = argsJson.path("path").asText("").trim()
        if (path.isNotBlank()) return path
        val source = argsJson.path("sourcePath").asText("").trim()
        if (source.isNotBlank()) return source
        return null
    }

    private fun buildToolDisplayText(
        toolName: String,
        filePath: String?,
        argsJson: JsonNode?,
    ): String {
        val fileName = filePath?.substringAfterLast('/')?.substringAfterLast('\\')
        return when {
            toolName.contains("SessionPlan", ignoreCase = true) -> {
                val action = argsJson?.path("action")?.asText("")?.trim()?.uppercase().orEmpty()
                when (action) {
                    "ACTIVATE" -> "Session Plan: Active"
                    "COMPLETE" -> "Session Plan: Update"
                    "DRAFT" -> "Session Plan: Draft"
                    else -> "Session Plan"
                }
            }

            toolName.contains("ReadFile", ignoreCase = true) && !fileName.isNullOrBlank() -> "Reading $fileName"
            toolName.contains("OpenFile", ignoreCase = true) && !fileName.isNullOrBlank() -> "Opening $fileName"
            toolName.contains("SearchInFiles", ignoreCase = true) -> "Searching files"
            toolName.contains("ListFiles", ignoreCase = true) -> "Listing files"
            toolName.contains("PatchFile", ignoreCase = true) && !fileName.isNullOrBlank() -> "Patching $fileName"
            toolName.contains(
                "CreateOrUpdateFile",
                ignoreCase = true
            ) && !fileName.isNullOrBlank() -> "Updating $fileName"

            else -> toolName.replace(Regex("([a-z])([A-Z])"), "$1 $2")
        }
    }
}
