// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.listeners

import com.github.quanta_dance.quanta.plugins.intellij.mcp.McpClientService
import com.github.quanta_dance.quanta.plugins.intellij.services.OpenAIPrewarmService
import com.github.quanta_dance.quanta.plugins.intellij.services.OpenAIService
import com.github.quanta_dance.quanta.plugins.intellij.services.SessionMemoryService
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class QuantaAIApplicationActivationListener : ProjectActivity {
    override suspend fun execute(project: Project) {
        // Prewarm OpenAI client and DNS on project open to reduce first-turn latency
        project.service<OpenAIPrewarmService>().prewarm()

        // Reset server-side thread pointers on project open to avoid stale threads,
        // but preserve persisted chat history and tool window restore.
        try {
            project.service<OpenAIService>().resetThreadStatePreservingHistory()
        } catch (_: Throwable) {
        }

        try {
            project.service<SessionMemoryService>().apply {
                ensureInitialized()
                refreshFromCurrentState(reason = "project_resume", explicitNote = "IDE session resumed/reopened.")
            }
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
