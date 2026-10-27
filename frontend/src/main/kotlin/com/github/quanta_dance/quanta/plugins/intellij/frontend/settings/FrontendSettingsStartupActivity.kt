// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.frontend.settings

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Startup sync that pushes the local frontend settings snapshot to the backend.
 *
 * This is especially important in split-mode, where backend services may start before the frontend
 * settings UI has synchronized the effective URL, token, and related runtime configuration.
 */
class FrontendSettingsStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        // Start the frontend-owned JSON read and backend sync before the chat model can request MCP
        // status. The sync itself remains non-blocking for MCP transport discovery on the backend.
        project.service<FrontendMcpConfigService>()
        project.service<FrontendSettingsSyncStateService>().syncOnStartup()
    }
}
