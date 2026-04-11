// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport

@Service(Service.Level.PROJECT)
class AgentManagerService(private val project: Project) : Disposable {
    data class AgentConfig(
        val role: String,
        val model: String?,
        val instructions: String?,
        val includeMcp: Boolean = true,
        val allowedBuiltInTools: Boolean = true,
        val allowedMcpServers: List<String>? = null,
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

    private val log = Logger.getInstance(AgentManagerService::class.java)
    private val pcs = PropertyChangeSupport(this)
    private val agents = linkedMapOf<String, AgentSession>()

    init {
        QuantaAISettingsState.instance.state.agents.forEach { pa ->
            agents[pa.id] = AgentSession(pa.id, AgentConfig(pa.role, pa.model, pa.instructions), pa.previousId)
        }
    }

    fun addPropertyChangeListener(listener: PropertyChangeListener) = pcs.addPropertyChangeListener(listener)
    fun removePropertyChangeListener(listener: PropertyChangeListener) = pcs.removePropertyChangeListener(listener)

    fun getAgentsSnapshot(): List<AgentSnapshot> =
        agents.values.map { AgentSnapshot(it.id, it.config.role, it.config.instructions, it.config.model) }

    fun getAgentAllowedBuiltInNames(agentId: String): Set<String>? = agents[agentId]?.config?.allowedBuiltInNames
    fun getAgentAllowedMcpNames(agentId: String): Set<String>? = agents[agentId]?.config?.allowedMcpNames

    fun createAgent(config: AgentConfig): String {
        val id = "agent-${agents.size + 1}"
        agents[id] = AgentSession(id, config)
        QuantaAISettingsState.instance.state.agents.add(
            QuantaAISettingsState.AgentProfile(id, config.role, config.model, config.instructions)
        )
        return id
    }

    fun exists(agentId: String): Boolean = agents.containsKey(agentId)

    fun sendMessage(agentId: String, message: String): String {
        if (!exists(agentId)) return "unknown agent id: $agentId"
        return "ok"
    }

    fun postInboxMessage(
        toAgentId: String,
        from: String?,
        text: String,
        kind: String?,
    ): Boolean {
        if (!exists(toAgentId) || text.isBlank()) return false
        addInboxMessage(toAgentId, from ?: "manager", text, kind ?: "notification")
        return true
    }

    fun removeAgent(id: String): Boolean = agents.remove(id) != null
    fun stopAgent(id: String) {}
    fun stopAllAgents() {}
    fun resetForNewSession() {}

    fun readAndClearInbox(agentId: String): List<QuantaAISettingsState.AgentInboxMessage> =
        QuantaAISettingsState.instance.state.agentInboxes[agentId].orEmpty()
            .also { QuantaAISettingsState.instance.state.agentInboxes[agentId] = mutableListOf() }

    fun addInboxMessage(agentId: String, from: String, text: String, kind: String = "message") {
        val list = QuantaAISettingsState.instance.state.agentInboxes.getOrPut(agentId) { mutableListOf() }
        list.add(QuantaAISettingsState.AgentInboxMessage(System.currentTimeMillis(), from, text, kind))
    }

    fun getConversation(agentId: String): List<QuantaAISettingsState.PersistedMessage> =
        QuantaAISettingsState.instance.state.conversations[agentId].orEmpty()

    fun setConversation(agentId: String, messages: List<QuantaAISettingsState.PersistedMessage>) {
        QuantaAISettingsState.instance.state.conversations[agentId] = messages.toMutableList()
    }

    fun getConversationSummary(agentId: String): String? =
        QuantaAISettingsState.instance.state.conversationSummaries[agentId]

    fun setConversationSummary(agentId: String, summary: String) {
        QuantaAISettingsState.instance.state.conversationSummaries[agentId] = summary
    }

    fun clearConversation(agentId: String) {
        QuantaAISettingsState.instance.state.conversations.remove(agentId)
    }

    fun saveAgentState() {}
    override fun dispose() {}
}
