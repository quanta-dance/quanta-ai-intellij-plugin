package com.github.quanta_dance.quanta.plugins.intellij.mcp

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class McpServersStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        // Ensure the watcher is instantiated so it subscribes to VFS changes
        project.service<McpServersWatcher>()
        // Trigger an initial reconcile at startup
        project.service<McpClientService>().refresh()
    }
}
