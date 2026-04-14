package com.github.quanta_dance.quanta.plugins.intellij.frontend.contracts

import com.github.quanta_dance.quanta.plugins.intellij.frontend.services.ToolWindowService
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolProgressEvent
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolProgressKind
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolProgressService
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.Service.Level
import com.intellij.openapi.project.Project

@Service(Level.PROJECT)
class FrontendToolProgressService(
    private val project: Project,
) : ToolProgressService {
    companion object {
        fun getInstance(project: Project): FrontendToolProgressService =
            project.getService(FrontendToolProgressService::class.java)
    }

    override fun publish(event: ToolProgressEvent) {
        val toolWindow = project.getService(ToolWindowService::class.java)
        when (event.kind) {
            ToolProgressKind.START -> toolWindow.startToolingMessage(event.toolName, event.message)
            ToolProgressKind.UPDATE -> toolWindow.addToolingMessage(event.toolName, event.message)
            ToolProgressKind.SUCCESS -> toolWindow.addToolingMessage(event.toolName, "SUCCESS: ${event.message}")
            ToolProgressKind.ERROR -> toolWindow.addToolingMessage(event.toolName, "ERROR: ${event.message}")
            ToolProgressKind.DEBUG -> toolWindow.addDebugMessage(event.toolName, event.message)
        }
    }
}
