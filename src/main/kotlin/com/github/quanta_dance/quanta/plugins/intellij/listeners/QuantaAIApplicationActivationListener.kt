// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.listeners

import com.github.quanta_dance.quanta.plugins.intellij.services.AgentManagerService
import com.github.quanta_dance.quanta.plugins.intellij.services.OpenAIPrewarmService
import com.github.quanta_dance.quanta.plugins.intellij.services.OpenAIService
import com.github.quanta_dance.quanta.plugins.intellij.services.ToolWindowService
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class QuantaAIApplicationActivationListener : ProjectActivity {
    override suspend fun execute(project: Project) {
        // Prewarm OpenAI client and DNS on project open to reduce first-turn latency
        project.service<OpenAIPrewarmService>().prewarm()

        // Start a new AI session on project open to avoid stale threads and keep manager/sub-agents in sync
        try {
            project.service<OpenAIService>().newSession()
        } catch (_: Throwable) { }

        // Inform user about the new session and show current sub-agents inventory
        try {
            val agents = project.service<AgentManagerService>().getAgentsSnapshot()
            val header = "New AI session started. Sub-agents available: ${agents.size}"
            project.service<ToolWindowService>().addToolingMessage("AI(manager)", header)
            agents.forEach { a ->
                val line = buildString {
                    append("agent id=").append(a.id).append(", role=").append(a.role)
                    a.model?.let { m -> append(", model=").append(m) }
                }
                project.service<ToolWindowService>().addToolingMessage("AI(manager)", line)
            }
        } catch (_: Throwable) { }
    }
}
