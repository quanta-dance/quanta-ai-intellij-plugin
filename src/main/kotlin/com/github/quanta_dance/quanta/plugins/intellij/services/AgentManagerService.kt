// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.services

import com.github.quanta_dance.quanta.plugins.intellij.services.QDLog
import com.github.quanta_dance.quanta.plugins.intellij.services.ToolWindowService
import com.github.quanta_dance.quanta.plugins.intellij.settings.Instructions
import com.github.quanta_dance.quanta.plugins.intellij.settings.QuantaAISettingsState
import com.intellij.openapi.Disposable
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
class AgentManagerService(private val project: Project) : Disposable {
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

    fun createAgent(config: AgentConfig): String {
        val enabled = QuantaAISettingsState.instance.state.agenticEnabled ?: true
        if (!enabled) throw IllegalStateException("Agentic mode is disabled in settings")
        val id = UUID.randomUUID().toString()
        val baseInstr = buildString {
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
                id = id, role = session.config.role, model = session.config.model, instructions = session.config.instructions, previousId = session.previousId,
            ),
        )
        pcs.firePropertyChange("agents", null, id)
        return id
    }

    fun removeAgent(agentId: String): Boolean {
        val removed = agents.remove(agentId) ?: return false
        executors.remove(agentId)?.let { exec ->
            try { exec.shutdownNow() } catch (_: Throwable) {}
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
            try { exec.shutdownNow() } catch (_: Throwable) {}
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
                try { exec.shutdownNow(); stopped++ } catch (_: Throwable) {}
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

    fun sendMessageAsync(agentId: String, message: String): CompletableFuture<AgentTaskResult> { /* unchanged */
        val enabled = QuantaAISettingsState.instance.state.agenticEnabled ?: true
        if (!enabled) return CompletableFuture.completedFuture(AgentTaskResult("", agentId, false, null, "Agentic mode disabled"))
        val session = agents[agentId]
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
                            ResponseInputItem.Message.builder().addInputTextContent("Agent Role: ${session.config.role}").role(ResponseInputItem.Message.Role.SYSTEM).build(),
                        ),
                    )
                }
                inputs.add(
                    ResponseInputItem.ofMessage(
                        ResponseInputItem.Message.builder().addInputTextContent(message).role(ResponseInputItem.Message.Role.USER).build(),
                    ),
                )
                val filter: ((Class<*>) -> Boolean)? = if (session.config.allowedBuiltInTools) null else { _ -> false }
                val includeMcp = session.config.includeMcp
                val agentLabel = "AI(${session.config.role})"
                val (reply, newPrev) = openAI.agentTurn(
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
                QuantaAISettingsState.instance.state.agents.find { it.id == agentId }?.previousId = newPrev
                QDLog.info(logger) { "Agent[$agentId][$requestId] reply length=${reply.length}" }
                val result = AgentTaskResult(requestId, agentId, true, reply.ifBlank { "<no message>" }, null)
                fut.complete(result)
                pcs.firePropertyChange("agent_task_finished", null, result)
            } catch (t: Throwable) {
                val err = t.message ?: t.javaClass.simpleName
                val result = AgentTaskResult(requestId, agentId, false, null, err)
                fut.complete(result)
                pcs.firePropertyChange("agent_task_finished", null, result)
            }
        }
        return fut
    }

    fun sendMessage(agentId: String, message: String): String { /* unchanged */
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
                        ResponseInputItem.Message.builder().addInputTextContent("Agent Role: ${session.config.role}").role(ResponseInputItem.Message.Role.SYSTEM).build(),
                    ),
                )
            }
            inputs.add(
                ResponseInputItem.ofMessage(
                    ResponseInputItem.Message.builder().addInputTextContent(message).role(ResponseInputItem.Message.Role.USER).build(),
                ),
            )
            val filter: ((Class<*>) -> Boolean)? = if (session.config.allowedBuiltInTools) null else { _ -> false }
            val includeMcp = session.config.includeMcp
            val agentLabel = "AI(${session.config.role})"
            val (reply, newPrev) = openAI.agentTurn(
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
            QuantaAISettingsState.instance.state.agents.find { it.id == agentId }?.previousId = newPrev
            QDLog.info(logger) { "Agent[$agentId] reply length=${reply.length}" }
            val out = reply.ifBlank { "<no message>" }
            pcs.firePropertyChange("agent_task_finished", null, AgentTaskResult(requestId, agentId, true, out, null))
            out
        } catch (t: Throwable) {
            val err = t.message ?: t.javaClass.simpleName
            pcs.firePropertyChange("agent_task_finished", null, AgentTaskResult(requestId, agentId, false, null, err))
            "Agent error: $err"
        }
    }

    fun exists(agentId: String): Boolean = agents.containsKey(agentId)

    override fun dispose() {
        executors.values.forEach { e ->
            try { e.shutdownNow() } catch (_: Throwable) {}
        }
        executors.clear()
    }
}
