// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.quanta_dance.quanta.plugins.intellij.backend.logging.QDLog
import com.github.quanta_dance.quanta.plugins.intellij.backend.openai.ToolExecutionService
import com.github.quanta_dance.quanta.plugins.intellij.backend.openai.models.OpenAIResponse
import com.github.quanta_dance.quanta.plugins.intellij.backend.settings.BackendRuntimeSettingsService
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolExecutionStatus
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.openai.models.responses.ResponseFunctionToolCall
import com.openai.models.responses.ResponseInputItem
import com.openai.models.responses.StructuredResponse

/**
 * Runs the main agent-turn orchestration loop for OpenAI-backed chat turns.
 *
 * `OpenAIService` remains the facade that owns collaborators and public API surface, while this
 * class owns the reprocessing loop, tool execution wiring, and plan-continuation policy.
 */
class AgentTurnOrchestrator(
    private val project: Project,
    private val contextInjector: AgentContextInjector,
    private val toolExecutionPresenter: ToolExecutionPresenter,
    private val continuationPolicy: AgentTurnContinuationPolicy,
    private val createResponse: (
        inputs: MutableList<ResponseInputItem>,
        previousId: String?,
        overrideInstructions: String?,
        overrideModel: String?,
        allowedToolClassFilter: ((Class<*>) -> Boolean)?,
        includeMcp: Boolean,
        allowedBuiltInNames: Set<String>?,
        allowedMcpNames: Set<String>?,
    ) -> Pair<StructuredResponse<OpenAIResponse>, String?>,
    private val systemMessage: (String) -> ResponseInputItem,
    private val persistAndShow: (role: String, agentLabel: String, text: String) -> Unit,
) {
    private val objectMapper = ObjectMapper()

    private data class GuardrailDecision(
        val allowExecution: Boolean,
        val reason: String? = null,
    )

    private data class TurnGuardrailState(
        var guardrailSkips: Int = 0,
        val touchedFiles: MutableSet<String> = linkedSetOf(),
        val toolNames: MutableList<String> = mutableListOf(),
    ) {
        fun recordExecution(toolName: String, filePath: String?, isWrite: Boolean, isRead: Boolean) {
            toolNames += toolName
            filePath?.let { touchedFiles += it }
        }
    }


    private fun sanitizeCandidatePath(path: String?): String? {
        val trimmed = path?.trim().orEmpty()
        if (trimmed.isBlank()) return null
        if (trimmed == "?" || trimmed == "??") return null
        if (trimmed.contains('?')) return null
        return trimmed
    }

    private fun extractFilePath(functionCall: ResponseFunctionToolCall): String? {
        val args = runCatching { objectMapper.readTree(functionCall.arguments()) }.getOrNull() ?: return null
        return listOf("filePath", "path", "sourcePath")
            .firstNotNullOfOrNull { key -> sanitizeCandidatePath(args.path(key).asText("")) }
    }

    private fun isReadTool(toolName: String): Boolean =
        toolName.equals("ReadFile", ignoreCase = true) ||
                toolName.equals("ReadFileContent", ignoreCase = true) ||
                toolName.equals("ReadPsiBlockAtPosition", ignoreCase = true)

    private fun isWriteTool(toolName: String): Boolean =
        toolName.equals("PatchFile", ignoreCase = true) ||
                toolName.equals("CreateOrUpdateFile", ignoreCase = true) ||
                toolName.equals("DeleteFileTool", ignoreCase = true) ||
                toolName.equals("CopyFileOrDirectoryTool", ignoreCase = true)

    private fun evaluateGuardrails(
        state: TurnGuardrailState,
        functionCall: ResponseFunctionToolCall,
    ): GuardrailDecision = GuardrailDecision(true)

    private fun guardrailToolResult(
        functionCall: ResponseFunctionToolCall,
        reason: String,
    ): ToolExecutionService.ToolExecutionResult {
        val toolName = functionCall.name()
        val message = "Skipped by orchestrator guardrail: $reason"
        val result = mapOf(
            "status" to "noop",
            "tool" to toolName,
            "code" to reason,
            "message" to message,
            "summary" to message,
        )
        val toolOutput =
            ResponseInputItem.FunctionCallOutput
                .builder()
                .callId(functionCall.callId())
                .outputAsJson(result)
                .build()
        return ToolExecutionService.ToolExecutionResult(
            toolOutput = toolOutput,
            succeeded = true,
            displaySummary = "$toolName: Skipped\n$message",
            detailText = "$toolName: Skipped\n$message",
            errorText = null,
        )
    }

    private fun logTurnSummary(
        state: TurnGuardrailState,
        agentLabel: String,
        responseId: String?,
    ) {
        QDLog.info(thisLogger()) {
            "OpenAIService.agentTurn summary: agent=$agentLabel responseId=${responseId ?: "<none>"} " +
                    "toolCalls=${state.toolNames.size} skipped=${state.guardrailSkips} " +
                    "files=${state.touchedFiles.size} tools=${state.toolNames.distinct().joinToString(",") { it }}"
        }
    }

    fun run(
        inputs: MutableList<ResponseInputItem>,
        previousId: String?,
        overrideInstructions: String? = null,
        overrideModel: String? = null,
        allowedToolClassFilter: ((Class<*>) -> Boolean)? = null,
        includeMcp: Boolean = true,
        agentLabel: String = "AI(agent)",
        allowedBuiltInNames: Set<String>? = null,
        allowedMcpNames: Set<String>? = null,
        onAssistantMessage: ((OpenAIService.AssistantTurnMessage) -> Unit)? = null,
        onToolUpdate: ((OpenAIService.ToolTurnUpdate) -> Unit)? = null,
    ): Pair<String, String?> {
        var localPrevId = previousId
        contextInjector.injectBaseContextForAgentTurn(inputs, localPrevId)
        val aggregated = StringBuilder()
        val processedCallIds = mutableSetOf<String>()
        val guardrailState = TurnGuardrailState()
        val planService = project.service<SessionPlanService>()
        val activePlanCoordinator = ActiveSessionPlanCoordinator(continuationPolicy)
        var reprocess = true
        var loopState = ActivePlanLoopState()
        val configuredContinuations =
            try {
                BackendRuntimeSettingsService.instance.settings.maxAutomaticTurns
                    .coerceIn(1, 100)
            } catch (_: Throwable) {
                5
            }
        val maxPlanToolEnforcementAttempts = 5

        while (reprocess) {
            reprocess = false
            val planIsActiveAtTurnStart = runCatching { planService.isActive() }.getOrDefault(false)
            val maxContinuations =
                if (planIsActiveAtTurnStart) maxOf(configuredContinuations, 30) else configuredContinuations
            val (structResponse, newId) =
                createResponse(
                    inputs,
                    localPrevId,
                    overrideInstructions,
                    overrideModel,
                    allowedToolClassFilter,
                    includeMcp,
                    allowedBuiltInNames,
                    allowedMcpNames,
                )
            localPrevId = newId
            inputs.clear()
            val pendingToolOutputs = mutableListOf<ResponseInputItem>()

            structResponse.output().forEach { item ->
                when {
                    item.isReasoning() -> {
                        val reasoning = item.asReasoning()
                        reasoning.summary().forEach { summary ->
                            val text = summary.text().trim()
                            if (text.isBlank()) return@forEach
                            aggregated.append(text).append('\n')
                            onAssistantMessage?.invoke(
                                OpenAIService.AssistantTurnMessage(
                                    text = text,
                                    ttsSummary = null,
                                    isReasoning = true,
                                ),
                            )
                        }
                    }

                    item.isFunctionCall() -> {
                        val functionCall: ResponseFunctionToolCall = item.asFunctionCall()
                        val callId = functionCall.callId()
                        if (!processedCallIds.add(callId)) return@forEach

                        val startedItem =
                            toolExecutionPresenter.buildToolExecutionItem(
                                functionCall,
                                ToolExecutionStatus.EXECUTING,
                            )
                        onToolUpdate?.invoke(OpenAIService.ToolTurnUpdate(startedItem))
                        try {
                            val filePath = extractFilePath(functionCall)
                            val isRead = isReadTool(functionCall.name())
                            val isWrite = isWriteTool(functionCall.name())
                            val decision = evaluateGuardrails(guardrailState, functionCall)
                            val toolResult =
                                if (decision.allowExecution) {
                                    project.service<ToolExecutionService>().executeToolCall(functionCall, agentLabel)
                                } else {
                                    QDLog.warn(thisLogger()) {
                                        "OpenAIService.agentTurn: stability intervention tool=${functionCall.name()} callId=$callId reason=${decision.reason}"
                                    }
                                    guardrailToolResult(functionCall, decision.reason ?: "guardrail_blocked")
                                }
                            if (decision.allowExecution) {
                                guardrailState.recordExecution(
                                    functionCall.name(),
                                    filePath,
                                    isWrite = isWrite,
                                    isRead = isRead
                                )
                            }
                            QDLog.info(thisLogger()) {
                                "OpenAIService.agentTurn: executed tool call name=${functionCall.name()} callId=$callId file=${filePath ?: "<none>"} guardrail=${decision.reason ?: "none"}"
                            }
                            val completedItem =
                                toolExecutionPresenter.buildToolExecutionItem(
                                    functionCall,
                                    if (toolResult.succeeded) ToolExecutionStatus.SUCCEEDED else ToolExecutionStatus.FAILED,
                                    displaySummary = toolResult.displaySummary,
                                    errorText = toolResult.errorText,
                                    detailText = toolResult.detailText,
                                )
                            onToolUpdate?.invoke(OpenAIService.ToolTurnUpdate(completedItem))
                            pendingToolOutputs.add(ResponseInputItem.ofFunctionCallOutput(toolResult.toolOutput))
                        } catch (t: Throwable) {
                            val failedItem =
                                toolExecutionPresenter.buildToolExecutionItem(
                                    functionCall,
                                    ToolExecutionStatus.FAILED,
                                    errorText = t.message,
                                    detailText = t.stackTraceToString().take(2_000),
                                )
                            onToolUpdate?.invoke(OpenAIService.ToolTurnUpdate(failedItem))
                            throw t
                        }
                    }

                    item.isMessage() -> {
                        item.message().ifPresent { messageItem ->
                            messageItem.content().forEach { content ->
                                val message = content.asOutputText()
                                val txt = message.summaryMessage
                                QDLog.info(thisLogger()) {
                                    val trimmed = txt.trim()
                                    val summary = trimmed.take(160)
                                    "OpenAIService.agentTurn outputText: nextStep=${message.nextStep} " +
                                            "planNeedsUserConfirmation=${message.planNeedsUserConfirmation} " +
                                            "summaryChars=${trimmed.length} summary='${summary}'"
                                }

                                aggregated.append(txt).append('\n')

                                val evaluation =
                                    activePlanCoordinator.evaluateAssistantMessage(
                                        message = message,
                                        summaryText = txt,
                                        planAtEvaluation = planService.loadPlanSnapshot(),
                                        planWasActiveAtTurnStart = planIsActiveAtTurnStart,
                                        pendingToolOutputsEmpty = pendingToolOutputs.isEmpty(),
                                        maxContinuations = maxContinuations,
                                        maxPlanToolEnforcementAttempts = maxPlanToolEnforcementAttempts,
                                        loopState = loopState,
                                    )
                                loopState = evaluation.loopState
                                val effectivePlanStatus = evaluation.effectivePlanStatus

                                evaluation.retryInstruction?.let { retryInstruction ->
                                    reprocess = true
                                    inputs.add(systemMessage(retryInstruction))
                                    return@forEach
                                }

                                if (txt.isNotBlank()) {
                                    persistAndShow("assistant", agentLabel, txt)
                                    onAssistantMessage?.invoke(
                                        OpenAIService.AssistantTurnMessage(
                                            text = txt,
                                            ttsSummary = message.ttsSummary?.trim()?.ifBlank { null },
                                            isReasoning = false,
                                        ),
                                    )
                                }

                                if (message.nextStep?.uppercase() == "CONTINUE") {
                                    if (loopState.continuationCount < maxContinuations) {
                                        loopState = loopState.copy(continuationCount = loopState.continuationCount + 1)
                                        inputs.add(systemMessage("Continue."))
                                        reprocess = true
                                    }
                                }
                            }
                        }
                    }
                }
            }
            val hasPending = pendingToolOutputs.isNotEmpty()
            if (hasPending) inputs.addAll(pendingToolOutputs)
            if (hasPending) reprocess = true
        }
        logTurnSummary(guardrailState, agentLabel, localPrevId)
        return aggregated.toString().trim() to localPrevId
    }
}
