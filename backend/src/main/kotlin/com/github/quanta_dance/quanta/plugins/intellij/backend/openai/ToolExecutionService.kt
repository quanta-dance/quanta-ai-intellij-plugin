// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.openai

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.quanta_dance.quanta.plugins.intellij.backend.logging.QDLog
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.ToolsRegistry
import com.github.quanta_dance.quanta.plugins.intellij.shared.tools.ToolInterface
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.openai.models.responses.ResponseFunctionToolCall
import com.openai.models.responses.ResponseInputItem

@Service(Service.Level.PROJECT)
class ToolExecutionService(
    private val project: Project,
) {
    companion object {
        private val logger = Logger.getInstance(ToolExecutionService::class.java)
        private const val MAX_DEBUG_RESULT_CHARS = 4_000
    }

    data class ToolExecutionResult(
        val toolOutput: ResponseInputItem.FunctionCallOutput,
        val succeeded: Boolean,
        val displaySummary: String? = null,
        val detailText: String? = null,
        val errorText: String? = null,
        val filePath: String? = null,
    )

    private val objectMapper = ObjectMapper()
    private val toolMetadataMapper = jacksonObjectMapper()
    private val toolRouter = ToolRouter(project, DefaultToolInvoker(), objectMapper)

    fun canExecuteInParallel(functionCall: ResponseFunctionToolCall): Boolean = instantiateTool(functionCall)?.canBeParallel == true

    fun executeToolCall(
        functionCall: ResponseFunctionToolCall,
        agentLabel: String,
    ): ToolExecutionResult {
        val argsJson = runCatching { objectMapper.readTree(functionCall.arguments()) }.getOrNull()
        val functionResult = toolRouter.route(functionCall)
        val safeResult = sanitizeToolResultForModel(functionCall.name(), functionResult) ?: emptyMap<String, Any>()
        val succeeded = !isErrorResult(functionResult)
        val displaySummary = extractDisplaySummary(safeResult)
        val detailText = buildDetailText(safeResult, succeeded)
        val errorText = extractErrorText(functionResult)
        val filePath = extractFilePath(safeResult)
        logToolResult(functionCall, safeResult, succeeded, agentLabel)
        val toolOutput =
            ResponseInputItem.FunctionCallOutput
                .builder()
                .callId(functionCall.callId())
                .outputAsJson(safeResult)
                .build()
        return ToolExecutionResult(
            toolOutput = toolOutput,
            succeeded = succeeded,
            displaySummary = displaySummary,
            detailText = detailText,
            errorText = errorText,
            filePath = filePath,
        )
    }

    private fun instantiateTool(functionCall: ResponseFunctionToolCall): ToolInterface<*>? {
        val toolClass =
            ToolsRegistry
                .toolsFor(project)
                .firstOrNull { it.simpleName == functionCall.name() || it.name.endsWith(".${functionCall.name()}") }
                ?: return null
        return runCatching {
            toolMetadataMapper.readValue(functionCall.arguments(), toolClass)
        }.getOrNull() as? ToolInterface<*>
    }

    private fun asResultMap(result: Any?): Map<*, *>? =
        when (result) {
            null -> null
            is Map<*, *> -> result
            else -> runCatching { objectMapper.convertValue(result, Map::class.java) as? Map<*, *> }.getOrNull()
        }

    private fun isErrorResult(result: Any?): Boolean {
        val map = asResultMap(result) ?: return false
        val status = map["status"]?.toString()?.trim()?.lowercase()
        val error = map["error"]?.toString()?.trim().orEmpty()
        val errorText = map["errorText"]?.toString()?.trim().orEmpty()
        if (status == "error" || error.isNotBlank() || errorText.isNotBlank()) return true

        val text = map["text"]?.toString().orEmpty()
        if (text.startsWith("Aborted:")) return true
        if (
            text.contains("Validation:") &&
            !text.contains("Validation: No compilation errors found.") &&
            !text.contains("Validation: completed, no errors reported.") &&
            !text.contains("Validation: skipped")
        ) {
            return true
        }
        return false
    }

    private fun extractErrorText(result: Any?): String? {
        val map = asResultMap(result) ?: return null
        return map["message"]?.toString()?.takeIf { it.isNotBlank() }
            ?: map["error"]?.toString()?.takeIf { it.isNotBlank() }
            ?: map["errorText"]?.toString()?.takeIf { it.isNotBlank() }
    }

    private fun sanitizeToolResultForModel(
        toolName: String,
        result: Any?,
    ): Any? =
        if (toolName.equals("TerminalCommandTool", ignoreCase = true)) {
            truncateToolOutput(result)
        } else {
            result
        }

    private fun extractDisplaySummary(safeResult: Any?): String? {
        val map = asResultMap(safeResult) ?: return null
        return map["displaySummary"]
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.take(2_000)
    }

    private fun extractFilePath(safeResult: Any?): String? {
        val map = asResultMap(safeResult) ?: return null
        return listOf("filePath", "path")
            .firstNotNullOfOrNull { key ->
                map[key]?.toString()?.trim()?.takeIf { it.isNotBlank() }
            }
    }

    private fun classifyOutcome(
        safeResult: Any?,
        succeeded: Boolean,
    ): String {
        if (!succeeded) return "FAILED"
        val map = asResultMap(safeResult) ?: return "SUCCEEDED"
        val status =
            map["status"]
                ?.toString()
                ?.trim()
                ?.uppercase()
                .orEmpty()
        if (status == "NOOP") return "NOOP"
        val text = map["text"]?.toString().orEmpty()
        return when {
            text.contains("Relocations:") -> "RELOCATED"
            text.contains("mismatch", ignoreCase = true) -> "MISMATCH"
            else -> "SUCCEEDED"
        }
    }

    private fun logToolResult(
        functionCall: ResponseFunctionToolCall,
        safeResult: Any?,
        succeeded: Boolean,
        agentLabel: String,
    ) {
        val map = asResultMap(safeResult)
        val outcome = classifyOutcome(safeResult, succeeded)
        val filePath =
            listOf("filePath", "path").firstNotNullOfOrNull { key ->
                map?.get(key)?.toString()?.takeIf(String::isNotBlank)
            }
        val hash =
            listOf("fileHashSha256", "expectedFileHashSha256").firstNotNullOfOrNull { key ->
                map?.get(key)?.toString()?.takeIf(String::isNotBlank)
            }
        val preview =
            runCatching { objectMapper.writeValueAsString(safeResult) }
                .getOrElse { safeResult?.toString().orEmpty() }
                .let { text -> if (text.length > MAX_DEBUG_RESULT_CHARS) text.take(MAX_DEBUG_RESULT_CHARS) + "... (truncated)" else text }
        QDLog.debug(logger) {
            "ToolExecutionService.executeToolCall: agent=$agentLabel tool=${functionCall.name()} callId=${functionCall.callId()} outcome=$outcome file=${filePath ?: "<none>"} hash=${hash ?: "<none>"} result=$preview"
        }
    }

    private fun buildDetailText(
        safeResult: Any?,
        succeeded: Boolean,
    ): String {
        val map = asResultMap(safeResult)
        val details =
            if (!succeeded && map != null) {
                listOf("errorText", "message", "summary", "text", "content")
                    .mapNotNull { key -> map[key]?.toString()?.trim()?.takeIf { value -> value.isNotBlank() } }
                    .firstOrNull()
                    ?: map.entries.joinToString("\n") { (key, value) -> "$key: $value" }.trim()
            } else {
                map
                    ?.let {
                        listOf("message", "text", "summary", "content")
                            .mapNotNull { key -> it[key]?.toString()?.trim()?.takeIf { value -> value.isNotBlank() } }
                            .firstOrNull()
                            ?: ""
                    }.orEmpty()
            }

        return details.take(2_000)
    }
}
