// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.openai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.openai.models.responses.ResponseFunctionToolCall
import com.openai.models.responses.ResponseInputItem

@Service(Service.Level.PROJECT)
class ToolExecutionService(
    private val project: Project,
) {
    data class ToolExecutionResult(
        val toolOutput: ResponseInputItem.FunctionCallOutput,
        val succeeded: Boolean,
        val detailText: String? = null,
        val errorText: String? = null,
    )

    private val objectMapper = ObjectMapper()
    private val toolRouter = ToolRouter(project, DefaultToolInvoker(), objectMapper)

    fun executeToolCall(
        functionCall: ResponseFunctionToolCall,
        agentLabel: String,
    ): ToolExecutionResult {
        val argsJson = runCatching { objectMapper.readTree(functionCall.arguments()) }.getOrNull()
        val functionResult = toolRouter.route(functionCall)
        val safeResult = truncateToolOutput(functionResult) ?: emptyMap<String, Any>()
        val succeeded = !isErrorResult(functionResult)
        val detailText = buildDetailText(functionCall.name(), argsJson, safeResult, succeeded)
        val errorText = extractErrorText(functionResult)
        val toolOutput = ResponseInputItem.FunctionCallOutput
            .builder()
            .callId(functionCall.callId())
            .outputAsJson(safeResult)
            .build()
        return ToolExecutionResult(
            toolOutput = toolOutput,
            succeeded = succeeded,
            detailText = detailText,
            errorText = errorText,
        )
    }

    private fun isErrorResult(result: Any?): Boolean {
        val map = result as? Map<*, *> ?: return false
        val status = map["status"]?.toString()?.trim()?.lowercase()
        return status == "error" || map.containsKey("error") || map.containsKey("errorText")
    }

    private fun extractErrorText(result: Any?): String? {
        val map = result as? Map<*, *> ?: return null
        return map["message"]?.toString()?.takeIf { it.isNotBlank() }
            ?: map["error"]?.toString()?.takeIf { it.isNotBlank() }
            ?: map["errorText"]?.toString()?.takeIf { it.isNotBlank() }
    }

    private fun buildDetailText(
        toolName: String,
        argsJson: JsonNode?,
        safeResult: Any?,
        succeeded: Boolean,
    ): String {
        val status = if (succeeded) "Succeeded" else "Failed"
        val map = safeResult as? Map<*, *>
        val preferred = map
            ?.let {
                listOf("message", "text", "summary", "content", "path", "filePath")
                    .mapNotNull { key -> it[key]?.toString()?.trim()?.takeIf { value -> value.isNotBlank() } }
                    .firstOrNull()
            }

        val argsSummary = buildArgsSummary(toolName, argsJson)
        val details =
            if (!succeeded && map != null) {
                listOf("errorText", "message", "summary", "text", "content")
                    .mapNotNull { key -> map[key]?.toString()?.trim()?.takeIf { value -> value.isNotBlank() } }
                    .firstOrNull()
                    ?: argsSummary.ifBlank {
                        map.entries.joinToString("\n") { (key, value) -> "$key: $value" }.trim()
                    }
            } else if (argsSummary.isNotBlank()) {
                argsSummary
            } else if (preferred != null) {
                preferred
            } else {
                map
                    ?.entries
                    ?.joinToString("\n") { (key, value) -> "$key: $value" }
                    ?.trim()
                    .orEmpty()
            }

        return buildString {
            append(toolName).append(": ").append(status)
            if (details.isNotBlank()) {
                append("\n")
                append(details)
            }
        }.take(2_000)
    }

    private fun buildArgsSummary(
        toolName: String,
        argsJson: JsonNode?,
    ): String {
        if (argsJson == null) return ""
        val filePath = argsJson.path("filePath").asText("").trim().ifBlank {
            argsJson.path("path").asText("").trim()
        }
        val fromLine = argsJson.path("fromLine").asInt(-1).takeIf { it > 0 }
        val toLine = argsJson.path("toLine").asInt(-1).takeIf { it > 0 }
        return when {
            toolName.contains("ReadFile", ignoreCase = true) && filePath.isNotBlank() -> {
                val fileName = filePath.substringAfterLast('/').substringAfterLast('\\')
                when {
                    fromLine != null && toLine != null -> "Read $fileName from line $fromLine to $toLine"
                    fromLine != null -> "Read $fileName from line $fromLine"
                    else -> "Read $fileName"
                }
            }

            toolName.contains("OpenFile", ignoreCase = true) && filePath.isNotBlank() -> {
                val fileName = filePath.substringAfterLast('/').substringAfterLast('\\')
                when {
                    fromLine != null -> "Open $fileName at line $fromLine"
                    else -> "Open $fileName"
                }
            }

            else -> ""
        }
    }
}
