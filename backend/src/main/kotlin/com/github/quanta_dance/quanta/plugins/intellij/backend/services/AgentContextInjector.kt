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

    @Volatile
    private var lastInjectedProjectLandscapeHash: Int? = null

    fun reset() {
        initialContextInjectedThisIdeSession = false
        lastInjectedAgentsMdHash = null
        lastInjectedAgentsRosterHash = null
        lastInjectedProjectLandscapeHash = null
    }

    fun injectBaseContextForAgentTurn(
        inputs: MutableList<ResponseInputItem>,
        previousId: String?,
    ) {
        val needBaseContext = (previousId == null) || (!initialContextInjectedThisIdeSession)

        try {
            val ctx = project.service<ProjectContextSnapshotService>().agentsMd()
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

        try {
            val landscape = project.service<ProjectContextSnapshotService>().landscape()
            if (landscape.isNotBlank()) {
                val hash = landscape.hashCode()
                if (needBaseContext || lastInjectedProjectLandscapeHash == null || lastInjectedProjectLandscapeHash != hash) {
                    inputs.add(0, systemMessageFactory(landscape))
                    lastInjectedProjectLandscapeHash = hash
                }
            }
        } catch (_: Throwable) {
        }

        if (needBaseContext) {
            initialContextInjectedThisIdeSession = true
        }
    }

    private fun buildAgentsRosterContext(): String {
        val participants =
            try {
                project.service<CollaborationRosterService>().participants()
            } catch (_: Throwable) {
                emptyList()
            }
        return buildString {
            append("Collaboration roster (auto):\n")
            if (participants.isEmpty()) {
                append("- <none>")
            } else {
                participants
                    .sortedBy { it.displayName }
                    .forEach { participant ->
                        append("- id=")
                            .append(participant.id)
                            .append(", name=")
                            .append(participant.displayName)
                            .append(", kind=")
                            .append(participant.kind)
                            .append(", capabilities=")
                            .append(participant.capabilities.sortedBy { it.name }.joinToString { it.name })
                            .append('\n')
                    }
            }
        }.trimEnd()
    }
}
