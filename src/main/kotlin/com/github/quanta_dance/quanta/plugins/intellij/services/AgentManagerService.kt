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
        project.service<ToolWindowService>().addToolingMessage("AgentManager", "Reset agents conversation state for new session")
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
