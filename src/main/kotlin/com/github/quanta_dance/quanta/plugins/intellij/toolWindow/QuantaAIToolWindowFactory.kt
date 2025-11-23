package com.github.quanta_dance.quanta.plugins.intellij.toolWindow

import com.github.quanta_dance.quanta.plugins.intellij.services.OpenAIService
import com.github.quanta_dance.quanta.plugins.intellij.services.ToolWindowService
import com.github.quanta_dance.quanta.plugins.intellij.toolWindow.panels.MainPanel
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory


class QuantaAIToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val mainPanel = MainPanel(project)

        project.service<ToolWindowService>().setToolWindowFactory(mainPanel)

        toolWindow.title = "Quanta Dance"

        // Add SettingsAction and New Session action to the tool window native title actions
        val actionManager = ActionManager.getInstance()
        val titleGroup = DefaultActionGroup()

        // New Session action — clears current dialog and starts a new session (with user confirmation)
        val newSessionAction = object : AnAction("New Session") {
            override fun actionPerformed(e: AnActionEvent) {
                try {
                    // Ask user to confirm starting a new session to avoid accidental loss of conversation
                    val confirmed = Messages.showYesNoDialog(
                        project,
                        "Start a new session? This will clear the current conversation history.",
                        "Start New Session",
                        "Start New Session",
                        "Cancel",
                        Messages.getQuestionIcon()
                    ) == Messages.YES

                    if (!confirmed) return

                    val service = project.service<OpenAIService>()
                    val newId = service.newSession()
                    project.service<ToolWindowService>().addToolingMessage("AI", "New session started: $newId")
                } catch (ex: Throwable) {
                    try { project.service<ToolWindowService>().addToolingMessage("AI", "Failed to start new session: ${ex.message}") } catch (_: Throwable) {}
                }
            }
        }

        titleGroup.add(newSessionAction)

        val settingsAction = actionManager.getAction("SettingsAction")
        if (settingsAction != null) {
            titleGroup.add(settingsAction)
            try {
                // Use getChildActionsOrStubs to avoid expanding action groups manually
                toolWindow.setTitleActions(titleGroup.childActionsOrStubs.toList())
            } catch (e: NoSuchMethodError) {
                // For older SDKs that may have a different signature, try vararg version via reflection
                try {
                    val actions = titleGroup.childActionsOrStubs.toList().toTypedArray()
                    val method = toolWindow.javaClass.getMethod("setTitleActions", Array<Any>::class.java)
                    method.invoke(toolWindow, arrayOf(actions))
                } catch (ex: Exception) {
                    // ignore — if this fails, the actions will not appear in native title; fallback could be implemented
                }
            }
        } else {
            // If no settings action, still try to set newSession as title action
            try {
                toolWindow.setTitleActions(titleGroup.childActionsOrStubs.toList())
            } catch (e: NoSuchMethodError) {
                try {
                    val actions = titleGroup.childActionsOrStubs.toList().toTypedArray()
                    val method = toolWindow.javaClass.getMethod("setTitleActions", Array<Any>::class.java)
                    method.invoke(toolWindow, arrayOf(actions))
                } catch (ex: Exception) {
                    // ignore
                }
            }
        }

        val content = ContentFactory.getInstance().createContent(
            mainPanel, "", false
        )
        toolWindow.contentManager.addContent(content)

        // Ensure the tool window is registered for each project
        toolWindow.setAvailable(true, null)
    }

}
