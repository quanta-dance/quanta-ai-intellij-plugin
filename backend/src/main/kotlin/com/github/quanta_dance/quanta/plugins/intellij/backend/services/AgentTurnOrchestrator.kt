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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

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
        fun recordExecution(
            toolName: String,
            filePath: String?,
            isWrite: Boolean,
            isRead: Boolean,
        ) {
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
        val result =
            mapOf(
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

    private data class ToolExecutionPlan(
        val functionCall: ResponseFunctionToolCall,
        val callId: String,
        val filePath: String?,
        val isRead: Boolean,
        val isWrite: Boolean,
        val decision: GuardrailDecision,
        val canRunInParallel: Boolean,
    )

    private data class ToolExecutionOutcome(
        val plan: ToolExecutionPlan,
        val executionMode: String,
        val parallelBatchSize: Int = 1,
        val result: ToolExecutionService.ToolExecutionResult? = null,
        val failure: Throwable? = null,
    )

    private fun buildToolExecutionPlan(
        functionCall: ResponseFunctionToolCall,
        toolExecutionService: ToolExecutionService,
        guardrailState: TurnGuardrailState,
    ): ToolExecutionPlan {
        val filePath = extractFilePath(functionCall)
        val isRead = isReadTool(functionCall.name())
        val isWrite = isWriteTool(functionCall.name())
        val decision = evaluateGuardrails(guardrailState, functionCall)
        val canRunInParallel = decision.allowExecution && toolExecutionService.canExecuteInParallel(functionCall)
        return ToolExecutionPlan(
            functionCall = functionCall,
            callId = functionCall.callId(),
            filePath = filePath,
            isRead = isRead,
            isWrite = isWrite,
            decision = decision,
            canRunInParallel = canRunInParallel,
        )
    }

    private fun executePlannedTool(
        plan: ToolExecutionPlan,
        agentLabel: String,
        toolExecutionService: ToolExecutionService,
        executionMode: String,
        parallelBatchSize: Int = 1,
    ): ToolExecutionOutcome =
        runCatching {
            val toolResult =
                if (plan.decision.allowExecution) {
                    toolExecutionService.executeToolCall(plan.functionCall, agentLabel)
                } else {
                    QDLog.warn(thisLogger()) {
                        "OpenAIService.agentTurn: stability intervention tool=${plan.functionCall.name()} callId=${plan.callId} reason=${plan.decision.reason} executionMode=$executionMode"
                    }
                    guardrailToolResult(plan.functionCall, plan.decision.reason ?: "guardrail_blocked")
                }
            ToolExecutionOutcome(
                plan = plan,
                executionMode = executionMode,
                parallelBatchSize = parallelBatchSize,
                result = toolResult,
            )
        }.getOrElse {
            ToolExecutionOutcome(
                plan = plan,
                executionMode = executionMode,
                parallelBatchSize = parallelBatchSize,
                failure = it,
            )
        }

    private fun applyToolExecutionOutcome(
        outcome: ToolExecutionOutcome,
        guardrailState: TurnGuardrailState,
        pendingToolOutputs: MutableList<ResponseInputItem>,
        onToolUpdate: ((OpenAIService.ToolTurnUpdate) -> Unit)?,
    ) {
        val functionCall = outcome.plan.functionCall
        outcome.failure?.let { failure ->
            val failedItem =
                toolExecutionPresenter.buildToolExecutionItem(
                    functionCall,
                    ToolExecutionStatus.FAILED,
                    errorText = failure.message,
                    detailText = failure.stackTraceToString().take(2_000),
                )
            onToolUpdate?.invoke(OpenAIService.ToolTurnUpdate(failedItem))
            throw failure
        }

        val toolResult = outcome.result ?: error("Missing tool result for ${outcome.plan.callId}")
        if (outcome.plan.decision.allowExecution) {
            guardrailState.recordExecution(
                functionCall.name(),
                outcome.plan.filePath,
                isWrite = outcome.plan.isWrite,
                isRead = outcome.plan.isRead,
            )
        }
        QDLog.info(thisLogger()) {
            "OpenAIService.agentTurn: executed tool call name=${functionCall.name()} callId=${outcome.plan.callId} file=${outcome.plan.filePath ?: "<none>"} executionMode=${outcome.executionMode} parallelBatchSize=${outcome.parallelBatchSize} guardrail=${outcome.plan.decision.reason ?: "none"}"
        }
        val completedItem =
            toolExecutionPresenter.buildToolExecutionItem(
                functionCall,
                if (toolResult.succeeded) ToolExecutionStatus.SUCCEEDED else ToolExecutionStatus.FAILED,
                displaySummary = toolResult.displaySummary,
                errorText = toolResult.errorText,
                detailText = toolResult.detailText,
                filePathOverride = toolResult.filePath,
            )
        onToolUpdate?.invoke(OpenAIService.ToolTurnUpdate(completedItem))
        pendingToolOutputs.add(ResponseInputItem.ofFunctionCallOutput(toolResult.toolOutput))
    }

    private fun executeFunctionCallBatch(
        functionCalls: List<ResponseFunctionToolCall>,
        toolExecutionService: ToolExecutionService,
        guardrailState: TurnGuardrailState,
        pendingToolOutputs: MutableList<ResponseInputItem>,
        agentLabel: String,
        onToolUpdate: ((OpenAIService.ToolTurnUpdate) -> Unit)?,
    ) {
        val plans =
            functionCalls.map { functionCall ->
                val plan = buildToolExecutionPlan(functionCall, toolExecutionService, guardrailState)
                val startedItem =
                    toolExecutionPresenter.buildToolExecutionItem(
                        functionCall,
                        ToolExecutionStatus.EXECUTING,
                    )
                onToolUpdate?.invoke(OpenAIService.ToolTurnUpdate(startedItem))
                plan
            }

        var index = 0
        while (index < plans.size) {
            val plan = plans[index]
            if (!plan.canRunInParallel) {
                applyToolExecutionOutcome(
                    outcome =
                        executePlannedTool(
                            plan = plan,
                            agentLabel = agentLabel,
                            toolExecutionService = toolExecutionService,
                            executionMode = "sequential",
                        ),
                    guardrailState = guardrailState,
                    pendingToolOutputs = pendingToolOutputs,
                    onToolUpdate = onToolUpdate,
                )
                index += 1
                continue
            }

            val parallelEndExclusive =
                (index until plans.size)
                    .firstOrNull { probe -> !plans[probe].canRunInParallel }
                    ?: plans.size
            val parallelPlans = plans.subList(index, parallelEndExclusive)
            val outcomes =
                if (parallelPlans.size == 1) {
                    listOf(
                        executePlannedTool(
                            plan = parallelPlans.first(),
                            agentLabel = agentLabel,
                            toolExecutionService = toolExecutionService,
                            executionMode = "sequential_parallel_capable",
                        ),
                    )
                } else {
                    runBlocking {
                        parallelPlans
                            .map { batchPlan ->
                                async(Dispatchers.IO) {
                                    executePlannedTool(
                                        plan = batchPlan,
                                        agentLabel = agentLabel,
                                        toolExecutionService = toolExecutionService,
                                        executionMode = "parallel",
                                        parallelBatchSize = parallelPlans.size,
                                    )
                                }
                            }.awaitAll()
                    }
                }
            outcomes.forEach { outcome ->
                applyToolExecutionOutcome(
                    outcome = outcome,
                    guardrailState = guardrailState,
                    pendingToolOutputs = pendingToolOutputs,
                    onToolUpdate = onToolUpdate,
                )
            }
            index = parallelEndExclusive
        }
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
            val toolExecutionService = project.service<ToolExecutionService>()
            val outputItems = structResponse.output()
            var outputIndex = 0

            while (outputIndex < outputItems.size) {
                val item = outputItems[outputIndex]
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
                        outputIndex += 1
                    }

                    item.isFunctionCall() -> {
                        val functionCalls = mutableListOf<ResponseFunctionToolCall>()
                        var probeIndex = outputIndex
                        while (probeIndex < outputItems.size && outputItems[probeIndex].isFunctionCall()) {
                            val functionCall = outputItems[probeIndex].asFunctionCall()
                            if (processedCallIds.add(functionCall.callId())) {
                                functionCalls += functionCall
                            }
                            probeIndex += 1
                        }
                        if (functionCalls.isNotEmpty()) {
                            executeFunctionCallBatch(
                                functionCalls = functionCalls,
                                toolExecutionService = toolExecutionService,
                                guardrailState = guardrailState,
                                pendingToolOutputs = pendingToolOutputs,
                                agentLabel = agentLabel,
                                onToolUpdate = onToolUpdate,
                            )
                        }
                        outputIndex = probeIndex
                    }

                    item.isMessage() -> {
                        item.message().ifPresent { messageItem ->
                            messageItem.content().forEach { content ->
                                val message = content.asOutputText()
                                val txt =
                                    message.summaryMessage.trim().ifBlank {
                                        listOfNotNull(
                                            message.ttsSummary.trim().takeIf { it.isNotBlank() },
                                            message.planBlockingQuestion?.trim()?.takeIf { it.isNotBlank() },
                                            message.nextStep
                                                ?.trim()
                                                ?.takeIf { it.isNotBlank() }
                                                ?.let { "Next step: $it" },
                                        ).firstOrNull().orEmpty()
                                    }
                                QDLog.info(thisLogger()) {
                                    val trimmed = txt.trim()
                                    val summary = trimmed.take(160)
                                    "OpenAIService.agentTurn outputText: nextStep=${message.nextStep} " +
                                        "planNeedsUserConfirmation=${message.planNeedsUserConfirmation} " +
                                        "summaryChars=${trimmed.length} summary='$summary'"
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
                        outputIndex += 1
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
