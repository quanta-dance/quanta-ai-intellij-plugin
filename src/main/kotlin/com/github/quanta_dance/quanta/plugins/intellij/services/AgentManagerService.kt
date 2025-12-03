// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.services

import com.github.quanta_dance.quanta.plugins.intellij.services.QDLog
import com.github.quanta_dance.quanta.plugins.intellij.services.ToolWindowService
import com.github.quanta_dance.quanta.plugins.intellij.settings.Instructions
import com.github.quanta_dance.quanta.plugins.intellij.settings.QuantaAISettingsState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.openai.models.responses.ResponseInputItem
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class AgentManagerService(private val project: Project) {
    data class AgentConfig(
        val role: String,
        val model: String?,
        val instructions: String?,
        val includeMcp: Boolean = true,
        val allowedBuiltInTools: Boolean = true,
        val allowedMcpServers: List<String>? = null,
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

    private val logger = Logger.getInstance(AgentManagerService::class.java)
    private val agents = ConcurrentHashMap<String, AgentSession>()
    private val pcs = PropertyChangeSupport(this)

    fun addPropertyChangeListener(listener: PropertyChangeListener) = pcs.addPropertyChangeListener(listener)

    fun getAgentsSnapshot(): List<AgentSnapshot> =
        agents.values.map { AgentSnapshot(it.id, it.config.role, it.config.instructions, it.config.model) }

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
        agents[id] = AgentSession(id = id, config = config.copy(instructions = baseInstr))
        project.service<ToolWindowService>().addToolingMessage("AgentManager", "Created agent ${config.role} [$id]")
        pcs.firePropertyChange("agents", null, id)
        return id
    }

    fun sendMessage(agentId: String, message: String): String {
        val enabled = QuantaAISettingsState.instance.state.agenticEnabled ?: true
        if (!enabled) throw IllegalStateException("Agentic mode is disabled in settings")
        val session = agents[agentId] ?: return "Agent not found: $agentId"
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
        )
        session.previousId = newPrev
        QDLog.info(logger) { "Agent[$agentId] reply length=${reply.length}" }
        return reply.ifBlank { "<no message>" }
    }

    fun exists(agentId: String): Boolean = agents.containsKey(agentId)
}
