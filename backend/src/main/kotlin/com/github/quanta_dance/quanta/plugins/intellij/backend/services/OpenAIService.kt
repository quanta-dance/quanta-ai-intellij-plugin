// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.quanta_dance.quanta.plugins.intellij.backend.chat.ChatConversationService
import com.github.quanta_dance.quanta.plugins.intellij.backend.logging.QDLog
import com.github.quanta_dance.quanta.plugins.intellij.backend.openai.*
import com.github.quanta_dance.quanta.plugins.intellij.backend.openai.models.OpenAIResponse
import com.github.quanta_dance.quanta.plugins.intellij.backend.openai.models.TeamAgentSpec
import com.github.quanta_dance.quanta.plugins.intellij.backend.settings.BackendRuntimeSettingsService
import com.github.quanta_dance.quanta.plugins.intellij.backend.settings.QuantaAISessionState
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.PathUtils
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.ToolsRegistry
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolExecutionItem
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.openai.client.OpenAIClient
import com.openai.models.images.ImageGenerateParams
import com.openai.models.images.ImageModel
import com.openai.models.responses.ResponseInputItem
import com.openai.models.responses.StructuredResponse
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
import java.io.File
import java.util.concurrent.ConcurrentHashMap
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
    private val projectDetailsContextBuilder = ProjectDetailsContextBuilder(project)
    private val localMemoryCommandHandler = LocalMemoryCommandHandler(
        project = project,
        resetThreadStatePreservingHistory = ::resetThreadStatePreservingHistory,
        compactConversationWithBrief = { brief ->
            project.service<ChatConversationService>().compactConversationWithBrief(brief)
        },
    )
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

    private var currentModel: String = ModelSelector.initialModel()

    private val managerLabel: String = "AI(manager)"

    @Volatile
    private var pendingTeamAddAgents: List<TeamAgentSpec>? = null

    @Volatile
    private var pendingTeamRemoveRoles: List<String>? = null

    @Volatile
    private var lastInjectedSummaryHash: Int? = null

    @Volatile
    private var lastInjectedPlanHash: Int? = null

    @Volatile
    private var toolPolicyHintInjectedThisIdeSession: Boolean = false

    @Volatile
    private var toolManifestInjectedThisIdeSession: Boolean = false

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

    private fun summaryForKey(key: String): String? =
        try {
            QuantaAISessionState.instance.state.conversationSummaries[key]
        } catch (_: Throwable) {
            null
        }

    private fun storeSummaryForKey(
        key: String,
        summary: String,
    ) {
        if (summary.isBlank()) return
        try {
            QuantaAISessionState.instance.state.conversationSummaries[key] = summary
        } catch (_: Throwable) {
        }
    }

    private fun buildHeuristicSummaryForKey(
        key: String,
        maxChars: Int = 2_000,
    ): String {
        val msgs =
            try {
                QuantaAISessionState.instance.state.conversations[key]
            } catch (_: Throwable) {
                null
            } ?: return ""

        val lastUser =
            msgs
                .asReversed()
                .firstOrNull { it.role == "user" }
                ?.text
                ?.trim()
                .orEmpty()
        val lastAssistant =
            msgs
                .asReversed()
                .firstOrNull { it.role == "assistant" }
                ?.text
                ?.trim()
                .orEmpty()
        val recentUsers =
            msgs
                .asReversed()
                .filter { it.role == "user" }
                .take(3)
                .mapNotNull {
                    it.text.trim()
                        ?.take(400)
                        ?.ifBlank { null }
                }

        val b = StringBuilder()
        if (recentUsers.isNotEmpty()) {
            b.append("Recent user requests:\n")
            recentUsers.asReversed().forEach { u -> b.append("- ").append(u.replace("\n", " ")).append('\n') }
            b.append('\n')
        }
        if (lastAssistant.isNotBlank()) {
            b.append("Last assistant response (truncated):\n")
            b.append(lastAssistant.take(800)).append('\n')
        } else if (lastUser.isNotBlank()) {
            b.append("Last user request:\n")
            b.append(lastUser.take(800)).append('\n')
        }

        val out = b.toString().trim()
        return if (out.length <= maxChars) out else out.take(maxChars) + "\n... (truncated)"
    }

    private val summaryLastRunAtMs: MutableMap<String, Long> = ConcurrentHashMap()

    private fun shouldSummarizeProactively(
        key: String,
        budgetedThisTurn: Boolean,
    ): Boolean {
        if (budgetedThisTurn) return true
        val msgs =
            try {
                QuantaAISessionState.instance.state.conversations[key]
            } catch (_: Throwable) {
                null
            } ?: return false
        return msgs.size >= 80
    }

    private fun scheduleSummaryIfNeeded(
        key: String,
        budgetedThisTurn: Boolean,
    ) {
        val now = System.currentTimeMillis()
        val last = summaryLastRunAtMs[key] ?: 0L
        val minIntervalMs = 2 * 60 * 1000L
        if (now - last < minIntervalMs) return
        if (!shouldSummarizeProactively(key, budgetedThisTurn)) return

        summaryLastRunAtMs[key] = now
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                project.service<SessionMemoryService>().refreshFromCurrentState(
                    reason = "proactive_refresh",
                    explicitNote = "Conversation approaching token limits; refreshing persisted memory.",
                    force = true,
                )
                val summary = generateSummaryWithLlm(key)
                if (summary.isNotBlank()) {
                    storeSummaryForKey(key, summary)
                }
            } catch (t: Throwable) {
                thisLogger().warn("Proactive summarization failed: ${t.message}", t)
            }
        }
    }

    private fun generateSummaryWithLlm(
        key: String,
        maxSummaryChars: Int = 2_000,
    ): String {
        val msgs =
            try {
                QuantaAISessionState.instance.state.conversations[key]
            } catch (_: Throwable) {
                null
            } ?: return ""

        val previousSummary = summaryForKey(key).orEmpty().trim()
        val tail = msgs.takeLast(30)
        val transcript =
            buildString {
                tail.forEach { m ->
                    val role = m.role.ifBlank { "unknown" }
                    val text = m.text.trim().take(800)
                    if (text.isNotBlank()) {
                        append(role.uppercase()).append(": ").append(text.replace("\n", " ")).append('\n')
                    }
                }
            }.trim()

        val instr =
            """
            You are maintaining a rolling conversation summary for an IDE assistant.
            Produce a concise summary using this exact schema:
            - Goal
            - Key decisions
            - Current state
            - Open questions
            - Next steps
            Keep it under $maxSummaryChars characters.
            """.trimIndent()

        val inputs = mutableListOf<ResponseInputItem>()
        inputs.add(systemMessage(instr))
        if (previousSummary.isNotBlank()) {
            inputs.add(systemMessage("Previous summary:\n" + previousSummary.take(1_500)))
        }
        if (transcript.isNotBlank()) {
            inputs.add(systemMessage("Recent transcript:\n" + transcript))
        }

        val (resp, _) =
            createResponse(
                inputs = inputs,
                previousId = null,
                allowedToolClassFilter = { _ -> false },
                includeMcp = false,
                usageTag = "summary",
                reportUsageToUi = false,
            )

        val out = StringBuilder()
        resp.output().forEach { item ->
            if (item.isMessage()) {
                item.message().map { m ->
                    m.content().forEach { c ->
                        try {
                            val t = c.asOutputText().summaryMessage
                            if (t.isNotBlank()) out.append(t).append('\n')
                        } catch (_: Throwable) {
                        }
                    }
                }
            }
        }

        val text = out.toString().trim()
        if (text.isBlank()) return ""
        return if (text.length <= maxSummaryChars) text else text.take(maxSummaryChars) + "\n... (truncated)"
    }

    private fun buildProjectDetailsSystemMessage(): String = projectDetailsContextBuilder.buildSystemMessage()

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

    private fun userMessage(text: String): ResponseInputItem =
        ResponseInputItem.ofMessage(
            ResponseInputItem.Message
                .builder()
                .addInputTextContent(text)
                .role(ResponseInputItem.Message.Role.USER)
                .build(),
        )

    private fun systemMessage(text: String): ResponseInputItem =
        ResponseInputItem.ofMessage(
            ResponseInputItem.Message
                .builder()
                .addInputTextContent(text)
                .role(ResponseInputItem.Message.Role.SYSTEM)
                .build(),
        )

    private fun buildBuiltInToolsManifestMessage(maxChars: Int = 2_000): String {
        val names =
            try {
                ToolsRegistry.toolsFor(project).map { toolClass -> toolClass.simpleName }.sorted()
            } catch (_: Throwable) {
                emptyList()
            }
        val text =
            if (names.isEmpty()) {
                "Available built-in tools: <unavailable>."
            } else {
                "Available built-in tools (request via requestedTools by class simple name):\n" + names.joinToString(", ")
            }
        return if (text.length <= maxChars) text else text.take(maxChars) + "\n... (truncated)"
    }

    private fun truncateSelectedText(
        text: String,
        maxChars: Int = 1200,
    ): String {
        if (text.length <= maxChars) return text
        return text.take(maxChars) + "\n... (truncated, originalChars=${text.length})"
    }


    private companion object {
        // Internal feature flag (not user-configurable). Default OFF.
        // When enabled, tools are disabled by default and the model can request tools by class name for a silent retry.
        private const val FEATURE_SILENT_TOOL_ESCALATION: Boolean = false

        private const val MAX_REQUEST_APPROX_CHARS: Int = 60_000
        private const val KEEP_PREFIX_ITEMS: Int = 3
        private const val KEEP_TAIL_ITEMS: Int = 20
        private const val TRIM_NOTICE: String = "Context trimmed due to size limits."
    }

    private fun approxChars(item: ResponseInputItem): Int =
        try {
            item.toString().length
        } catch (_: Throwable) {
            0
        }

    private fun approxTotalChars(inputs: List<ResponseInputItem>): Int {
        var total = 0
        inputs.forEach { total += approxChars(it) }
        return total
    }

    private fun budgetRequestInputs(
        inputs: MutableList<ResponseInputItem>,
        maxApproxChars: Int = MAX_REQUEST_APPROX_CHARS,
        keepPrefixItems: Int = KEEP_PREFIX_ITEMS,
        keepTailItems: Int = KEEP_TAIL_ITEMS,
    ): Boolean {
        val beforeSize = inputs.size
        val beforeChars = approxTotalChars(inputs)
        if (beforeChars <= maxApproxChars) return false

        val prefix = keepPrefixItems.coerceAtLeast(0).coerceAtMost(inputs.size)
        val minKeep = (prefix + keepTailItems.coerceAtLeast(1)).coerceAtMost(inputs.size)

        // Drop oldest items after the prefix first.
        while (inputs.size > minKeep && approxTotalChars(inputs) > maxApproxChars) {
            val idx = prefix.coerceAtMost(inputs.lastIndex)
            if (idx <= inputs.lastIndex) {
                inputs.removeAt(idx)
            } else {
                break
            }
        }

        // If still too large, keep only the tail.
        while (inputs.size > keepTailItems.coerceAtLeast(1) && approxTotalChars(inputs) > maxApproxChars) {
            inputs.removeAt(0)
        }

        // If still too large, fall back to a minimal context: trim notice + last item (usually the user message).
        if (inputs.isNotEmpty() && approxTotalChars(inputs) > maxApproxChars) {
            val last = inputs.last()
            inputs.clear()
            inputs.add(systemMessage(TRIM_NOTICE))
            inputs.add(last)
        }

        val changed = (inputs.size != beforeSize) || (approxTotalChars(inputs) != beforeChars)
        if (changed) {
            thisLogger().info(
                "Budgeted requestInputs: beforeItems=$beforeSize beforeApproxChars=$beforeChars " +
                        "afterItems=${inputs.size} afterApproxChars=${approxTotalChars(inputs)}",
            )
        }
        return changed
    }

    fun inProgress(): Boolean = operationInProgress

    fun addPropertyChangeListener(listener: PropertyChangeListener) = pcs.addPropertyChangeListener(listener)

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

    fun getCurrentSessionId(): String = sessionCoordinator.currentSessionId()

    fun getLastResponseId(): String? = sessionCoordinator.lastResponseId()

    fun switchToSession(
        sessionId: String,
        lastResponseId: String?,
    ) {
        sessionCoordinator.switchToSession(sessionId, lastResponseId)
    }

    fun stopAndClearSession() {
        stopProcessing()
        newSession()
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


    private fun handleLocalMemoryCommand(text: String): Boolean = localMemoryCommandHandler.handle(text)

    fun generateImage(promptText: String): String {
        val params =
            ImageGenerateParams
                .builder()
                .prompt(promptText)
                .size(ImageGenerateParams.Size._1024X1024)
                .model(ImageModel.DALL_E_3)
                .build()
        return requireClientReady()
            .images()
            .generate(params)
            .data()
            .orElseThrow()
            .stream()
            .flatMap { image -> image.url().stream() }
            .findFirst()
            .orElseThrow()
    }
}
