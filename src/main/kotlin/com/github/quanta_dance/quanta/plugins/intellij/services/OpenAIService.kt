// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.services

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.quanta_dance.quanta.plugins.intellij.models.OpenAIResponse
import com.github.quanta_dance.quanta.plugins.intellij.project.CurrentFileContextProvider
import com.github.quanta_dance.quanta.plugins.intellij.project.ProjectVersionUtil
import com.github.quanta_dance.quanta.plugins.intellij.services.openai.*
import com.github.quanta_dance.quanta.plugins.intellij.services.ui.DelayedSpinner
import com.github.quanta_dance.quanta.plugins.intellij.services.ui.Notifications
import com.github.quanta_dance.quanta.plugins.intellij.settings.QuantaAISettingsListener
import com.github.quanta_dance.quanta.plugins.intellij.settings.QuantaAISettingsState
import com.github.quanta_dance.quanta.plugins.intellij.tools.ToolsRegistry
import com.intellij.notification.NotificationType
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
import com.openai.models.responses.ResponseUsage
import com.openai.models.responses.StructuredResponse
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
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
    private var clientKey: Pair<String, String> = QuantaAISettingsState.instance.state.let { it.host to it.token }

    @Volatile
    private var modelKey: Pair<Boolean, String> =
        QuantaAISettingsState.instance.state.let { (it.dynamicModelEnabled == true) to it.aiChatModel }

    private var lastResponseId: String? = QuantaAISettingsState.instance.state.mainLastResponseId
    private var currentSessionId: String = UUID.randomUUID().toString()

    private val toolInvoker: ToolInvoker = DefaultToolInvoker()
    private val mapper = ObjectMapper()
    private val toolRouter = ToolRouter(project, toolInvoker, mapper)
    private val responseBuilder = ResponseBuilder(project)

    private var currentModel: String = ModelSelector.initialModel()

    private val managerLabel: String = "AI(manager)"

    @Volatile
    private var pendingTeamAddAgents: List<com.github.quanta_dance.quanta.plugins.intellij.models.TeamAgentSpec>? = null

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

        if (reportToUi) {
            try {
                project.service<ToolWindowService>().addDebugMessage(
                    "usage",
                    "+in=$inTok +out=$outTok +total=$totalTok | total in=$tIn out=$tOut total=$tTot",
                )
            } catch (_: Throwable) {
            }
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
                val method = gitClass.getMethod("getInstance", com.intellij.openapi.project.Project::class.java)
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
                    val basePath = project.basePath
                    if (basePath != null) {
                        val pb = ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD")
                        pb.directory(java.io.File(basePath))
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
            val state = QuantaAISettingsState.instance.state
            val list = state.conversations.getOrPut(key) { mutableListOf() }
            list.add(QuantaAISettingsState.PersistedMessage(System.currentTimeMillis(), role, text, responseId))
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
        try {
            project.service<ToolWindowService>().addToolingMessage(label, text)
        } catch (_: Throwable) {
        }
    }

    private fun summaryForKey(key: String): String? =
        try {
            QuantaAISettingsState.instance.state.conversationSummaries[key]
        } catch (_: Throwable) {
            null
        }

    private fun storeSummaryForKey(
        key: String,
        summary: String,
    ) {
        if (summary.isBlank()) return
        try {
            QuantaAISettingsState.instance.state.conversationSummaries[key] = summary
        } catch (_: Throwable) {
        }
    }

    private fun buildHeuristicSummaryForKey(
        key: String,
        maxChars: Int = 2_000,
    ): String {
        val msgs =
            try {
                QuantaAISettingsState.instance.state.conversations[key]
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
                    it.text
                        ?.trim()
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
                QuantaAISettingsState.instance.state.conversations[key]
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
                QuantaAISettingsState.instance.state.conversations[key]
            } catch (_: Throwable) {
                null
            } ?: return ""

        val previousSummary = summaryForKey(key).orEmpty().trim()
        val tail = msgs.takeLast(30)
        val transcript =
            buildString {
                tail.forEach { m ->
                    val role = m.role.ifBlank { "unknown" }
                    val text = (m.text ?: "").trim().take(800)
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
                val basePath = project.basePath
                if (basePath != null) {
                    val root =
                        com.intellij.openapi.vfs.LocalFileSystem
                            .getInstance()
                            .findFileByPath(basePath)
                    if (root != null) {
                        var cnt = 0

                        fun dfs(v: com.intellij.openapi.vfs.VirtualFile) {
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
        project.messageBus.connect(this).subscribe(
            QuantaAISettingsListener.TOPIC,
            object : QuantaAISettingsListener {
                override fun onSettingsChanged(newState: QuantaAISettingsState.QuantaAIState) {
                    try {
                        val newClientKey = newState.host to newState.token
                        if (newClientKey != clientKey) {
                            oAI = OpenAIClientProvider.get(project)
                            clientKey = newClientKey
                            thisLogger().info("OpenAI client reinitialized after host/token change")
                        }
                        val newModelKey = (newState.dynamicModelEnabled == true) to newState.aiChatModel
                        if (newModelKey != modelKey) {
                            currentModel = ModelSelector.initialModel()
                            modelKey = newModelKey
                        }
                    } catch (_: Throwable) {
                    }
                }
            },
        )

        // Chat restore happens in ToolWindowService.setToolWindowFactory (UI-ready)
    }

    override fun dispose() {}

    private fun userMessage(text: String): ResponseInputItem =
        com.openai.models.responses.ResponseInputItem.ofMessage(
            com.openai.models.responses.ResponseInputItem.Message
                .builder()
                .addInputTextContent(text)
                .role(com.openai.models.responses.ResponseInputItem.Message.Role.USER)
                .build(),
        )

    private fun systemMessage(text: String): ResponseInputItem =
        com.openai.models.responses.ResponseInputItem.ofMessage(
            com.openai.models.responses.ResponseInputItem.Message
                .builder()
                .addInputTextContent(text)
                .role(com.openai.models.responses.ResponseInputItem.Message.Role.SYSTEM)
                .build(),
        )

    private fun buildBuiltInToolsManifestMessage(maxChars: Int = 2_000): String {
        val names =
            try {
                ToolsRegistry.toolsFor(project).map { it.simpleName }.sorted()
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

    private fun truncateToolOutput(
        value: Any?,
        maxJsonChars: Int = 4000,
        maxStringChars: Int = 2000,
        depth: Int = 0,
    ): Any? {
        if (value == null) return null
        if (depth > 6) return "<truncated: max depth reached>"

        val simplified: Any =
            when (value) {
                is String -> if (value.length <= maxStringChars) value else value.take(maxStringChars) + "... (truncated)"
                is Map<*, *> ->
                    value.entries
                        .take(80)
                        .associate { (k, v) ->
                            val key = k?.toString() ?: "<null>"
                            key to truncateToolOutput(v, maxJsonChars, maxStringChars, depth + 1)
                        }

                is List<*> -> value.take(80).map { truncateToolOutput(it, maxJsonChars, maxStringChars, depth + 1) }
                else -> value
            }

        return try {
            val json = mapper.writeValueAsString(simplified)
            if (json.length <= maxJsonChars) {
                simplified
            } else {
                mapOf(
                    "truncated" to true,
                    "preview" to json.take(maxJsonChars) + "... (truncated)",
                    "originalChars" to json.length,
                )
            }
        } catch (_: Throwable) {
            simplified
        }
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
            QuantaAISettingsState.instance.state.mainLastResponseId = null
        } catch (_: Throwable) {
        }
        lastCtxHash = null
        initialContextInjectedThisIdeSession = false

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
        QuantaAISettingsState.instance.state.mainLastResponseId = null
        lastCtxHash = null
        initialContextInjectedThisIdeSession = false

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
            QuantaAISettingsState.instance.state.conversations
                .remove(key)
        } catch (_: Throwable) {
        }

        try {
            project.service<AgentManagerService>().resetForNewSession()
        } catch (_: Throwable) {
        }
        pcs.firePropertyChange("session", old, currentSessionId)
        project.service<ToolWindowService>().clear()
        return currentSessionId
    }

    fun getCurrentSessionId(): String = currentSessionId

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
        val createParams =
            responseBuilder
                .createParamsBuilder(
                    inputs,
                    previousId,
                    currentModel,
                    overrideInstructions,
                    overrideModel,
                    allowedToolClassFilter,
                    includeMcp,
                    allowedBuiltInNames,
                    allowedMcpNames,
                ).build()
        val structResponse = oAI.responses().create(createParams)

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
    ): Pair<String, String?> {
        var localPrevId = previousId
        val aggregated = StringBuilder()
        val processedCallIds = mutableSetOf<String>()
        var reprocess = true
        var continuationCount = 0
        val maxContinuations =
            try {
                QuantaAISettingsState.instance.state.maxAutomaticTurns.coerceIn(1, 100)
            } catch (_: Throwable) {
                5
            }

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

            structResponse.output().map { item ->
                when {
                    item.isFunctionCall() -> {
                        val functionCall: com.openai.models.responses.ResponseFunctionToolCall = item.asFunctionCall()
                        val callId = functionCall.callId()
                        if (!processedCallIds.add(callId)) return@map
                        project
                            .service<ToolWindowService>()
                            .addToolingMessage(agentLabel, "Calling tool: ${functionCall.name()}")
                        val functionResult = toolRouter.route(functionCall)
                        val safeResult = truncateToolOutput(functionResult)!!
                        pendingToolOutputs.add(
                            com.openai.models.responses.ResponseInputItem.ofFunctionCallOutput(
                                com.openai.models.responses.ResponseInputItem.FunctionCallOutput
                                    .builder()
                                    .callId(callId)
                                    .outputAsJson(safeResult ?: emptyMap<String, Any>())
                                    .build(),
                            ),
                        )
                    }

                    item.isMessage() -> {
                        item.message().map { m ->
                            m.content().forEach { c ->
                                val message = c.asOutputText()
                                val txt = message.summaryMessage
                                if (txt.isNotBlank()) {
                                    persistAndShow("assistant", agentLabel, txt)
                                }

                                aggregated.append(txt).append('\n')

                                // Option 3: 3-state conversation control
                                if (message.nextStep?.uppercase() == "CONTINUE") {
                                    if (continuationCount < maxContinuations) {
                                        continuationCount++
                                        // Nudge follow-up (no tools pending)
                                        inputs.add(systemMessage("Continue."))
                                        reprocess = true
                                        project.service<ToolWindowService>().addToolingMessage(
                                            agentLabel,
                                            "Continuation requested by model (nextStep=CONTINUE) #$continuationCount",
                                        )
                                    } else {
                                        project.service<ToolWindowService>().addToolingMessage(
                                            agentLabel,
                                            "nextStep=CONTINUE requested but maxContinuations=$maxContinuations reached; stopping",
                                        )
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

    fun sendMessage(
        text: String,
        messageCallback: (OpenAIResponse) -> Unit = {},
        toolCallback: () -> Unit = {},
    ) {
        operationInProgress = true
        pcs.firePropertyChange("inProgress", false, true)
        processingFuture =
            ApplicationManager.getApplication().executeOnPooledThread {
                val requestInputs =
                    Collections.synchronizedList(mutableListOf<com.openai.models.responses.ResponseInputItem>())

                val ctx = CurrentFileContextProvider(project).getCurrent()
                if (ctx != null) {
                    val header =
                        "Current file open: ${ctx.filePathRelative}, file version: ${ctx.version} - " +
                                "you must always reread file if version changed"
                    val caretLine = ctx.caretLine
                    val caretCol = ctx.caretColumn
                    val sb = StringBuilder().append(header)
                    if (caretLine != null && caretCol != null) {
                        sb.append(
                            """
                            User Caret position in the file ${ctx.filePathRelative} - Line: ${'$'}caretLine, Column (Offset): ${'$'}caretCol
                            """.trimIndent(),
                        )
                    }
                    if (ctx.selectedText != null && ctx.selectionStartLine != null && ctx.selectionStartColumn != null &&
                        ctx.selectionEndLine != null && ctx.selectionEndColumn != null
                    ) {
                        sb.append(
                            "\nSelection starts at line ${ctx.selectionStartLine}, column ${ctx.selectionStartColumn} " +
                                    "and ends at line ${ctx.selectionEndLine}, column ${ctx.selectionEndLine}\n",
                        )
                        val sel = ctx.selectedText
                        if (sel != null) {
                            sb.append("Selected text snippet is:\n")
                            sb.append(truncateSelectedText(sel))
                        }
                    }
                    val payload = sb.toString()
                    val h = payload.hashCode()
                    if (lastCtxHash == null || lastCtxHash != h || lastResponseId == null) {
                        requestInputs.add(systemMessage(payload))
                        lastCtxHash = h
                    }
                }
                try {
                    val effectiveForThisCall = ModelSelector.effectiveModel(currentModel)
                    requestInputs.add(systemMessage("{\"currentModel\":\"${effectiveForThisCall}\"}"))
                } catch (_: Throwable) {
                }
                // Inject hidden context on:
                // - new server thread (lastResponseId == null)
                // - first IDE session turn after restart (server thread may not exist even if lastResponseId is non-null)
                // - any observable changes to summary / AGENTS.md / agents roster
                val needBaseContext = (lastResponseId == null) || (!initialContextInjectedThisIdeSession)
                val key = conversationKeyForMain()

                // Ensure a summary exists; if none, build a heuristic one from persisted messages.
                val existingSummary =
                    try {
                        summaryForKey(key)
                    } catch (_: Throwable) {
                        null
                    }.orEmpty()

                val ensuredSummary =
                    if (existingSummary.isNotBlank()) {
                        existingSummary
                    } else {
                        try {
                            val h = buildHeuristicSummaryForKey(key)
                            if (h.isNotBlank()) storeSummaryForKey(key, h)
                            h
                        } catch (_: Throwable) {
                            ""
                        }
                    }

                if (ensuredSummary.isNotBlank()) {
                    val h = ensuredSummary.hashCode()
                    if (needBaseContext || lastInjectedSummaryHash == null || lastInjectedSummaryHash != h) {
                        requestInputs.add(systemMessage("Conversation summary (auto):\n" + ensuredSummary))
                        lastInjectedSummaryHash = h
                    }
                }

                // Repository-root AGENTS.md (preferred) or fallback project details (rare)
                try {
                    val ctx = ProjectAgentsFileManager(project).readAgentsFile(maxChars = 8_000)
                    if (ctx.isNotBlank()) {
                        val h = ctx.hashCode()
                        if (needBaseContext || lastInjectedAgentsMdHash == null || lastInjectedAgentsMdHash != h) {
                            requestInputs.add(systemMessage("AGENTS.md:\n" + ctx))
                            lastInjectedAgentsMdHash = h
                        }
                    } else if (needBaseContext && lastInjectedAgentsMdHash == null) {
                        // Only send fallback project details once per base context injection.
                        requestInputs.add(systemMessage(buildProjectDetailsSystemMessage()))
                        lastInjectedAgentsMdHash = 0
                    }
                } catch (_: Throwable) {
                }

                // Agents roster: only send when it changes or on base context injection.
                try {
                    val roster = buildAgentsRosterContext()
                    val h = roster.hashCode()
                    if (needBaseContext || lastInjectedAgentsRosterHash == null || lastInjectedAgentsRosterHash != h) {
                        requestInputs.add(systemMessage(roster))
                        lastInjectedAgentsRosterHash = h
                    }
                } catch (_: Throwable) {
                }

                if (needBaseContext) {
                    initialContextInjectedThisIdeSession = true
                }

                // Reset continuation counter at the start of a user-initiated turn so continuations don't carry over from prior turns
                var continuationCount = 0
                val maxContinuations =
                    try {
                        QuantaAISettingsState.instance.state.maxAutomaticTurns.coerceIn(1, 100)
                    } catch (_: Throwable) {
                        10
                    }

                // Persist user input for this turn (per-branch). UI may already show it elsewhere, so we persist only.
                persistOnly("user", text)

                val planService = SessionPlanService(project)
                val normalized = text.trim().lowercase()
                // Cooperative activation: user must explicitly approve the drafted plan.
                if (normalized == "approve" || normalized == "approve plan" || normalized == "approve the plan") {
                    try {
                        // Apply pending team shaping once on approval
                        val agentMgr = project.service<AgentManagerService>()
                        val snaps = agentMgr.getAgentsSnapshot()

                        val toRemove =
                            pendingTeamRemoveRoles
                                ?.mapNotNull { it.trim().ifBlank { null } }
                                .orEmpty()
                                .toSet()
                        if (toRemove.isNotEmpty()) {
                            snaps.forEach { s ->
                                try {
                                    if (toRemove.contains(s.role)) {
                                        agentMgr.removeAgent(s.id)
                                    }
                                } catch (_: Throwable) {
                                }
                            }
                        }

                        val toAdd = pendingTeamAddAgents.orEmpty()
                        toAdd.forEach { spec ->
                            try {
                                val role = spec.role.trim()
                                if (role.isNotBlank()) {
                                    agentMgr.createAgent(
                                        AgentManagerService.AgentConfig(
                                            role = role,
                                            model = spec.model,
                                            instructions = spec.instructions,
                                            includeMcp = false,
                                            allowedBuiltInTools = true,
                                        ),
                                    )
                                }
                            } catch (_: Throwable) {
                            }
                        }

                        pendingTeamAddAgents = null
                        pendingTeamRemoveRoles = null
                    } catch (_: Throwable) {
                    }

                    try {
                        planService.activate()
                    } catch (_: Throwable) {
                    }
                }


                // If plan is ACTIVE, attach it as context so the manager follows it until finished.
                val planIsActive = planService.isActive()
                if (planIsActive) {
                    try {
                        val planText = planService.loadText(maxChars = 8_000)
                        if (planText.isNotBlank()) {
                            requestInputs.add(systemMessage("Session plan (.quantadance/session/plan.md):\n" + planText))
                        }
                        requestInputs.add(
                            systemMessage(
                                "Plan execution policy: the session plan is ACTIVE. " +
                                        "Proceed autonomously through unchecked tasks from top to bottom. " +
                                        "Prefer delegation: coordinate work by sending tasks to sub-agents (Developer Agent / Test Agent / Project Analyst) and integrate their replies. " +
                                        "Only do work yourself when coordination-only or trivial. " +
                                        "Do NOT ask the user questions unless truly blocked. " +
                                        "If blocked, set nextStep=WAIT_USER, set planNeedsUserConfirmation=true, and put exactly one question in planBlockingQuestion. " +
                                        "Otherwise, keep nextStep=CONTINUE until all tasks are [x], then DONE.",

                                ),
                        )
                    } catch (_: Throwable) {
                    }
                }



                if (FEATURE_SILENT_TOOL_ESCALATION) {
                    // Tools are disabled by default. Model can request tools by class simple name via OpenAIResponse.requestedTools.
                    // We then silently retry once with only those tools enabled.
                    try {
                        val isNewThread = lastResponseId == null

                        val shouldInjectToolPolicyHint = !toolPolicyHintInjectedThisIdeSession || isNewThread
                        if (shouldInjectToolPolicyHint) {
                            requestInputs.add(
                                systemMessage(
                                    "Tool policy: tools are DISABLED by default. If you need tools, set nextStep=CONTINUE and " +
                                            "requestedTools=[<ClassSimpleName>, ...]. " +
                                            "Do not claim you executed tools unless tool outputs are present.",
                                ),
                            )
                            toolPolicyHintInjectedThisIdeSession = true
                        }

                        val shouldInjectToolManifest = !toolManifestInjectedThisIdeSession || isNewThread
                        if (shouldInjectToolManifest) {
                            requestInputs.add(systemMessage(buildBuiltInToolsManifestMessage()))
                            toolManifestInjectedThisIdeSession = true
                        }
                    } catch (_: Throwable) {
                    }
                }

                requestInputs.add(userMessage(text))

                var reprocess = true
                var spokeThisTurn = false
                val processedCallIds = mutableSetOf<String>()
                var previousIdForThisTurn = lastResponseId
                var aborted = false
                var retriedAfterContextReset = false
                var budgetedThisTurn = false

                var includeMcpThisAttempt = !FEATURE_SILENT_TOOL_ESCALATION
                var allowedBuiltInNamesThisAttempt: Set<String>? =
                    if (FEATURE_SILENT_TOOL_ESCALATION) emptySet() else null
                var allowedMcpNamesThisAttempt: Set<String>? = if (FEATURE_SILENT_TOOL_ESCALATION) emptySet() else null
                var toolEscalatedThisTurn = false

                val tws = project.service<ToolWindowService>()
                val delayedSpinner = DelayedSpinner(tws)
                delayedSpinner.startWithDelay("AI is thinking [${ModelSelector.effectiveModel(currentModel)}]", 300)

                while (reprocess) {
                    reprocess = false
                    try {
                        // Step 0 instrumentation: estimate request size before calling the API
                        try {
                            var totalChars = 0
                            var maxItemChars = 0
                            requestInputs.forEach { item ->
                                val n =
                                    try {
                                        item.toString().length
                                    } catch (_: Throwable) {
                                        0
                                    }
                                totalChars += n
                                if (n > maxItemChars) maxItemChars = n
                            }
                            thisLogger().info(
                                "RequestInputs: items=${requestInputs.size} approxChars=$totalChars maxItemChars=$maxItemChars " +
                                        "previousIdNull=${previousIdForThisTurn == null} session=$currentSessionId",
                            )
                        } catch (_: Throwable) {
                        }

                        // Step 1 mitigation: budget request inputs to reduce chances of hitting the context window
                        try {
                            budgetedThisTurn = budgetedThisTurn || budgetRequestInputs(requestInputs)
                        } catch (_: Throwable) {
                        }

                        // Tools are disabled by default. When the model requests tools (requestedTools), we silently retry once
                        // with only those tools enabled by passing allow-lists to createResponse.
                        val (structResponse, newId) =

                            createResponse(
                                requestInputs,
                                previousIdForThisTurn,
                                allowedToolClassFilter = null,
                                includeMcp = includeMcpThisAttempt,
                                allowedBuiltInNames = allowedBuiltInNamesThisAttempt,
                                allowedMcpNames = allowedMcpNamesThisAttempt,
                            )
                        previousIdForThisTurn = newId
                        delayedSpinner.stopSuccess()

                        requestInputs.clear()
                        val pendingToolOutputs = mutableListOf<com.openai.models.responses.ResponseInputItem>()

                        structResponse.output().map { item ->
                            when {
                                item.isReasoning() -> {
                                    val reasoning = item.asReasoning()
                                    reasoning.summary().forEach { summary ->
                                        project
                                            .service<ToolWindowService>()
                                            .addToolingMessage("Reasoning(manager)", summary.text())
                                    }
                                }

                                item.isFunctionCall() -> {
                                    val functionCall: com.openai.models.responses.ResponseFunctionToolCall =
                                        item.asFunctionCall()
                                    val callId = functionCall.callId()
                                    if (!processedCallIds.add(callId)) return@map
                                    project
                                        .service<ToolWindowService>()
                                        .addToolingMessage(managerLabel, "Calling tool: ${functionCall.name()}")
                                    val functionResult = toolRouter.route(functionCall)
                                    val safeResult = truncateToolOutput(functionResult)
                                    pendingToolOutputs.add(
                                        com.openai.models.responses.ResponseInputItem.ofFunctionCallOutput(
                                            com.openai.models.responses.ResponseInputItem.FunctionCallOutput
                                                .builder()
                                                .callId(callId)
                                                .outputAsJson(safeResult ?: emptyMap<String, Any>())
                                                .build(),
                                        ),
                                    )
                                }

                                item.isMessage() -> {
                                    item.message().map { m ->
                                        m.content().forEach { c ->
                                            val message = c.asOutputText()

                                            // Capture draft team shaping proposals (applied only when user approves)
                                            try {
                                                if (message.planStatus?.uppercase() == "DRAFT" && message.planNeedsUserConfirmation == true) {
                                                    pendingTeamAddAgents = message.teamAddAgents
                                                    pendingTeamRemoveRoles = message.teamRemoveRoles
                                                }
                                            } catch (_: Throwable) {
                                            }

                                            // Apply plan progress if present
                                            try {
                                                val completed =
                                                    message.planCompletedTasks
                                                        ?.mapNotNull { it.trim().ifBlank { null } }
                                                        .orEmpty()
                                                if (completed.isNotEmpty()) {
                                                    SessionPlanService(project).markTasksDone(completed)
                                                }
                                            } catch (_: Throwable) {
                                            }

                                            // Enforce ACTIVE plan autonomy: do not allow WAIT_USER unless explicitly blocked.
                                            if (planIsActive && message.nextStep?.uppercase() == "WAIT_USER") {
                                                val blocked = message.planNeedsUserConfirmation == true
                                                if (!blocked) {
                                                    // Override to continue execution without user input.
                                                    if (continuationCount < maxContinuations) {
                                                        continuationCount++
                                                        reprocess = true
                                                        requestInputs.add(
                                                            systemMessage(
                                                                "Continue executing the ACTIVE plan autonomously. " +
                                                                        "Do not ask the user questions unless truly blocked.",
                                                            ),
                                                        )
                                                        return@forEach
                                                    }
                                                }
                                            }


                                            if (FEATURE_SILENT_TOOL_ESCALATION) {

                                                val requested =
                                                    message.requestedTools?.mapNotNull { it.trim().ifBlank { null } }
                                                        .orEmpty()
                                                val wantsTools =
                                                    message.nextStep?.uppercase() == "CONTINUE" &&
                                                            requested.isNotEmpty() &&
                                                            !toolEscalatedThisTurn

                                                if (!wantsTools) {
                                                    persistAndShow("assistant", managerLabel, message.summaryMessage)

                                                    message.ttsSummary?.also { summary ->
                                                        if (!spokeThisTurn) {
                                                            project.service<AIVoiceService>().say(summary)
                                                            spokeThisTurn = true
                                                        }
                                                    }
                                                }

                                                // Debug: surface nextStep value
                                                project.service<ToolWindowService>().addDebugMessage(
                                                    "next_step",
                                                    "nextStep=${message.nextStep} continuationCount=$continuationCount/$maxContinuations",
                                                )

                                                if (wantsTools) {
                                                    // Silent tool escalation: enable only requested built-in tools by class simple name and retry once.
                                                    toolEscalatedThisTurn = true
                                                    val available =
                                                        try {
                                                            com.github.quanta_dance.quanta.plugins.intellij.tools.ToolsRegistry
                                                                .toolsFor(project)
                                                                .map { it.simpleName }
                                                                .toSet()
                                                        } catch (_: Throwable) {
                                                            emptySet()
                                                        }
                                                    val filtered = requested.filter { available.contains(it) }.toSet()
                                                    allowedBuiltInNamesThisAttempt = filtered
                                                    includeMcpThisAttempt = false
                                                    allowedMcpNamesThisAttempt = emptySet()

                                                    reprocess = filtered.isNotEmpty()
                                                    if (reprocess) {
                                                        requestInputs.add(
                                                            systemMessage(
                                                                "Tools enabled for this turn: ${
                                                                    filtered.sorted().joinToString()
                                                                }. " +
                                                                        "Proceed to call tools as needed.",
                                                            ),
                                                        )
                                                    } else {
                                                        // Fall back to plain continuation if nothing matched.
                                                        if (continuationCount < maxContinuations) {
                                                            continuationCount++
                                                            reprocess = true
                                                            requestInputs.add(systemMessage("Continue."))
                                                        }
                                                    }
                                                } else {
                                                    // Option 3: 3-state conversation control
                                                    if (message.nextStep?.uppercase() == "CONTINUE") {
                                                        if (continuationCount < maxContinuations) {
                                                            continuationCount++
                                                            reprocess = true
                                                            // Add an explicit continuation nudge so the next call continues immediately.
                                                            requestInputs.add(systemMessage("Continue."))
                                                            project.service<ToolWindowService>().addToolingMessage(
                                                                managerLabel,
                                                                "Response incomplete; requesting continuation (#$continuationCount)",
                                                            )
                                                        } else {
                                                            project.service<ToolWindowService>().addToolingMessage(
                                                                managerLabel,
                                                                "Response incomplete but maxContinuations=$maxContinuations" +
                                                                        " reached; stopping",
                                                            )
                                                        }
                                                    }
                                                }
                                            } else {
                                                // Baseline mode: expose all tools by default.
                                                persistAndShow("assistant", managerLabel, message.summaryMessage)

                                                message.ttsSummary?.also { summary ->
                                                    if (!spokeThisTurn) {
                                                        project.service<AIVoiceService>().say(summary)
                                                        spokeThisTurn = true
                                                    }
                                                }

                                                // Debug: surface nextStep value
                                                project.service<ToolWindowService>().addDebugMessage(
                                                    "next_step",
                                                    "nextStep=${message.nextStep} continuationCount=$continuationCount/$maxContinuations",
                                                )

                                                // Option 3: 3-state conversation control
                                                if (message.nextStep?.uppercase() == "CONTINUE") {
                                                    if (continuationCount < maxContinuations) {
                                                        continuationCount++
                                                        reprocess = true
                                                        requestInputs.add(systemMessage("Continue."))
                                                        project.service<ToolWindowService>().addToolingMessage(
                                                            managerLabel,
                                                            "Response incomplete; requesting continuation (#$continuationCount)",
                                                        )
                                                    } else {
                                                        project.service<ToolWindowService>().addToolingMessage(
                                                            managerLabel,
                                                            "Response incomplete but maxContinuations=$maxContinuations reached; stopping",
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                item.isImageGenerationCall() -> {}

                                else -> {
                                    thisLogger().warn("Unknown item type received.")
                                }
                            }
                        }

                        val hasPending = pendingToolOutputs.isNotEmpty()
                        if (hasPending) requestInputs.addAll(pendingToolOutputs)
                        if (hasPending) reprocess = true
                    } catch (e: InterruptedException) {
                        aborted = true
                        delayedSpinner.stopError("Cancelled after interruption")
                        thisLogger().warn("Execution interrupted: ", e)
                        Thread.currentThread().interrupt()
                        break
                    } catch (e: Throwable) {
                        val msg = e.message.orEmpty()
                        val isContextWindow =
                            msg.contains("exceeds context window", ignoreCase = true) ||
                                    msg.contains("context window", ignoreCase = true)

                        if (isContextWindow && !retriedAfterContextReset) {
                            // Step 4: summarize + rewrite local history + reset previousId and retry once with minimal context.
                            // Goal: user should not notice the context-window failure.
                            thisLogger().warn(
                                "CONTEXT_WINDOW_EXCEEDED: attempting soft reset (retry once). " +
                                        "items=${requestInputs.size} " +
                                        "previousIdNull=${previousIdForThisTurn == null} " +
                                        "session=$currentSessionId message=$msg",
                                e,
                            )
                            try {
                                project.service<ToolWindowService>().addDebugMessage(
                                    "context_reset",
                                    "attempting soft reset: items=${requestInputs.size} previousIdNull=${previousIdForThisTurn == null}",
                                )
                            } catch (_: Throwable) {
                            }

                            val key = conversationKeyForMain()

                            // Force a rolling summary that represents the whole conversation.
                            // Prefer LLM summary (more accurate), fallback to heuristic if needed.
                            val summaryText: String =
                                try {
                                    generateSummaryWithLlm(key)
                                } catch (_: Throwable) {
                                    ""
                                }.ifBlank {
                                    try {
                                        buildHeuristicSummaryForKey(key)
                                    } catch (_: Throwable) {
                                        ""
                                    }
                                }

                            if (summaryText.isNotBlank()) {
                                try {
                                    storeSummaryForKey(key, summaryText)
                                } catch (_: Throwable) {
                                }
                                try {
                                    project.service<ToolWindowService>().addDebugMessage(
                                        "context_reset",
                                        "summary generated: chars=${summaryText.length}",
                                    )
                                } catch (_: Throwable) {
                                }

                                // Rewrite persisted history to avoid re-hitting the context window on restart
                                // and to keep the chat UI coherent.
                                try {
                                    val st = QuantaAISettingsState.instance.state
                                    st.conversations[key] =
                                        mutableListOf(
                                            QuantaAISettingsState.PersistedMessage(
                                                System.currentTimeMillis(),
                                                "system",
                                                "Conversation summary (auto, rewritten after context-window reset):\n" + summaryText,
                                                null,
                                            ),
                                            QuantaAISettingsState.PersistedMessage(
                                                System.currentTimeMillis(),
                                                "user",
                                                text,
                                                null,
                                            ),
                                        )
                                } catch (_: Throwable) {
                                }
                            }

                            // Reset server-side thread state
                            previousIdForThisTurn = null
                            lastResponseId = null
                            try {
                                QuantaAISettingsState.instance.state.mainLastResponseId = null
                            } catch (_: Throwable) {
                            }

                            // Rebuild minimal request inputs for retry
                            try {
                                requestInputs.clear()
                                val summary = summaryForKey(key)
                                if (!summary.isNullOrBlank()) {
                                    requestInputs.add(systemMessage("Conversation summary (auto):\n" + summary))
                                }
                                val agentsCtx = ProjectAgentsFileManager(project).readAgentsFile(maxChars = 8_000)
                                if (agentsCtx.isNotBlank()) {
                                    requestInputs.add(systemMessage("AGENTS.md:\n" + agentsCtx))
                                }
                                requestInputs.add(systemMessage(buildAgentsRosterContext()))
                                requestInputs.add(userMessage(text))
                            } catch (_: Throwable) {
                            }

                            retriedAfterContextReset = true
                            reprocess = true
                            continue
                        }

                        aborted = true
                        delayedSpinner.stopError(if (msg.isNotBlank()) msg else "Unexpected error")
                        if (isContextWindow) {
                            thisLogger().warn(
                                "CONTEXT_WINDOW_EXCEEDED: items=${requestInputs.size} previousIdNull=${previousIdForThisTurn == null} " +
                                        "session=$currentSessionId message=$msg",
                                e,
                            )
                        } else {
                            thisLogger().warn("Unexpected Error: ", e)
                        }
                        Notifications.show(project, msg, NotificationType.ERROR)
                        break
                    }
                }
                if (!aborted) {
                    lastResponseId = previousIdForThisTurn
                    QuantaAISettingsState.instance.state.mainLastResponseId = lastResponseId

                    // Proactive LLM summarization (throttled) to keep long conversations within context limits
                    try {
                        val key = conversationKeyForMain()
                        scheduleSummaryIfNeeded(key, budgetedThisTurn)
                    } catch (_: Throwable) {
                    }
                }

                operationInProgress = false
                pcs.firePropertyChange("inProgress", true, false)
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
