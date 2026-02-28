// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.services

import com.github.quanta_dance.quanta.plugins.intellij.settings.Instructions
import com.github.quanta_dance.quanta.plugins.intellij.settings.QuantaAISettingsState
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.openai.models.responses.ResponseInputItem
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.PROJECT)
class AgentManagerService(
    private val project: Project,
) : Disposable {
    data class AgentConfig(
        val role: String,
        val model: String?,
        val instructions: String?,
        val includeMcp: Boolean = true,
        val allowedBuiltInTools: Boolean = true,
        val allowedMcpServers: List<String>? = null,
        // New: per-agent explicit allow-lists (if set, these lists restrict tool access)
        val allowedBuiltInNames: Set<String>? = null,
        val allowedMcpNames: Set<String>? = null,
    )

    data class AgentSession(
        val id: String,
        val config: AgentConfig,
        var previousId: String? = null,
    )

    data class AgentSnapshot(
        val id: String,
        val role: String,
        val instructions: String?,
        val model: String?,
    )

    data class AgentTaskResult(
        val requestId: String,
        val agentId: String,
        val ok: Boolean,
        val text: String?,
        val error: String?,
    )

    private val logger = Logger.getInstance(AgentManagerService::class.java)
    private val agents = ConcurrentHashMap<String, AgentSession>()
    private val pcs = PropertyChangeSupport(this)
    private val executors = ConcurrentHashMap<String, ExecutorService>()

    // Proactive summarization throttle
    private val agentSummaryLastRunAtMs = ConcurrentHashMap<String, Long>()

    // Auto-wake agents when new inbox messages arrive
    private val agentWakeInFlight = ConcurrentHashMap<String, AtomicBoolean>()
    private val agentLastWakeRequestedAtMs = ConcurrentHashMap<String, Long>()

    init {
        val st = QuantaAISettingsState.instance.state
        st.agents.forEach { pa ->
            val session = AgentSession(pa.id, AgentConfig(pa.role, pa.model, pa.instructions), previousId = pa.previousId)
            agents[pa.id] = session
            ensureExecutor(pa.id)
        }
    }

    fun addPropertyChangeListener(listener: PropertyChangeListener) = pcs.addPropertyChangeListener(listener)

    fun getAgentsSnapshot(): List<AgentSnapshot> =
        agents.values.map { AgentSnapshot(it.id, it.config.role, it.config.instructions, it.config.model) }

    fun getAgentAllowedBuiltInNames(agentId: String): Set<String>? = agents[agentId]?.config?.allowedBuiltInNames

    private fun buildAgentsRosterText(): String {
        val snaps = getAgentsSnapshot().sortedBy { it.role }
        val b = StringBuilder()
        b.append("Agents roster (auto):\n")
        if (snaps.isEmpty()) {
            b.append("- <none>\n")
            return b.toString()
        }
        snaps.forEach { a ->
            b.append("- id=").append(a.id).append(", role=").append(a.role)
            a.model?.let { m -> b.append(", model=").append(m) }
            b.append('\n')
        }
        return b.toString().trimEnd()
    }

    fun postInboxMessage(
        toAgentId: String,
        from: String?,
        text: String,
        kind: String? = "notification",
    ): Boolean {
        if (text.isBlank()) return false
        if (!agents.containsKey(toAgentId)) return false
        return try {
            val st = QuantaAISettingsState.instance.state
            val list = st.agentInboxes.getOrPut(toAgentId) { mutableListOf() }
            list.add(QuantaAISettingsState.AgentInboxMessage(System.currentTimeMillis(), from, text, kind))
            val max = 50
            if (list.size > max) {
                val drop = list.size - max
                repeat(drop) { if (list.isNotEmpty()) list.removeAt(0) }
            }
            pcs.firePropertyChange("agent_inbox", null, mapOf("agentId" to toAgentId, "count" to list.size))

            try {
                QDLog.debug(logger) {
                    "Inbox post: to=$toAgentId from=${from ?: "<null>"} kind=${kind ?: "<null>"} " +
                        "len=${text.length} inboxSize=${list.size}"
                }
                project.service<ToolWindowService>().addDebugMessage(
                    "inbox_post",
                    "to=$toAgentId from=${from ?: "<null>"} kind=${kind ?: "<null>"} inboxSize=${list.size}",
                )
            } catch (_: Throwable) {
            }

            requestWakeIfIdle(toAgentId)
            true
        } catch (t: Throwable) {
            try {
                QDLog.warn(logger, { "Inbox post failed: to=$toAgentId err=${t.message}" }, t)
            } catch (_: Throwable) {
            }
            false
        }
    }

    fun getLastWakeRequestedAtMs(agentId: String): Long? = agentLastWakeRequestedAtMs[agentId]

    private fun requestWakeIfIdle(agentId: String) {
        // Debounce: if multiple messages come in quickly, we wake at most once per short interval.
        val now = System.currentTimeMillis()
        val last = agentLastWakeRequestedAtMs[agentId] ?: 0L
        if (now - last < 500L) {
            try {
                QDLog.debug(logger) { "Wake skipped (debounce): agent=$agentId now=$now last=$last" }
            } catch (_: Throwable) {
            }
            return
        }
        agentLastWakeRequestedAtMs[agentId] = now
        pcs.firePropertyChange("agent_wake_requested", null, mapOf("agentId" to agentId, "at" to now))
        try {
            project.service<ToolWindowService>().addDebugMessage("wake_requested", "agent=$agentId at=$now")
        } catch (_: Throwable) {
        }

        // Do not auto-call OpenAI during unit tests.
        if (ApplicationManager.getApplication().isUnitTestMode) {
            try {
                QDLog.debug(logger) { "Wake not executed in unit test mode: agent=$agentId" }
            } catch (_: Throwable) {
            }
            return
        }

        val session = agents[agentId] ?: return
        val flag = agentWakeInFlight.computeIfAbsent(agentId) { AtomicBoolean(false) }
        if (!flag.compareAndSet(false, true)) {
            try {
                QDLog.debug(logger) { "Wake skipped (already in flight): agent=$agentId" }
                project.service<ToolWindowService>().addDebugMessage("wake_skip", "agent=$agentId alreadyInFlight=true")
            } catch (_: Throwable) {
            }
            return
        }

        ensureExecutor(agentId).submit {
            try {
                QDLog.debug(logger) { "Wake turn starting: agent=$agentId" }
                project.service<ToolWindowService>().addDebugMessage("wake_start", "agent=$agentId")

                // A lightweight wake turn. Inbox messages will be injected at start-of-turn and cleared.
                val reply =
                    sendMessage(
                        agentId,
                        "(auto) You have new inbox messages. Process them. " +
                            "If you need to respond to another agent, use AgentPostMessageTool. " +
                            "If nothing is required, reply with DONE.",
                    )
                QDLog.debug(logger) { "Wake turn finished: agent=$agentId replyLen=${reply.length}" }
                project.service<ToolWindowService>().addDebugMessage("wake_done", "agent=$agentId replyLen=${reply.length}")
            } catch (t: Throwable) {
                try {
                    QDLog.warn(logger, { "Wake turn failed: agent=$agentId err=${t.message}" }, t)
                    project.service<ToolWindowService>().addDebugMessage("wake_error", "agent=$agentId err=${t.message}")
                } catch (_: Throwable) {
                }
            } finally {
                flag.set(false)
            }
        }
    }

    fun readAndClearInbox(agentId: String): List<QuantaAISettingsState.AgentInboxMessage> {
        return try {
            val st = QuantaAISettingsState.instance.state
            val list = st.agentInboxes[agentId] ?: return emptyList()
            val out = list.toList()
            list.clear()
            pcs.firePropertyChange("agent_inbox", null, mapOf("agentId" to agentId, "count" to 0))
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun broadcastRosterUpdate(from: String? = "AgentManager") {
        val roster = buildAgentsRosterText()
        if (roster.isBlank()) return
        try {
            QDLog.debug(logger) { "Roster broadcast: from=${from ?: "<null>"} agents=${agents.size}" }
            project.service<ToolWindowService>().addDebugMessage(
                "roster_broadcast",
                "from=${from ?: "<null>"} agents=${agents.size}",
            )
        } catch (_: Throwable) {
        }
        agents.keys.forEach { id ->
            postInboxMessage(id, from, roster, kind = "roster_update")
        }
    }

    private fun ensureExecutor(agentId: String): ExecutorService =
        executors.computeIfAbsent(agentId) { Executors.newSingleThreadExecutor { r -> Thread(r, "agent-$agentId") } }

    private fun agentConversationKey(agentId: String): String = "agent:$agentId"

    private fun persistAgentMessage(
        agentId: String,
        role: String,
        text: String,
    ) {
        try {
            val key = agentConversationKey(agentId)
            val state = QuantaAISettingsState.instance.state
            val list = state.conversations.getOrPut(key) { mutableListOf() }
            list.add(QuantaAISettingsState.PersistedMessage(System.currentTimeMillis(), role, text, null))

            // Keep bounded to ensure summary inputs remain small.
            val max = 120
            if (list.size > max) {
                val drop = list.size - max
                repeat(drop) { if (list.isNotEmpty()) list.removeAt(0) }
            }
        } catch (_: Throwable) {
        }
    }

    private fun summaryForAgent(agentId: String): String? =
        try {
            QuantaAISettingsState.instance.state.conversationSummaries[agentConversationKey(agentId)]
        } catch (_: Throwable) {
            null
        }

    private fun storeSummaryForAgent(
        agentId: String,
        summary: String,
    ) {
        if (summary.isBlank()) return
        try {
            QuantaAISettingsState.instance.state.conversationSummaries[agentConversationKey(agentId)] = summary
        } catch (_: Throwable) {
        }
    }

    private fun shouldSummarizeAgentProactively(
        agentId: String,
        maxMessages: Int = 80,
    ): Boolean {
        val key = agentConversationKey(agentId)
        val msgs =
            try {
                QuantaAISettingsState.instance.state.conversations[key]
            } catch (_: Throwable) {
                null
            } ?: return false
        return msgs.size >= maxMessages
    }

    private fun scheduleAgentSummaryIfNeeded(
        agentId: String,
        agentModelOverride: String?,
    ) {
        val now = System.currentTimeMillis()
        val last = agentSummaryLastRunAtMs[agentId] ?: 0L
        val minIntervalMs = 2 * 60 * 1000L
        if (now - last < minIntervalMs) return
        if (!shouldSummarizeAgentProactively(agentId)) return

        agentSummaryLastRunAtMs[agentId] = now
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val openAI = project.service<OpenAIService>()
                val summary = generateAgentSummaryWithLlm(openAI, agentId, agentModelOverride)
                if (summary.isNotBlank()) {
                    storeSummaryForAgent(agentId, summary)
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun isContextWindowError(t: Throwable): Boolean {
        val msg = t.message.orEmpty()
        return msg.contains("exceeds context window", ignoreCase = true) ||
            msg.contains("context window", ignoreCase = true)
    }

    private fun softResetAndRetryAgentTurnOnce(
        openAI: OpenAIService,
        agentId: String,
        session: AgentSession,
        message: String,
        agentLabel: String,
        toolClassFilter: ((Class<*>) -> Boolean)?,
        includeMcp: Boolean,
    ): Pair<String, String?>? {
        // Generate and store a fresh rolling summary
        val summaryText =
            try {
                generateAgentSummaryWithLlm(openAI, agentId, session.config.model)
            } catch (_: Throwable) {
                ""
            }
        if (summaryText.isNotBlank()) {
            try {
                storeSummaryForAgent(agentId, summaryText)
            } catch (_: Throwable) {
            }
        }

        // Rewrite persisted history so restart won't immediately exceed context window
        try {
            val key = agentConversationKey(agentId)
            QuantaAISettingsState.instance.state.conversations[key] =
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
                        message,
                        null,
                    ),
                )
        } catch (_: Throwable) {
        }

        // Reset server-side thread state for the agent
        session.previousId = null
        try {
            QuantaAISettingsState.instance.state.agents
                .find { it.id == agentId }
                ?.previousId = null
        } catch (_: Throwable) {
        }

        // Rebuild minimal request inputs and retry once
        val retryInputs = mutableListOf<ResponseInputItem>()
        retryInputs.add(
            ResponseInputItem.ofMessage(
                ResponseInputItem.Message
                    .builder()
                    .addInputTextContent("Agent Role: ${session.config.role}")
                    .role(ResponseInputItem.Message.Role.SYSTEM)
                    .build(),
            ),
        )
        val sum = summaryForAgent(agentId)
        if (!sum.isNullOrBlank()) {
            retryInputs.add(
                ResponseInputItem.ofMessage(
                    ResponseInputItem.Message
                        .builder()
                        .addInputTextContent("Conversation summary (auto):\n" + sum)
                        .role(ResponseInputItem.Message.Role.SYSTEM)
                        .build(),
                ),
            )
        }
        try {
            val agentsText = ProjectAgentsFileManager(project).readAgentsFile(maxChars = 8_000)
            if (agentsText.isNotBlank()) {
                retryInputs.add(
                    ResponseInputItem.ofMessage(
                        ResponseInputItem.Message
                            .builder()
                            .addInputTextContent(agentsText)
                            .role(ResponseInputItem.Message.Role.SYSTEM)
                            .build(),
                    ),
                )
            }
        } catch (_: Throwable) {
        }
        retryInputs.add(
            ResponseInputItem.ofMessage(
                ResponseInputItem.Message
                    .builder()
                    .addInputTextContent(message)
                    .role(ResponseInputItem.Message.Role.USER)
                    .build(),
            ),
        )

        return try {
            openAI.agentTurn(
                inputs = retryInputs,
                previousId = null,
                overrideInstructions = session.config.instructions,
                overrideModel = session.config.model,
                allowedToolClassFilter = toolClassFilter,
                includeMcp = includeMcp,
                agentLabel = agentLabel,
                allowedBuiltInNames = session.config.allowedBuiltInNames,
                allowedMcpNames = session.config.allowedMcpNames,
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun generateAgentSummaryWithLlm(
        openAI: OpenAIService,
        agentId: String,
        agentModelOverride: String?,
        maxSummaryChars: Int = 2_000,
    ): String {
        val key = agentConversationKey(agentId)
        val msgs =
            try {
                QuantaAISettingsState.instance.state.conversations[key]
            } catch (_: Throwable) {
                null
            } ?: return ""

        val previousSummary = summaryForAgent(agentId).orEmpty().trim()
        val tail = msgs.takeLast(30)
        val transcript =
            buildString {
                tail.forEach { m ->
                    val role = m.role.ifBlank { "unknown" }
                    val t = (m.text ?: "").trim().take(800)
                    if (t.isNotBlank()) {
                        append(role.uppercase()).append(": ").append(t.replace("\n", " ")).append('\n')
                    }
                }
            }.trim()

        val instr =
            """
            You are maintaining a rolling conversation summary for an IDE sub-agent.
            Produce a concise summary using this exact schema:
            - Goal
            - Key decisions
            - Current state
            - Open questions
            - Next steps
            Keep it under $maxSummaryChars characters.
            """.trimIndent()

        fun sys(text: String): ResponseInputItem =
            ResponseInputItem.ofMessage(
                ResponseInputItem.Message
                    .builder()
                    .addInputTextContent(text)
                    .role(ResponseInputItem.Message.Role.SYSTEM)
                    .build(),
            )

        val inputs = mutableListOf<ResponseInputItem>()
        inputs.add(sys(instr))
        if (previousSummary.isNotBlank()) inputs.add(sys("Previous summary:\n" + previousSummary.take(1_500)))
        if (transcript.isNotBlank()) inputs.add(sys("Recent transcript:\n" + transcript))

        val (resp, _) =
            openAI.createResponse(
                inputs = inputs,
                previousId = null,
                overrideModel = agentModelOverride,
                allowedToolClassFilter = { _ -> false },
                includeMcp = false,
                usageTag = "agent_summary",
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

    fun createAgent(config: AgentConfig): String {
        val enabled = QuantaAISettingsState.instance.state.agenticEnabled ?: true
        if (!enabled) throw IllegalStateException("Agentic mode is disabled in settings")
        val id = UUID.randomUUID().toString()
        val baseInstr =
            buildString {
                append("You are an assistant agent with the role '").append(config.role).append("'. ")
                append("Follow the global development instructions. Communicate in plain text.\n\n")
                append(Instructions.instructions)
                if (!config.instructions.isNullOrBlank()) {
                    append("\n\n# Role-specific instructions\n").append(config.instructions)
                }
            }
        val session = AgentSession(id = id, config = config.copy(instructions = baseInstr))
        agents[id] = session
        ensureExecutor(id)
        project.service<ToolWindowService>().addToolingMessage("AgentManager", "Created agent ${config.role} [$id]")
        try {
            broadcastRosterUpdate(from = "AgentManager")
        } catch (_: Throwable) {
        }
        val st = QuantaAISettingsState.instance.state
        st.agents.add(
            QuantaAISettingsState.PersistedAgent(
                id = id,
                role = session.config.role,
                model = session.config.model,
                instructions = session.config.instructions,
                previousId = session.previousId,
            ),
        )
        pcs.firePropertyChange("agents", null, id)
        return id
    }

    fun createDefaultTeam(): List<String> {
        val enabled = QuantaAISettingsState.instance.state.agenticEnabled ?: true
        if (!enabled) return emptyList()

        // Idempotent: do not create duplicates if user already has agents.
        if (agents.isNotEmpty()) {
            return agents.keys.toList()
        }

        // Common communication tools for sub-agents.
        // Note: we intentionally do NOT grant AgentReadInboxTool to sub-agents.
        // Inbox messages are delivered automatically at the start of each turn.
        val commonComms =
            setOf(
                "AgentSendMessageTool",
                "AgentPostMessageTool",
            )

        val developerTools =
            commonComms +
                setOf(
                    "CodeRefactorSuggester",
                    "CreateOrUpdateFile",
                    "PatchFile",
                    "ReadFileContent",
                    "ReadPsiBlockAtPosition",
                    "SearchInFiles",
                    "SearchProjectEmbeddings",
                    "UpsertProjectEmbedding",
                    "GetProjectDetails",
                    "ListFiles",
                    "GetFileReferencesAndDependencies",
                    "InspectDependencies",
                    "OpenFileInEditorTool",
                    "ValidateClassFileTool",
                    "CopyFileOrDirectoryTool",
                    "DeleteFileTool",
                )

        val testTools =
            commonComms +
                setOf(
                    "RunGradleTestsTool",
                    "RunGradleBuildTool",
                    "GetTestInfoTool",
                    "GradleSyncTool",
                    "ReadFileContent",
                    "SearchInFiles",
                    "GetProjectDetails",
                )

        val analystTools =
            commonComms +
                setOf(
                    "GetProjectDetails",
                    "SearchInFiles",
                    "ReadFileContent",
                    "SearchProjectEmbeddings",
                    "GetFileReferencesAndDependencies",
                    "InspectDependencies",
                    "ListFiles",
                )

        val ids = mutableListOf<String>()
        ids +=
            createAgent(
                AgentConfig(
                    role = "Developer Agent",
                    model = null,
                    instructions = "Develop and implement code changes. Do not run Gradle tasks; coordinate with Test Agent.",
                    includeMcp = false,
                    allowedBuiltInTools = true,
                    allowedBuiltInNames = developerTools,
                ),
            )
        ids +=
            createAgent(
                AgentConfig(
                    role = "Test Agent",
                    model = null,
                    instructions = "Run Gradle tests/build and report failures and key logs. Avoid editing code.",
                    includeMcp = false,
                    allowedBuiltInTools = true,
                    allowedBuiltInNames = testTools,
                ),
            )
        ids +=
            createAgent(
                AgentConfig(
                    role = "Project Analyst",
                    model = null,
                    instructions = "Analyze project structure and requirements; provide concise guidance. Avoid editing code.",
                    includeMcp = false,
                    allowedBuiltInTools = true,
                    allowedBuiltInNames = analystTools,
                ),
            )
        return ids
    }

    fun removeAgent(agentId: String): Boolean {
        val removed = agents.remove(agentId) ?: return false
        executors.remove(agentId)?.let { exec ->
            try {
                exec.shutdownNow()
            } catch (_: Throwable) {
            }
        }
        val st = QuantaAISettingsState.instance.state
        st.agents.removeIf { it.id == agentId }
        project.service<ToolWindowService>().addToolingMessage("AgentManager", "Removed agent ${removed.config.role} [$agentId]")
        try {
            broadcastRosterUpdate(from = "AgentManager")
        } catch (_: Throwable) {
        }
        pcs.firePropertyChange("agents", null, agentId)
        pcs.firePropertyChange("agent_removed", null, agentId)
        return true
    }

    // Stop a single agent: cancel its running tasks and recreate its executor so it can accept new work
    fun stopAgent(agentId: String): Boolean {
        if (!agents.containsKey(agentId)) return false
        executors.remove(agentId)?.let { exec ->
            try {
                exec.shutdownNow()
            } catch (_: Throwable) {
            }
        }
        executors[agentId] = Executors.newSingleThreadExecutor { r -> Thread(r, "agent-$agentId") }
        project.service<ToolWindowService>().addToolingMessage("AgentManager", "Stopped agent [$agentId]")
        pcs.firePropertyChange("agent_stopped", null, agentId)
        return true
    }

    fun stopAllAgents(): Int {
        var stopped = 0
        executors.keys.toList().forEach { id ->
            executors.remove(id)?.let { exec ->
                try {
                    exec.shutdownNow()
                    stopped++
                } catch (_: Throwable) {
                }
                executors[id] = Executors.newSingleThreadExecutor { r -> Thread(r, "agent-$id") }
            }
        }
        project.service<ToolWindowService>().addToolingMessage("AgentManager", "Stopped tasks for $stopped agent(s)")
        pcs.firePropertyChange("agents_stopped", null, stopped)
        return stopped
    }

    /**
     * Reset all agents' conversation state (previousId) for a new manager session/thread
     * without removing the agents themselves. Also persists cleared previousIds.
     */
    fun resetForNewSession() {
        agents.values.forEach { it.previousId = null }
        val st = QuantaAISettingsState.instance.state
        st.agents.forEach { it.previousId = null }
        project.service<ToolWindowService>().addDebugMessage(
            "agents_reset",
            "Reset agents conversation state for new session",
        )
        pcs.firePropertyChange("agents_reset", null, null)
    }

    fun sendMessageAsync(
        agentId: String,
        message: String,
    ): CompletableFuture<AgentTaskResult> { // unchanged
        val enabled = QuantaAISettingsState.instance.state.agenticEnabled ?: true
        if (!enabled) return CompletableFuture.completedFuture(AgentTaskResult("", agentId, false, null, "Agentic mode disabled"))
        val session =
            agents[agentId]
                ?: return CompletableFuture.completedFuture(AgentTaskResult("", agentId, false, null, "Agent not found"))
        val requestId = UUID.randomUUID().toString()
        pcs.firePropertyChange("agent_task_started", null, mapOf("requestId" to requestId, "agentId" to agentId))

        val fut = CompletableFuture<AgentTaskResult>()
        ensureExecutor(agentId).submit {
            try {
                val openAI = project.service<OpenAIService>()
                val inputs = mutableListOf<ResponseInputItem>()

                // Always deliver pending inbox messages at the start of the turn.
                try {
                    val inbox = readAndClearInbox(agentId)
                    if (inbox.isNotEmpty()) {
                        try {
                            QDLog.debug(logger) {
                                val kinds = inbox.mapNotNull { it.kind?.ifBlank { null } }.distinct()
                                "Inbox injected: agent=$agentId count=${inbox.size} kinds=$kinds"
                            }
                            project.service<ToolWindowService>().addDebugMessage(
                                "inbox_injected",
                                "agent=$agentId count=${inbox.size}",
                            )
                        } catch (_: Throwable) {
                        }

                        val inboxText =
                            buildString {
                                append("Inbox messages (auto):\n")
                                inbox.sortedBy { it.timestamp }.forEach { m ->
                                    val from = m.from?.ifBlank { null } ?: "unknown"
                                    val kind = m.kind?.ifBlank { null }
                                    append("- [").append(from)
                                    if (kind != null) append(", kind=").append(kind)
                                    append("] ").append(m.text.trim()).append('\n')
                                }
                            }.trimEnd()
                        inputs.add(
                            ResponseInputItem.ofMessage(
                                ResponseInputItem.Message
                                    .builder()
                                    .addInputTextContent(inboxText)
                                    .role(ResponseInputItem.Message.Role.SYSTEM)
                                    .build(),
                            ),
                        )
                    }
                } catch (_: Throwable) {
                }

                if (session.previousId == null) {
                    inputs.add(
                        ResponseInputItem.ofMessage(
                            ResponseInputItem.Message
                                .builder()
                                .addInputTextContent(
                                    "Agent Role: ${session.config.role}",
                                ).role(ResponseInputItem.Message.Role.SYSTEM)
                                .build(),
                        ),
                    )

                    // Provide agents roster so agents can message each other by id.
                    try {
                        inputs.add(
                            ResponseInputItem.ofMessage(
                                ResponseInputItem.Message
                                    .builder()
                                    .addInputTextContent(buildAgentsRosterText())
                                    .role(ResponseInputItem.Message.Role.SYSTEM)
                                    .build(),
                            ),
                        )
                    } catch (_: Throwable) {
                    }

                    // Provide rolling summary if present
                    try {
                        val sum = summaryForAgent(agentId)
                        if (!sum.isNullOrBlank()) {
                            inputs.add(
                                ResponseInputItem.ofMessage(
                                    ResponseInputItem.Message
                                        .builder()
                                        .addInputTextContent("Conversation summary (auto):\n" + sum)
                                        .role(ResponseInputItem.Message.Role.SYSTEM)
                                        .build(),
                                ),
                            )
                        }
                    } catch (_: Throwable) {
                    }

                    // Provide project-specific instructions from repository-root AGENTS.md (if present)
                    try {
                        val agentsText = ProjectAgentsFileManager(project).readAgentsFile(maxChars = 8_000)
                        if (agentsText.isNotBlank()) {
                            inputs.add(
                                ResponseInputItem.ofMessage(
                                    ResponseInputItem.Message
                                        .builder()
                                        .addInputTextContent(agentsText)
                                        .role(ResponseInputItem.Message.Role.SYSTEM)
                                        .build(),
                                ),
                            )
                        }
                    } catch (_: Throwable) {
                        // Non-fatal: proceed without AGENTS.md
                    }
                }

                inputs.add(
                    ResponseInputItem.ofMessage(
                        ResponseInputItem.Message
                            .builder()
                            .addInputTextContent(message)
                            .role(ResponseInputItem.Message.Role.USER)
                            .build(),
                    ),
                )
                val filter: ((Class<*>) -> Boolean)? = if (session.config.allowedBuiltInTools) null else { _ -> false }
                val includeMcp = session.config.includeMcp
                val agentLabel = "AI(${session.config.role})"
                val (reply, newPrev) =
                    openAI.agentTurn(
                        inputs = inputs,
                        previousId = session.previousId,
                        overrideInstructions = session.config.instructions,
                        overrideModel = session.config.model,
                        allowedToolClassFilter = filter,
                        includeMcp = includeMcp,
                        agentLabel = agentLabel,
                        allowedBuiltInNames = session.config.allowedBuiltInNames,
                        allowedMcpNames = session.config.allowedMcpNames,
                    )
                session.previousId = newPrev
                QuantaAISettingsState.instance.state.agents
                    .find { it.id == agentId }
                    ?.previousId = newPrev

                // Persist transcript and schedule proactive summarization
                persistAgentMessage(agentId, "user", message)
                persistAgentMessage(agentId, "assistant", reply)
                try {
                    scheduleAgentSummaryIfNeeded(agentId, session.config.model)
                } catch (_: Throwable) {
                }

                QDLog.info(logger) { "Agent[$agentId][$requestId] reply length=${reply.length}" }
                val result = AgentTaskResult(requestId, agentId, true, reply.ifBlank { "<no message>" }, null)
                fut.complete(result)
                pcs.firePropertyChange("agent_task_finished", null, result)
            } catch (t: Throwable) {
                if (isContextWindowError(t)) {
                    try {
                        val openAI = project.service<OpenAIService>()
                        val filter: ((Class<*>) -> Boolean)? = if (session.config.allowedBuiltInTools) null else { _ -> false }
                        val includeMcp = session.config.includeMcp
                        val agentLabel = "AI(${session.config.role})"
                        val retry =
                            softResetAndRetryAgentTurnOnce(
                                openAI = openAI,
                                agentId = agentId,
                                session = session,
                                message = message,
                                agentLabel = agentLabel,
                                toolClassFilter = filter,
                                includeMcp = includeMcp,
                            )
                        if (retry != null) {
                            val (reply, newPrev) = retry
                            session.previousId = newPrev
                            QuantaAISettingsState.instance.state.agents
                                .find { it.id == agentId }
                                ?.previousId = newPrev

                            persistAgentMessage(agentId, "user", message)
                            persistAgentMessage(agentId, "assistant", reply)
                            try {
                                scheduleAgentSummaryIfNeeded(agentId, session.config.model)
                            } catch (_: Throwable) {
                            }

                            val result = AgentTaskResult(requestId, agentId, true, reply.ifBlank { "<no message>" }, null)
                            fut.complete(result)
                            pcs.firePropertyChange("agent_task_finished", null, result)
                            return@submit
                        }
                    } catch (_: Throwable) {
                    }
                }

                val err = t.message ?: t.javaClass.simpleName
                val result = AgentTaskResult(requestId, agentId, false, null, err)
                fut.complete(result)
                pcs.firePropertyChange("agent_task_finished", null, result)
            }
        }
        return fut
    }

    fun sendMessage(
        agentId: String,
        message: String,
    ): String { // unchanged
        val enabled = QuantaAISettingsState.instance.state.agenticEnabled ?: true
        if (!enabled) throw IllegalStateException("Agentic mode is disabled in settings")
        val session = agents[agentId] ?: return "Agent not found: $agentId"
        val requestId = UUID.randomUUID().toString()
        pcs.firePropertyChange("agent_task_started", null, mapOf("requestId" to requestId, "agentId" to agentId))
        return try {
            val openAI = project.service<OpenAIService>()
            val inputs = mutableListOf<ResponseInputItem>()

            // Always deliver pending inbox messages at the start of the turn.
            try {
                val inbox = readAndClearInbox(agentId)
                if (inbox.isNotEmpty()) {
                    try {
                        QDLog.debug(logger) {
                            val kinds = inbox.mapNotNull { it.kind?.ifBlank { null } }.distinct()
                            "Inbox injected: agent=$agentId count=${inbox.size} kinds=$kinds"
                        }
                        project.service<ToolWindowService>().addDebugMessage(
                            "inbox_injected",
                            "agent=$agentId count=${inbox.size}",
                        )
                    } catch (_: Throwable) {
                    }

                    val inboxText =
                        buildString {
                            append("Inbox messages (auto):\n")
                            inbox.sortedBy { it.timestamp }.forEach { m ->
                                val from = m.from?.ifBlank { null } ?: "unknown"
                                val kind = m.kind?.ifBlank { null }
                                append("- [").append(from)
                                if (kind != null) append(", kind=").append(kind)
                                append("] ").append(m.text.trim()).append('\n')
                            }
                        }.trimEnd()
                    inputs.add(
                        ResponseInputItem.ofMessage(
                            ResponseInputItem.Message
                                .builder()
                                .addInputTextContent(inboxText)
                                .role(ResponseInputItem.Message.Role.SYSTEM)
                                .build(),
                        ),
                    )
                }
            } catch (_: Throwable) {
            }

            if (session.previousId == null) {
                inputs.add(
                    ResponseInputItem.ofMessage(
                        ResponseInputItem.Message
                            .builder()
                            .addInputTextContent(
                                "Agent Role: ${session.config.role}",
                            ).role(ResponseInputItem.Message.Role.SYSTEM)
                            .build(),
                    ),
                )

                // Provide agents roster so agents can message each other by id.
                try {
                    inputs.add(
                        ResponseInputItem.ofMessage(
                            ResponseInputItem.Message
                                .builder()
                                .addInputTextContent(buildAgentsRosterText())
                                .role(ResponseInputItem.Message.Role.SYSTEM)
                                .build(),
                        ),
                    )
                } catch (_: Throwable) {
                }

                // Provide rolling summary if present
                try {
                    val sum = summaryForAgent(agentId)
                    if (!sum.isNullOrBlank()) {
                        inputs.add(
                            ResponseInputItem.ofMessage(
                                ResponseInputItem.Message
                                    .builder()
                                    .addInputTextContent("Conversation summary (auto):\n" + sum)
                                    .role(ResponseInputItem.Message.Role.SYSTEM)
                                    .build(),
                            ),
                        )
                    }
                } catch (_: Throwable) {
                }

                // Provide project-specific instructions from repository-root AGENTS.md (if present)
                try {
                    val agentsText = ProjectAgentsFileManager(project).readAgentsFile(maxChars = 8_000)
                    if (agentsText.isNotBlank()) {
                        inputs.add(
                            ResponseInputItem.ofMessage(
                                ResponseInputItem.Message
                                    .builder()
                                    .addInputTextContent(agentsText)
                                    .role(ResponseInputItem.Message.Role.SYSTEM)
                                    .build(),
                            ),
                        )
                    }
                } catch (_: Throwable) {
                    // Non-fatal: proceed without AGENTS.md
                }
            }

            inputs.add(
                ResponseInputItem.ofMessage(
                    ResponseInputItem.Message
                        .builder()
                        .addInputTextContent(message)
                        .role(ResponseInputItem.Message.Role.USER)
                        .build(),
                ),
            )
            val filter: ((Class<*>) -> Boolean)? = if (session.config.allowedBuiltInTools) null else { _ -> false }
            val includeMcp = session.config.includeMcp
            val agentLabel = "AI(${session.config.role})"
            val (reply, newPrev) =
                openAI.agentTurn(
                    inputs = inputs,
                    previousId = session.previousId,
                    overrideInstructions = session.config.instructions,
                    overrideModel = session.config.model,
                    allowedToolClassFilter = filter,
                    includeMcp = includeMcp,
                    agentLabel = agentLabel,
                    allowedBuiltInNames = session.config.allowedBuiltInNames,
                    allowedMcpNames = session.config.allowedMcpNames,
                )
            session.previousId = newPrev
            QuantaAISettingsState.instance.state.agents
                .find { it.id == agentId }
                ?.previousId = newPrev

            // Persist transcript and schedule proactive summarization
            persistAgentMessage(agentId, "user", message)
            persistAgentMessage(agentId, "assistant", reply)
            try {
                scheduleAgentSummaryIfNeeded(agentId, session.config.model)
            } catch (_: Throwable) {
            }

            QDLog.info(logger) { "Agent[$agentId] reply length=${reply.length}" }
            val out = reply.ifBlank { "<no message>" }
            pcs.firePropertyChange("agent_task_finished", null, AgentTaskResult(requestId, agentId, true, out, null))
            out
        } catch (t: Throwable) {
            if (isContextWindowError(t)) {
                try {
                    val openAI = project.service<OpenAIService>()
                    val filter: ((Class<*>) -> Boolean)? = if (session.config.allowedBuiltInTools) null else { _ -> false }
                    val includeMcp = session.config.includeMcp
                    val agentLabel = "AI(${session.config.role})"
                    val retry =
                        softResetAndRetryAgentTurnOnce(
                            openAI = openAI,
                            agentId = agentId,
                            session = session,
                            message = message,
                            agentLabel = agentLabel,
                            toolClassFilter = filter,
                            includeMcp = includeMcp,
                        )
                    if (retry != null) {
                        val (reply, newPrev) = retry
                        session.previousId = newPrev
                        QuantaAISettingsState.instance.state.agents
                            .find { it.id == agentId }
                            ?.previousId = newPrev

                        persistAgentMessage(agentId, "user", message)
                        persistAgentMessage(agentId, "assistant", reply)
                        try {
                            scheduleAgentSummaryIfNeeded(agentId, session.config.model)
                        } catch (_: Throwable) {
                        }

                        val out = reply.ifBlank { "<no message>" }
                        pcs.firePropertyChange("agent_task_finished", null, AgentTaskResult(requestId, agentId, true, out, null))
                        return out
                    }
                } catch (_: Throwable) {
                }
            }

            val err = t.message ?: t.javaClass.simpleName
            pcs.firePropertyChange("agent_task_finished", null, AgentTaskResult(requestId, agentId, false, null, err))
            "Agent error: $err"
        }
    }

    fun exists(agentId: String): Boolean = agents.containsKey(agentId)

    override fun dispose() {
        executors.values.forEach { e ->
            try {
                e.shutdownNow()
            } catch (_: Throwable) {
            }
        }
        executors.clear()
    }
}
