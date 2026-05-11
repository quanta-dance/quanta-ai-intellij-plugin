// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.quanta_dance.quanta.plugins.intellij.backend.chat.ChatConversationService
import com.github.quanta_dance.quanta.plugins.intellij.backend.logging.QDLog
import com.github.quanta_dance.quanta.plugins.intellij.backend.openai.*
import com.github.quanta_dance.quanta.plugins.intellij.backend.openai.models.OpenAIResponse
import com.github.quanta_dance.quanta.plugins.intellij.backend.openai.models.TeamAgentSpec
import com.github.quanta_dance.quanta.plugins.intellij.backend.project.ProjectVersionUtil
import com.github.quanta_dance.quanta.plugins.intellij.backend.settings.BackendQuantaSettingsState
import com.github.quanta_dance.quanta.plugins.intellij.backend.settings.QuantaAISessionState
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.PathUtils
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.ToolsRegistry
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolExecutionItem
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolExecutionStatus
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.openai.client.OpenAIClient
import com.openai.models.images.ImageGenerateParams
import com.openai.models.images.ImageModel
import com.openai.models.responses.ResponseFunctionToolCall
import com.openai.models.responses.ResponseInputItem
import com.openai.models.responses.ResponseUsage
import com.openai.models.responses.StructuredResponse
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
import java.io.File
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong

@Service(Service.Level.PROJECT)
class OpenAIService(
    private val project: Project,
) : Disposable {
    private var processingFuture: Future<*>? = null
    private var operationInProgress = false
    private val pcs = PropertyChangeSupport(this)

    @Volatile
    private var oAI: OpenAIClient = OpenAIClientProvider.get(project)

    @Volatile
    private var clientKey: Pair<String, String> =
        BackendQuantaSettingsState.instance.settings.let { it.openAiUrl to it.openAiToken }

    @Volatile
    private var modelKey: Pair<Boolean, String> =
        BackendQuantaSettingsState.instance.settings.let { (it.dynamicModelEnabled == true) to it.aiChatModel }

    private var lastResponseId: String? = QuantaAISessionState.instance.state.mainLastResponseId
    private var currentSessionId: String = UUID.randomUUID().toString()

    private val toolInvoker: ToolInvoker = DefaultToolInvoker()
    private val mapper = ObjectMapper()
    private val toolRouter = ToolRouter(project, toolInvoker, mapper)
    private val responseBuilder = ResponseBuilder(project)

    private var currentModel: String = ModelSelector.initialModel()

    private val managerLabel: String = "AI(manager)"

    @Volatile
    private var pendingTeamAddAgents: List<TeamAgentSpec>? = null

    @Volatile
    private var pendingTeamRemoveRoles: List<String>? = null

    @Volatile
    private var initialContextInjectedThisIdeSession: Boolean = false

    @Volatile
    private var lastInjectedSummaryHash: Int? = null

    @Volatile
    private var lastInjectedAgentsMdHash: Int? = null

    @Volatile
    private var lastInjectedAgentsRosterHash: Int? = null

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

    private data class UsageTotals(
        val inputTokens: AtomicLong = AtomicLong(0),
        val outputTokens: AtomicLong = AtomicLong(0),
        val totalTokens: AtomicLong = AtomicLong(0),
    )

    private val globalUsageTotals: UsageTotals = UsageTotals()

    fun getUsageSnapshot(): UsageSnapshot =
        UsageSnapshot(
            inputTokens = globalUsageTotals.inputTokens.get(),
            outputTokens = globalUsageTotals.outputTokens.get(),
            totalTokens = globalUsageTotals.totalTokens.get(),
        )

    private fun recordUsage(
        tag: String,
        usage: ResponseUsage,
        reportToUi: Boolean,
    ) {
        // Note: tag is intentionally ignored for UI; we show only global totals.
        val inTok =
            try {
                usage.inputTokens()
            } catch (_: Throwable) {
                0L
            }
        val outTok =
            try {
                usage.outputTokens()
            } catch (_: Throwable) {
                0L
            }
        val totalTok =
            try {
                usage.totalTokens()
            } catch (_: Throwable) {
                inTok + outTok
            }

        val tIn = globalUsageTotals.inputTokens.addAndGet(inTok)
        val tOut = globalUsageTotals.outputTokens.addAndGet(outTok)
        val tTot = globalUsageTotals.totalTokens.addAndGet(totalTok)

        try {
            pcs.firePropertyChange("usage", null, UsageSnapshot(tIn, tOut, tTot))
        } catch (_: Throwable) {
        }

        try {
            thisLogger().info(
                "Usage: tag=$tag in=$inTok out=$outTok total=$totalTok global(in=$tIn out=$tOut total=$tTot)",
            )
        } catch (_: Throwable) {
        }
    }

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

    private fun buildProjectDetailsSystemMessage(): String {
        val sdkVersion =
            try {
                ProjectVersionUtil.getProjectCompileVersion(project)
            } catch (_: Throwable) {
                null
            }
        val buildFiles =
            try {
                ProjectVersionUtil.getProjectBuildFiles(project)
            } catch (_: Throwable) {
                null
            }

        val filesCount =
            try {
                val basePath = PathUtils.projectRootPath(project)
                if (basePath != null) {
                    val root =
                        LocalFileSystem
                            .getInstance()
                            .findFileByPath(basePath)
                    if (root != null) {
                        var cnt = 0

                        fun dfs(v: VirtualFile) {
                            if (!v.isValid) return
                            if (v.isDirectory) {
                                v.children?.forEach { dfs(it) }
                            } else {
                                cnt++
                            }
                        }
                        dfs(root)
                        cnt
                    } else {
                        0
                    }
                } else {
                    0
                }
            } catch (_: Throwable) {
                0
            }

        val b = StringBuilder()
        b.append("Project details (auto, hidden).\n")
        b.append("Available build files: ").append(buildFiles).append('\n')
        sdkVersion?.let { b.append(it).append('\n') }
        b.append("Files in the project: ").append(filesCount)
        return b.toString()
    }

    init {
        thisLogger().warn("AI Service initialized.")
        QDLog.info(thisLogger()) { "AI Service initialized." }
        // Backend settings are read directly from BackendQuantaSettingsState.
        // The frontend owns UI refresh and display concerns.
        clientKey = BackendQuantaSettingsState.instance.settings.let { it.openAiUrl to it.openAiToken }
        modelKey =
            BackendQuantaSettingsState.instance.settings.let { (it.dynamicModelEnabled == true) to it.aiChatModel }

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
        val settings = BackendQuantaSettingsState.instance.settings
        val latestClientKey = settings.openAiUrl to settings.openAiToken
        if (latestClientKey != clientKey) {
            QDLog.info(thisLogger()) {
                "OpenAIService: rebuilding OpenAI client due to backend settings change. url=${settings.openAiUrl}, tokenPresent=${settings.openAiToken.isNotBlank()}"
            }
            oAI = OpenAIClientProvider.get(project)
            clientKey = latestClientKey
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
        thisLogger().info("Resetting AI thread state (preserve history). session=$currentSessionId")
        lastResponseId = null
        try {
            QuantaAISessionState.instance.state.mainLastResponseId = null
        } catch (_: Throwable) {
        }
        lastCtxHash = null
        initialContextInjectedThisIdeSession = false
        lastInjectedSummaryHash = null
        lastInjectedAgentsMdHash = null
        lastInjectedAgentsRosterHash = null
        lastInjectedPlanHash = null

        // Reset agents thread pointers as well; keep their transcripts.
        try {
            project.service<AgentManagerService>().resetForNewSession()
        } catch (_: Throwable) {
        }
    }

    fun newSession(): String {
        thisLogger().info("Starting new AI session. Previous session: $currentSessionId")
        val old = currentSessionId
        currentSessionId = UUID.randomUUID().toString()
        lastResponseId = null
        QuantaAISessionState.instance.state.mainLastResponseId = null
        lastCtxHash = null
        initialContextInjectedThisIdeSession = false
        lastInjectedSummaryHash = null
        lastInjectedAgentsMdHash = null
        lastInjectedAgentsRosterHash = null
        lastInjectedPlanHash = null

        // Reset global token usage counters
        try {
            globalUsageTotals.inputTokens.set(0)
            globalUsageTotals.outputTokens.set(0)
            globalUsageTotals.totalTokens.set(0)
            pcs.firePropertyChange("usage", null, UsageSnapshot(0, 0, 0))
        } catch (_: Throwable) {
        }

        // Clear persisted chat for current branch so "new session" is a clean slate on restart too
        try {
            val key = conversationKeyForMain()
            QuantaAISessionState.instance.state.conversations
                .remove(key)
        } catch (_: Throwable) {
        }

        try {
            project.service<AgentManagerService>().resetForNewSession()
        } catch (_: Throwable) {
        }
        pcs.firePropertyChange("session", old, currentSessionId)
        return currentSessionId
    }

    fun getCurrentSessionId(): String = currentSessionId

    fun getLastResponseId(): String? = lastResponseId

    fun switchToSession(
        sessionId: String,
        lastResponseId: String?,
    ) {
        currentSessionId = sessionId
        this.lastResponseId = lastResponseId
        QuantaAISessionState.instance.state.mainLastResponseId = lastResponseId
        lastCtxHash = null
        initialContextInjectedThisIdeSession = false
        lastInjectedSummaryHash = null
        lastInjectedAgentsMdHash = null
        lastInjectedAgentsRosterHash = null
        lastInjectedPlanHash = null
    }

    fun stopAndClearSession() {
        stopProcessing()
        newSession()
    }

    private fun buildAgentsRosterContext(): String {
        val agents =
            try {
                project.service<AgentManagerService>().getAgentsSnapshot()
            } catch (_: Throwable) {
                emptyList()
            }
        val b = StringBuilder()
        b.append("Agents roster (auto):\n")
        if (agents.isEmpty()) {
            b.append("- <none>")
            return b.toString()
        }
        agents.forEach { a ->
            b.append("- id=").append(a.id).append(", role=").append(a.role)
            a.model?.let { m -> b.append(", model=").append(m) }
            b.append('\n')
        }
        return b.toString().trimEnd()
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
        ensureClientIsCurrent()
        QDLog.info(thisLogger()) { "OpenAIService.createResponse: request built, sending to OpenAI" }
        val structResponse = oAI.responses().create(createParams)
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
                recordUsage(usageTag, usage, reportToUi = reportUsageToUi)
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
    ): Pair<String, String?> {
        var localPrevId = previousId
        injectBaseContextForAgentTurn(inputs, localPrevId)
        val aggregated = StringBuilder()
        val processedCallIds = mutableSetOf<String>()
        val planService = SessionPlanService(project)
        val planIsActive = runCatching { planService.isActive() }.getOrDefault(false)
        var reprocess = true
        var continuationCount = 0
        var forcePlanPersistenceAttempts = 0
        var lastPlanLoopSignature: String? = null
        var repeatedPlanLoopSignatureCount = 0
        val configuredContinuations =
            try {
                QuantaAISessionState.instance.state.maxAutomaticTurns.coerceIn(1, 100)
            } catch (_: Throwable) {
                5
            }
        val maxContinuations = if (planIsActive) maxOf(configuredContinuations, 30) else configuredContinuations
        val maxPlanPersistenceAttempts = 5

        while (reprocess) {
            reprocess = false
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
            var sessionPlanToolCalledThisTurn = false

            structResponse.output().map { item ->
                when {
                    item.isReasoning() -> {
                        val reasoning = item.asReasoning()
                        reasoning.summary().forEach { summary ->
                            val text = summary.text().trim()
                            if (text.isBlank()) return@forEach
                            aggregated.append(text).append('\n')
                            onAssistantMessage?.invoke(
                                AssistantTurnMessage(
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
                        if (!processedCallIds.add(callId)) return@map
                        if (functionCall.name().contains("SessionPlan", ignoreCase = true)) {
                            sessionPlanToolCalledThisTurn = true
                        }
                        val startedItem = buildToolExecutionItem(
                            functionCall,
                            ToolExecutionStatus.EXECUTING
                        )
                        onToolUpdate?.invoke(ToolTurnUpdate(startedItem))
                        try {
                            val toolResult =
                                project.service<ToolExecutionService>().executeToolCall(functionCall, agentLabel)
                            QDLog.info(thisLogger()) {
                                "OpenAIService.agentTurn: executed tool call name=${functionCall.name()} callId=$callId"
                            }
                            val completedItem = buildToolExecutionItem(
                                functionCall,
                                if (toolResult.succeeded) {
                                    ToolExecutionStatus.SUCCEEDED
                                } else {
                                    ToolExecutionStatus.FAILED
                                },
                                errorText = toolResult.errorText,
                                detailText = toolResult.detailText,
                            )
                            onToolUpdate?.invoke(ToolTurnUpdate(completedItem))
                            pendingToolOutputs.add(
                                ResponseInputItem.ofFunctionCallOutput(toolResult.toolOutput),
                            )
                        } catch (t: Throwable) {
                            val failedItem = buildToolExecutionItem(
                                functionCall,
                                ToolExecutionStatus.FAILED,
                                errorText = t.message,
                                detailText = t.stackTraceToString().take(2_000),
                            )
                            onToolUpdate?.invoke(ToolTurnUpdate(failedItem))
                            throw t
                        }
                    }

                    item.isMessage() -> {
                        item.message().map { m ->
                            m.content().forEach { c ->
                                val message = c.asOutputText()
                                val txt = message.summaryMessage
                                QDLog.info(thisLogger()) {
                                    "OpenAIService.agentTurn outputText: nextStep=${message.nextStep} planStatus=${message.planStatus} planNeedsUserConfirmation=${message.planNeedsUserConfirmation} completedTasks=${message.planCompletedTasks?.size ?: 0} summary='${
                                        txt.take(
                                            160
                                        )
                                    }'"
                                }

                                aggregated.append(txt).append('\n')

                                var effectivePlanStatus = message.planStatus?.uppercase()
                                val hasBlankPlanMetadataInActiveMode =
                                    planIsActive &&
                                            effectivePlanStatus.isNullOrBlank() &&
                                            message.planGoal.isNullOrBlank() &&
                                            message.planDefinitionOfDone.isNullOrBlank() &&
                                            message.planTasks.isNullOrEmpty()

                                if (hasBlankPlanMetadataInActiveMode) {
                                    if (forcePlanPersistenceAttempts < maxPlanPersistenceAttempts) {
                                        forcePlanPersistenceAttempts++
                                        continuationCount++
                                        reprocess = true
                                        inputs.add(
                                            systemMessage(
                                                "The session plan is ACTIVE, but your response omitted required structured plan fields. Return complete plan metadata and/or call SessionPlanTool before concluding the turn. Do not return DONE with blank planStatus/planGoal/planTasks while the plan is still active.",
                                            ),
                                        )
                                        return@forEach
                                    }
                                }

                                if (
                                    planIsActive &&
                                    !sessionPlanToolCalledThisTurn &&
                                    (message.planStatus?.uppercase() == "DONE" || !message.planCompletedTasks.isNullOrEmpty())
                                ) {
                                    if (forcePlanPersistenceAttempts < maxPlanPersistenceAttempts) {
                                        forcePlanPersistenceAttempts++
                                        continuationCount++
                                        reprocess = true
                                        inputs.add(
                                            systemMessage(
                                                "The session plan is ACTIVE. Before finishing or marking tasks complete, you MUST call SessionPlanTool to persist the current plan state. Do not only report planStatus/planCompletedTasks in your response fields. Update the plan file first, then continue.",
                                            ),
                                        )
                                        return@forEach
                                    }
                                }

                                try {
                                    if (!sessionPlanToolCalledThisTurn) {
                                        when (effectivePlanStatus) {
                                            "DRAFT" -> planService.applyDraftFromResponse(
                                                goal = message.planGoal,
                                                definitionOfDone = message.planDefinitionOfDone,
                                                tasks = message.planTasks,
                                            )

                                            "ACTIVE" -> {
                                                if (!planService.isActive()) {
                                                    planService.applyDraftFromResponse(
                                                        goal = message.planGoal,
                                                        definitionOfDone = message.planDefinitionOfDone,
                                                        tasks = message.planTasks,
                                                    )
                                                    planService.activate()
                                                }
                                            }

                                            "DONE" -> planService.markAllTasksDone()
                                        }
                                    }
                                } catch (_: Throwable) {
                                }

                                try {
                                    val completed = message.planCompletedTasks
                                        ?.mapNotNull { it.trim().ifBlank { null } }
                                        .orEmpty()
                                    if (completed.isNotEmpty()) {
                                        planService.markTasksDone(completed)
                                    }
                                    effectivePlanStatus = planService.getStatus().uppercase()
                                } catch (_: Throwable) {
                                }

                                if (message.nextStep?.uppercase() == "DONE" && effectivePlanStatus != "DONE") {
                                    try {
                                        if (planService.onlyPassiveTailTasksRemain()) {
                                            planService.markAllTasksDone()
                                            effectivePlanStatus = planService.getStatus().uppercase()
                                        }
                                    } catch (_: Throwable) {
                                    }
                                }

                                val activePlanStillHasWork =
                                    try {
                                        (planService.isActive() || effectivePlanStatus == "ACTIVE") && planService.hasUncheckedTasks()
                                    } catch (_: Throwable) {
                                        false
                                    }

                                val currentPlanLoopSignature =
                                    buildString {
                                        append(message.nextStep?.uppercase().orEmpty())
                                        append('|').append(effectivePlanStatus.orEmpty())
                                        append('|').append(message.planNeedsUserConfirmation == true)
                                        append('|').append(
                                            message.planCompletedTasks?.sorted()?.joinToString("||").orEmpty()
                                        )
                                        append('|').append(normalizePlanLoopSummary(txt))
                                    }
                                if (activePlanStillHasWork) {
                                    if (currentPlanLoopSignature == lastPlanLoopSignature) {
                                        repeatedPlanLoopSignatureCount++
                                    } else {
                                        lastPlanLoopSignature = currentPlanLoopSignature
                                        repeatedPlanLoopSignatureCount = 0
                                    }
                                    if (repeatedPlanLoopSignatureCount >= 1 && pendingToolOutputs.isEmpty()) {
                                        return@forEach
                                    }
                                } else {
                                    lastPlanLoopSignature = currentPlanLoopSignature
                                    repeatedPlanLoopSignatureCount = 0
                                }

                                if (planIsActive && message.nextStep?.uppercase() == "WAIT_USER") {
                                    val blockingQuestion = message.planBlockingQuestion?.trim().orEmpty()
                                    val hardBlocked =
                                        message.planNeedsUserConfirmation == true &&
                                                blockingQuestion.isNotBlank() &&
                                                !isRoutineConfirmationQuestion(blockingQuestion)
                                    if (!hardBlocked) {
                                        if (continuationCount < maxContinuations) {
                                            continuationCount++
                                            reprocess = true
                                            inputs.add(
                                                systemMessage(
                                                    "Continue executing the ACTIVE plan autonomously. Do not ask the user questions unless truly blocked by a missing external dependency or unavailable information. Do not stop for routine confirmations such as asking permission to continue, apply safe changes, inspect files, or run the next planned step.",
                                                ),
                                            )
                                            return@forEach
                                        }
                                    }
                                }

                                if (activePlanStillHasWork && message.nextStep?.uppercase() != "WAIT_USER") {
                                    if (continuationCount < maxContinuations && effectivePlanStatus != "DONE") {
                                        continuationCount++
                                        reprocess = true
                                        inputs.add(
                                            systemMessage(
                                                "The session plan is ACTIVE and still has unchecked tasks. Continue executing until tasks are completed or you are truly blocked. Avoid asking for simple confirmations while the plan can still be executed safely.",
                                            ),
                                        )
                                        return@forEach
                                    }
                                }

                                if (txt.isNotBlank()) {
                                    persistAndShow("assistant", agentLabel, txt)
                                    onAssistantMessage?.invoke(
                                        AssistantTurnMessage(
                                            text = txt,
                                            ttsSummary = message.ttsSummary?.trim()?.ifBlank { null },
                                            isReasoning = false,
                                        ),
                                    )
                                }

                                // Option 3: 3-state conversation control
                                if (message.nextStep?.uppercase() == "CONTINUE") {
                                    if (continuationCount < maxContinuations) {
                                        continuationCount++
                                        // Nudge follow-up (no tools pending)
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
        return aggregated.toString().trim() to localPrevId
    }

    private fun injectBaseContextForAgentTurn(
        inputs: MutableList<ResponseInputItem>,
        previousId: String?,
    ) {
        val needBaseContext = (previousId == null) || (!initialContextInjectedThisIdeSession)

        try {
            val ctx = ProjectAgentsFileManager(project).readAgentsFile(maxChars = 8_000)
            if (ctx.isNotBlank()) {
                val h = ctx.hashCode()
                if (needBaseContext || lastInjectedAgentsMdHash == null || lastInjectedAgentsMdHash != h) {
                    inputs.add(0, systemMessage("AGENTS.md:\n" + ctx))
                    lastInjectedAgentsMdHash = h
                }
            }
        } catch (_: Throwable) {
        }

        try {
            val roster = buildAgentsRosterContext()
            val h = roster.hashCode()
            if (needBaseContext || lastInjectedAgentsRosterHash == null || lastInjectedAgentsRosterHash != h) {
                inputs.add(0, systemMessage(roster))
                lastInjectedAgentsRosterHash = h
            }
        } catch (_: Throwable) {
        }

        if (needBaseContext) {
            initialContextInjectedThisIdeSession = true
        }
    }

    private fun buildToolExecutionItem(
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

    private fun normalizePlanLoopSummary(text: String): String =
        text.lowercase()
            .replace(Regex("\\s+"), " ")
            .replace(Regex("[^a-z0-9 _|:-]"), "")
            .trim()
            .take(240)

    private fun isRoutineConfirmationQuestion(question: String): Boolean {
        val q = question.trim().lowercase()
        if (q.isBlank()) return false
        val patterns = listOf(
            "should i",
            "do you want",
            "would you like",
            "shall i",
            "may i",
            "can i continue",
            "can i proceed",
            "please confirm",
            "confirm that i should",
            "before i continue",
            "before proceeding",
            "before i make changes",
            "before applying",
            "before running",
        )
        return patterns.any { q.contains(it) }
    }

    private fun parseMemoryFactCommand(prefix: String, text: String): Pair<String, String?>? {
        val body = text.trim().removePrefix(prefix).trim()
        if (body.isBlank()) return null
        val parts = body.split("| supersedes ", limit = 2)
        val fact = parts[0].trim()
        val supersedes = parts.getOrNull(1)?.trim()?.ifBlank { null }
        if (fact.isBlank()) return null
        return fact to supersedes
    }

    private fun handleLocalMemoryCommand(text: String): Boolean {
        val raw = text.trim()
        val normalized = raw.lowercase()
        val memory = project.service<SessionMemoryService>()
        return when {
            normalized == "refresh summary" || normalized == "/refresh summary" -> {
                memory.refreshFromCurrentState(reason = "user_command_refresh", userText = raw, force = true)
                true
            }

            normalized == "compact with memory" || normalized == "/compact with memory" -> {
                val brief = memory.compactConversationHistory()
                resetThreadStatePreservingHistory()
                runCatching {
                    project.service<ChatConversationService>()
                        .compactConversationWithBrief(brief)
                }
                true
            }

            normalized == "show session brief" || normalized == "/show session brief" -> {
                true
            }

            normalized == "restore from session memory" || normalized == "/restore from session memory" -> {
                memory.refreshFromCurrentState(
                    reason = "user_command_restore",
                    explicitNote = "Restored state from persisted session memory.",
                    force = true
                )
                resetThreadStatePreservingHistory()
                true
            }

            normalized.startsWith("pin fact ") || normalized.startsWith("/pin fact ") -> {
                val parsed =
                    parseMemoryFactCommand(if (normalized.startsWith("/pin fact ")) "/pin fact" else "pin fact", raw)
                if (parsed != null) {
                    memory.pinFact(parsed.first, parsed.second)
                }
                true
            }

            normalized.startsWith("mark root cause ") || normalized.startsWith("/mark root cause ") -> {
                val parsed =
                    parseMemoryFactCommand(
                        if (normalized.startsWith("/mark root cause ")) "/mark root cause" else "mark root cause",
                        raw,
                    )
                if (parsed != null) {
                    memory.markRootCause(parsed.first, parsed.second)
                }
                true
            }

            else -> false
        }
    }

    fun generateImage(promptText: String): String {
        val params =
            ImageGenerateParams
                .builder()
                .prompt(promptText)
                .size(ImageGenerateParams.Size._1024X1024)
                .model(ImageModel.DALL_E_3)
                .build()
        return oAI
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
