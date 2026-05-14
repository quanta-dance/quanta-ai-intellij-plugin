// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.openai.models.responses.ResponseInputItem

/**
 * Injects durable base context for agent turns.
 *
 * This keeps stable session-scoped context concerns out of [OpenAIService], while preserving the
 * same injection policy and deduplication behavior across repeated turns inside one IDE session.
 */
class AgentContextInjector(
    private val project: Project,
    private val systemMessageFactory: (String) -> ResponseInputItem,
) {
    @Volatile
    private var initialContextInjectedThisIdeSession: Boolean = false

    @Volatile
    private var lastInjectedAgentsMdHash: Int? = null

    @Volatile
    private var lastInjectedAgentsRosterHash: Int? = null

    fun reset() {
        initialContextInjectedThisIdeSession = false
        lastInjectedAgentsMdHash = null
        lastInjectedAgentsRosterHash = null
    }

    fun injectBaseContextForAgentTurn(
        inputs: MutableList<ResponseInputItem>,
        previousId: String?,
    ) {
        val needBaseContext = (previousId == null) || (!initialContextInjectedThisIdeSession)

        try {
            val ctx = ProjectAgentsFileManager(project).readAgentsFile(maxChars = 8_000)
            if (ctx.isNotBlank()) {
                val hash = ctx.hashCode()
                if (needBaseContext || lastInjectedAgentsMdHash == null || lastInjectedAgentsMdHash != hash) {
                    inputs.add(0, systemMessageFactory("AGENTS.md:\n$ctx"))
                    lastInjectedAgentsMdHash = hash
                }
            }
        } catch (_: Throwable) {
        }

        try {
            val roster = buildAgentsRosterContext()
            val hash = roster.hashCode()
            if (needBaseContext || lastInjectedAgentsRosterHash == null || lastInjectedAgentsRosterHash != hash) {
                inputs.add(0, systemMessageFactory(roster))
                lastInjectedAgentsRosterHash = hash
            }
        } catch (_: Throwable) {
        }

        if (needBaseContext) {
            initialContextInjectedThisIdeSession = true
        }
    }

    private fun buildAgentsRosterContext(): String {
        val agents =
            try {
                project.service<AgentManagerService>().getAgentsSnapshot()
            } catch (_: Throwable) {
                emptyList()
            }
        val builder = StringBuilder()
        builder.append("Agents roster (auto):\n")
        if (agents.isEmpty()) {
            builder.append("- <none>")
            return builder.toString()
        }
        agents.forEach { agent ->
            builder.append("- id=").append(agent.id).append(", role=").append(agent.role)
            agent.model?.let { model -> builder.append(", model=").append(model) }
            builder.append('\n')
        }
        return builder.toString().trimEnd()
    }
}
