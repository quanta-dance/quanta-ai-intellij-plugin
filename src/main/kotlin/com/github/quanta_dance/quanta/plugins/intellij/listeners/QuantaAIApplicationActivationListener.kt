// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.listeners

import com.github.quanta_dance.quanta.plugins.intellij.mcp.McpClientService
import com.github.quanta_dance.quanta.plugins.intellij.services.OpenAIPrewarmService
import com.github.quanta_dance.quanta.plugins.intellij.services.OpenAIService
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
        } catch (_: Throwable) {
        }

        // Ensure MCP services are initialized; discovery continues in background
        try {
            project.service<McpClientService>()
        } catch (_: Throwable) {
        }
        // Note: intentionally no Tool Window messages here to avoid startup noise.
        // Manager receives bootstrap context on its first turn.
    }
}
