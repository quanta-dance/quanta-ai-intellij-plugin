// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.chat.agents

import com.github.quanta_dance.quanta.plugins.intellij.backend.settings.Instructions
import com.github.quanta_dance.quanta.plugins.intellij.backend.settings.QuantaAISessionState
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.util.*

/**
 * Session-scoped registry of delegated agents available to the backend chat runtime.
 *
 * It owns agent identity, role/configuration snapshots, and persistence into session state so
 * multi-agent orchestration can survive chat reloads.
 */
@Service(Service.Level.PROJECT)
class AgentRegistryService(
    private val project: Project,
) {
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

    private val agents = LinkedHashMap<String, AgentSession>()

    init {
        QuantaAISessionState.instance.state.agents.forEach { profile ->
            val session = AgentSession(
                id = profile.id,
                config = AgentConfig(profile.role, profile.model, profile.instructions),
                previousId = profile.previousId,
            )
            agents[session.id] = session
        }
    }

    fun getAgentsSnapshot(): List<AgentSnapshot> =
        agents.values.map { AgentSnapshot(it.id, it.config.role, it.config.instructions, it.config.model) }

    fun getSession(agentId: String): AgentSession? = agents[agentId]

    fun createAgent(config: AgentConfig): String {
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
        QuantaAISessionState.instance.state.agents.add(
            QuantaAISessionState.AgentProfile(
                id = id,
                role = session.config.role,
                model = session.config.model,
                instructions = session.config.instructions,
                previousId = session.previousId,
            ),
        )
        return id
    }
}
