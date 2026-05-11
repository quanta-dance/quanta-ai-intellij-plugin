// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.chat.agents

import com.github.quanta_dance.quanta.plugins.intellij.backend.settings.QuantaAISessionState
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport

/**
 * Coordinates agent-team lifecycle decisions for the current chat session.
 *
 * This service creates the default agent team and emits lifecycle-related updates that other
 * backend chat services can translate into UI-visible state.
 */
@Service(Service.Level.PROJECT)
class AgentLifecycleService(
    private val project: Project,
) {
    private val pcs = PropertyChangeSupport(this)

    fun addPropertyChangeListener(listener: PropertyChangeListener) = pcs.addPropertyChangeListener(listener)

    fun createDefaultTeam(registry: AgentRegistryService): List<String> {
        if ((QuantaAISessionState.instance.state.agenticEnabled ?: true).not()) return emptyList()
        if (registry.getAgentsSnapshot().isNotEmpty()) return registry.getAgentsSnapshot().map { it.id }

        val commonComms = setOf("AgentSendMessageTool", "AgentPostMessageTool")
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
                        "ReadFileContentPlatformTest",
                    )

        val ids = mutableListOf<String>()
        ids += registry.createAgent(
            AgentRegistryService.AgentConfig(
                role = "developer",
                model = null,
                instructions = "Focus on implementation details and code changes.",
                allowedBuiltInNames = developerTools,
                allowedMcpNames = null,
            ),
        )
        ids += registry.createAgent(
            AgentRegistryService.AgentConfig(
                role = "reviewer",
                model = null,
                instructions = "Focus on correctness, regressions and concise reviews.",
                allowedBuiltInNames = commonComms,
                allowedMcpNames = null,
            ),
        )
        ids += registry.createAgent(
            AgentRegistryService.AgentConfig(
                role = "commentator",
                model = null,
                instructions = "Focus on explanatory notes and user-facing summaries.",
                allowedBuiltInNames = commonComms,
                allowedMcpNames = null,
            ),
        )
        pcs.firePropertyChange("agents", null, ids)
        return ids
    }
}
