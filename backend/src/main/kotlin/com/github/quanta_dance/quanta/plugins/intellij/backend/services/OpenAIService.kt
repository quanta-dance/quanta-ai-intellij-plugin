// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.quanta_dance.quanta.plugins.intellij.backend.logging.QDLog
import com.github.quanta_dance.quanta.plugins.intellij.backend.openai.*
import com.github.quanta_dance.quanta.plugins.intellij.backend.openai.models.OpenAIResponse
import com.github.quanta_dance.quanta.plugins.intellij.backend.settings.BackendRuntimeSettingsService
import com.github.quanta_dance.quanta.plugins.intellij.backend.settings.QuantaAISessionState
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.PathUtils
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolExecutionItem
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.openai.client.OpenAIClient
import com.openai.models.responses.ResponseInputItem
import com.openai.models.responses.StructuredResponse
import java.beans.PropertyChangeSupport
import java.io.File
import java.util.concurrent.Future

@Service(Service.Level.PROJECT)
class OpenAIService(
    private val project: Project,
) : Disposable {
    private var processingFuture: Future<*>? = null
    private var operationInProgress = false
    private val pcs = PropertyChangeSupport(this)

    @Volatile
    private var oAI: OpenAIClient? = null

    @Volatile
    private var clientKey: Pair<String, String> =
        BackendRuntimeSettingsService.instance.settings.let { it.openAiUrl to it.openAiToken }

    @Volatile
    private var modelKey: Pair<Boolean, String> =
        BackendRuntimeSettingsService.instance.settings.let { (it.dynamicModelEnabled == true) to it.aiChatModel }

    private val toolInvoker: ToolInvoker = DefaultToolInvoker()
    private val mapper = ObjectMapper()
    private val toolRouter = ToolRouter(project, toolInvoker, mapper)
    private val responseBuilder = ResponseBuilder(project)
    private val contextInjector = AgentContextInjector(project, ::systemMessage)
    private val toolExecutionPresenter = ToolExecutionPresenter(mapper)
    private val usageTracker = OpenAIUsageTracker(thisLogger()) { snapshot ->
        pcs.firePropertyChange("usage", null, snapshot)
    }
    private val continuationPolicy = AgentTurnContinuationPolicy()
    private val agentTurnOrchestrator = AgentTurnOrchestrator(
        project = project,
        contextInjector = contextInjector,
        toolExecutionPresenter = toolExecutionPresenter,
        continuationPolicy = continuationPolicy,
        createResponse = ::createResponse,
        systemMessage = ::systemMessage,
        persistAndShow = ::persistAndShow,
    )
    private val sessionCoordinator = OpenAISessionCoordinator(
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

    fun getUsageSnapshot(): UsageSnapshot = usageTracker.snapshot()

    @Volatile
    private var lastCtxHash: Int? = null

    // Helper to form conversation key including git branch if available
    private fun conversationKeyForMain(): String {
        val base = "main"
        val branch =
            try {
                // Try Git IDEA API via reflection to avoid hard dependency
                val gitClass = Class.forName("git4idea.repo.GitRepositoryManager")
                val method = gitClass.getMethod("getInstance", Project::class.java)
                val mgr = method.invoke(null, project)
                val reposMethod = gitClass.getMethod("getRepositories")
                val repos = reposMethod.invoke(mgr) as java.util.List<*>
                if (repos.isNotEmpty()) {
                    val repo = repos[0]
                    val branchMethod = repo.javaClass.getMethod("getCurrentBranchName")
                    branchMethod.invoke(repo) as String? ?: "no-branch"
                } else {
                    "no-branch"
                }
            } catch (_: Throwable) {
                // Fallback to running git in project base dir
                try {
                    val basePath = PathUtils.projectRootPath(project)
                    if (basePath != null) {
                        val pb = ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD")
                        pb.directory(File(basePath))
                        pb.redirectErrorStream(true)
                        val proc = pb.start()
                        val out =
                            proc.inputStream
                                .bufferedReader()
                                .readText()
                                .trim()
                        proc.waitFor()
                        if (out.isNotBlank()) out else "no-branch"
                    } else {
                        "no-branch"
                    }
                } catch (_: Throwable) {
                    "no-branch"
                }
            }
        return "$base@${branch.replace(' ', '_')}"
    }

    private val maxPersistedMessagesPerConversation: Int = 500

    private fun persistOnly(
        role: String,
        text: String,
        responseId: String? = null,
    ) {
        try {
            val key = conversationKeyForMain()
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
        if (role.equals("assistant", ignoreCase = true) && text.isNotBlank()) {
            try {
                project.service<SessionMemoryService>().refreshFromCurrentState(
                    reason = "assistant_turn",
                    assistantText = text,
                )
            } catch (_: Throwable) {
            }
        }
    }


    init {
        thisLogger().warn("AI Service initialized.")
        QDLog.info(thisLogger()) { "AI Service initialized." }
        // Backend runtime settings are synchronized from the frontend.
        // The frontend owns persistence, UI refresh, and display concerns.
        clientKey = BackendRuntimeSettingsService.instance.settings.let { it.openAiUrl to it.openAiToken }
        modelKey =
            BackendRuntimeSettingsService.instance.settings.let { (it.dynamicModelEnabled == true) to it.aiChatModel }

        // Chat restore happens in the frontend ToolWindowService when UI is ready.
    }

    override fun dispose() {}

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

    fun stopProcessing() {
        try {
            processingFuture?.run {
                if (!isDone) {
                    cancel(true)
                    thisLogger().info("Processing was cancelled.")
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            thisLogger().warn("Interrupted exception: ", e)
        } catch (e: Exception) {
            thisLogger().error("Error while stopping processing: ", e)
        }
    }

    fun resetThreadStatePreservingHistory() {
        thisLogger().info("Resetting AI thread state (preserve history). session=${sessionCoordinator.currentSessionId()}")
        sessionCoordinator.clearLastResponseId()
        lastCtxHash = null
        contextInjector.reset()
        lastInjectedSummaryHash = null
        lastInjectedPlanHash = null

        // Reset agents thread pointers as well; keep their transcripts.
        try {
            project.service<AgentManagerService>().resetForNewSession()
        } catch (_: Throwable) {
        }
    }

    fun newSession(): String {
        thisLogger().info("Starting new AI session. Previous session: ${sessionCoordinator.currentSessionId()}")
        usageTracker.reset(reportToUi = true)
        return sessionCoordinator.newSession()
    }

    fun getLastResponseId(): String? = sessionCoordinator.lastResponseId()

    fun switchToSession(
        sessionId: String,
        lastResponseId: String?,
    ) {
        sessionCoordinator.switchToSession(sessionId, lastResponseId)
    }


    /**
     * Core request/response API used by newer code paths.
     *
     * TODO: keep this as the primary low-level API once the legacy sendMessage flow is removed.
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
        QDLog.info(thisLogger()) {
            "OpenAIService.createResponse: inputs=${inputs.size}, previousId=${previousId ?: "<none>"}, includeMcp=$includeMcp, allowedBuiltInNames=${allowedBuiltInNames?.size ?: "all"}, allowedMcpNames=${allowedMcpNames?.size ?: "all"}"
        }
        val createParams =
            responseBuilder.buildStructuredResponseParams(
                inputs = inputs,
                includeMcp = includeMcp,
                previousResponseId = previousId,
            )
        val client = requireClientReady()
        QDLog.info(thisLogger()) { "OpenAIService.createResponse: request built, sending to OpenAI" }
        val structResponse = client.responses().create(createParams)
        QDLog.info(thisLogger()) {
            "OpenAIService.createResponse: response received id=${runCatching { structResponse.id() }.getOrNull()} outputSize=${
                runCatching { structResponse.output().size }.getOrDefault(
                    -1
                )
            }"
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
    )

    /**
     * High-level agent loop that coordinates tool calls, context injection, and assistant callbacks.
     *
     * TODO: migrate any remaining legacy chat-only behavior to this path (or delete the legacy path) once
     * the old frontend entry point is removed.
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
