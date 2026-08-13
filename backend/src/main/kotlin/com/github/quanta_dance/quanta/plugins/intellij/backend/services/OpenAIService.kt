// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.quanta_dance.quanta.plugins.intellij.backend.logging.QDLog
import com.github.quanta_dance.quanta.plugins.intellij.backend.openai.OpenAIClientProvider
import com.github.quanta_dance.quanta.plugins.intellij.backend.openai.ResponseBuilder
import com.github.quanta_dance.quanta.plugins.intellij.backend.openai.models.OpenAIResponse
import com.github.quanta_dance.quanta.plugins.intellij.backend.settings.BackendRuntimeSettingsService
import com.github.quanta_dance.quanta.plugins.intellij.backend.settings.QuantaAISessionState
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolExecutionItem
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.openai.client.OpenAIClient
import com.openai.models.responses.ResponseInputItem
import com.openai.models.responses.StructuredResponse
import java.beans.PropertyChangeSupport

@Service(Service.Level.PROJECT)
class OpenAIService(
    private val project: Project,
) : Disposable {
    private val pcs = PropertyChangeSupport(this)

    @Volatile
    private var oAI: OpenAIClient? = null

    @Volatile
    private var clientKey: Pair<String, String> =
        BackendRuntimeSettingsService.instance.settings.let { it.openAiUrl to it.openAiToken }

    @Volatile
    private var modelKey: Pair<Boolean, String> =
        BackendRuntimeSettingsService.instance.settings.let { (it.dynamicModelEnabled == true) to it.aiChatModel }

    private val mapper = ObjectMapper()
    private val responseBuilder = ResponseBuilder(project)
    private val contextInjector = AgentContextInjector(project, ::systemMessage)
    private val toolExecutionPresenter = ToolExecutionPresenter(project, mapper)
    private val usageTracker =
        OpenAIUsageTracker(thisLogger()) { snapshot ->
            pcs.firePropertyChange("usage", null, snapshot)
        }
    private val continuationPolicy = AgentTurnContinuationPolicy()
    private val agentTurnOrchestrator =
        AgentTurnOrchestrator(
            project = project,
            contextInjector = contextInjector,
            toolExecutionPresenter = toolExecutionPresenter,
            continuationPolicy = continuationPolicy,
            createResponse = ::createResponse,
            systemMessage = ::systemMessage,
            persistAndShow = ::persistAndShow,
        )
    private val sessionCoordinator =
        OpenAISessionCoordinator(
            project = project,
            onSessionStateReset = {
                lastCtxHash = null
                contextInjector.reset()
                lastInjectedSummaryHash = null
                lastInjectedPlanHash = null
            },
            onSessionChanged = { oldSessionId, newSessionId ->
                pcs.firePropertyChange("session", oldSessionId, newSessionId)
            },
        )

    @Volatile
    private var lastInjectedSummaryHash: Int? = null

    @Volatile
    private var lastInjectedPlanHash: Int? = null

    data class UsageSnapshot(
        val inputTokens: Long,
        val outputTokens: Long,
        val totalTokens: Long,
    )

    // TODO: currently unused after the service refactor, but keep this facade as the stable read path
    // if we surface token usage in the UI/diagnostics again. Remove only if we intentionally drop
    // usage snapshot reporting as a supported service capability.
    fun getUsageSnapshot(): UsageSnapshot = usageTracker.snapshot()

    @Volatile
    private var lastCtxHash: Int? = null

    private val mainConversationKeyResolver = MainConversationKeyResolver(project)

    private val maxPersistedMessagesPerConversation: Int = 500

    private fun persistOnly(
        role: String,
        text: String,
        responseId: String? = null,
    ) {
        try {
            val key = mainConversationKeyResolver.conversationKeyForMain()
            val state = QuantaAISessionState.instance.state
            val list = state.conversations.getOrPut(key) { mutableListOf() }
            list.add(QuantaAISessionState.PersistedMessage(System.currentTimeMillis(), role, text, responseId))
            // Retention: keep only last N messages to avoid settings bloat
            if (list.size > maxPersistedMessagesPerConversation) {
                val drop = list.size - maxPersistedMessagesPerConversation
                repeat(drop) { if (list.isNotEmpty()) list.removeAt(0) }
            }
        } catch (e: Throwable) {
            thisLogger().warn("Failed to persist chat message: ", e)
        }
    }

    private fun persistAndShow(
        role: String,
        label: String,
        text: String,
        responseId: String? = null,
    ) {
        persistOnly(role, text, responseId)
    }

    init {
        QDLog.info(thisLogger()) { "AI Service initialized (project service ready)." }
        // Backend runtime settings are synchronized from the frontend.
        // The frontend owns persistence, UI refresh, and display concerns.
        clientKey = BackendRuntimeSettingsService.instance.settings.let { it.openAiUrl to it.openAiToken }
        modelKey =
            BackendRuntimeSettingsService.instance.settings.let { (it.dynamicModelEnabled == true) to it.aiChatModel }

        // Chat restore happens in the frontend ToolWindowService when UI is ready.
    }

    override fun dispose() {
        oAI?.close()
        oAI = null
    }

    /**
     * Refreshes the cached OpenAI client if backend settings changed after service initialization.
     *
     * This protects split-mode sessions where the frontend synchronizes URL/token only after the
     * backend service has already been created.
     */
    private fun ensureClientIsCurrent() {
        val settings = BackendRuntimeSettingsService.instance.settings
        val latestClientKey = settings.openAiUrl to settings.openAiToken
        if (!BackendRuntimeSettingsService.instance.hasFrontendSync()) {
            return
        }
        if (oAI == null || latestClientKey != clientKey) {
            QDLog.info(thisLogger()) {
                "OpenAIService: rebuilding OpenAI client due to backend settings change. url=${settings.openAiUrl}, tokenPresent=${settings.openAiToken.isNotBlank()}"
            }
            oAI?.close()
            oAI = OpenAIClientProvider.get(project)
            clientKey = latestClientKey
        }
    }

    private fun requireClientReady(): OpenAIClient {
        ensureClientIsCurrent()
        BackendRuntimeSettingsService.instance.requireFrontendSync("OpenAI requests")
        return checkNotNull(oAI) {
            "OpenAI client was not initialized after frontend settings sync completed."
        }
    }

    private fun systemMessage(text: String): ResponseInputItem =
        ResponseInputItem.ofMessage(
            ResponseInputItem.Message
                .builder()
                .addInputTextContent(text)
                .role(ResponseInputItem.Message.Role.SYSTEM)
                .build(),
        )

    fun getLastResponseId(): String? = sessionCoordinator.lastResponseId()

    fun switchToSession(
        sessionId: String,
        lastResponseId: String?,
    ) {
        sessionCoordinator.switchToSession(sessionId, lastResponseId)
    }

    /**
     * Core request/response API used by higher-level turn orchestration and selected backend flows.
     *
     * Supports per-request instruction/model overrides and tool exposure controls before the higher-
     * level `agentTurn(...)` loop layers continuation and callback behavior on top.
     */
    fun createResponse(
        inputs: MutableList<ResponseInputItem>,
        previousId: String?,
        overrideInstructions: String? = null,
        overrideModel: String? = null,
        allowedToolClassFilter: ((Class<*>) -> Boolean)? = null,
        includeMcp: Boolean = true,
        allowedBuiltInNames: Set<String>? = null,
        allowedMcpNames: Set<String>? = null,
        usageTag: String = "main",
        reportUsageToUi: Boolean = true,
    ): Pair<StructuredResponse<OpenAIResponse>, String?> {
        QDLog.debug(thisLogger()) {
            "OpenAIService.createResponse: inputs=${inputs.size}, previousId=${previousId ?: "<none>"}, includeMcp=$includeMcp, " +
                "allowedBuiltInNames=${allowedBuiltInNames?.size ?: "all"}, allowedMcpNames=${allowedMcpNames?.size ?: "all"}"
        }
        val createParams =
            responseBuilder.buildStructuredResponseParams(
                inputs = inputs,
                includeMcp = includeMcp,
                previousResponseId = previousId,
                overrideInstructions = overrideInstructions,
                overrideModel = overrideModel,
                allowedToolClassFilter = allowedToolClassFilter,
                allowedBuiltInNames = allowedBuiltInNames,
                allowedMcpNames = allowedMcpNames,
            )
        val client = requireClientReady()
        QDLog.debug(thisLogger()) { "OpenAIService.createResponse: request built, sending to OpenAI" }
        val structResponse = client.responses().create(createParams)
        QDLog.info(thisLogger()) {
            val responseId = runCatching { structResponse.id() }.getOrNull()
            val outputSize = runCatching { structResponse.output().size }.getOrDefault(-1)
            val usage = runCatching { structResponse.usage().orElse(null) }.getOrNull()
            val usageSummary =
                usage?.let { " input=${it.inputTokens()} output=${it.outputTokens()} total=${it.totalTokens()}" } ?: ""
            "OpenAIService.createResponse: response received id=$responseId outputSize=$outputSize$usageSummary"
        }

        try {
            val usage = structResponse.usage().orElse(null)
            if (usage != null) {
                usageTracker.recordUsage(usageTag, usage, reportToUi = reportUsageToUi)
            }
        } catch (_: Throwable) {
        }

        val id =
            try {
                structResponse.id()
            } catch (_: Throwable) {
                null
            }
        return structResponse to id
    }

    data class AssistantTurnMessage(
        val text: String,
        val ttsSummary: String? = null,
        val isReasoning: Boolean = false,
    )

    data class ToolTurnUpdate(
        val item: ToolExecutionItem,
        val responseId: String?,
    )

    /**
     * High-level agent-turn facade over [AgentTurnOrchestrator].
     *
     * This is the main entry point for manager and delegated-agent turns. Keep the orchestration logic
     * in the collaborator and keep this method as the stable service-level API.
     */
    fun agentTurn(
        inputs: MutableList<ResponseInputItem>,
        previousId: String?,
        overrideInstructions: String? = null,
        overrideModel: String? = null,
        allowedToolClassFilter: ((Class<*>) -> Boolean)? = null,
        includeMcp: Boolean = true,
        agentLabel: String = "AI(agent)",
        allowedBuiltInNames: Set<String>? = null,
        allowedMcpNames: Set<String>? = null,
        onAssistantMessage: ((AssistantTurnMessage) -> Unit)? = null,
        onToolUpdate: ((ToolTurnUpdate) -> Unit)? = null,
    ): Pair<String, String?> =
        agentTurnOrchestrator.run(
            inputs = inputs,
            previousId = previousId,
            overrideInstructions = overrideInstructions,
            overrideModel = overrideModel,
            allowedToolClassFilter = allowedToolClassFilter,
            includeMcp = includeMcp,
            agentLabel = agentLabel,
            allowedBuiltInNames = allowedBuiltInNames,
            allowedMcpNames = allowedMcpNames,
            onAssistantMessage = onAssistantMessage,
            onToolUpdate = onToolUpdate,
        )
}
