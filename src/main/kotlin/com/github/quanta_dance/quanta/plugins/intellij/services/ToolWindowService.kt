// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.services

import com.github.quanta_dance.quanta.plugins.intellij.models.Suggestion
import com.github.quanta_dance.quanta.plugins.intellij.toolWindow.cards.ImageCardPanel
import com.github.quanta_dance.quanta.plugins.intellij.toolWindow.cards.SpinnerCardPanel
import com.github.quanta_dance.quanta.plugins.intellij.toolWindow.cards.SuggestionCardPanel
import com.github.quanta_dance.quanta.plugins.intellij.toolWindow.cards.ToolExecCardPanel
import com.github.quanta_dance.quanta.plugins.intellij.toolWindow.cards.UserMessageCardPanel
import com.github.quanta_dance.quanta.plugins.intellij.toolWindow.panels.MainPanel
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import java.util.Timer
import java.util.TimerTask
import javax.swing.Box
import javax.swing.JPanel
import javax.swing.SwingUtilities

@Service(Service.Level.PROJECT)
class ToolWindowService(
    private val project: Project,
) {
    private var mainToolPanel: JPanel? = null
    private var messageScrollPane: JBScrollPane? = null

    @Volatile
    private var restoredOnce: Boolean = false

    fun clear() {
        ApplicationManager.getApplication().invokeLater {
            mainToolPanel?.removeAll()
            mainToolPanel?.revalidate()
            mainToolPanel?.repaint()
            messageScrollPane?.verticalScrollBar?.value = messageScrollPane?.verticalScrollBar?.maximum!!
        }
    }

    fun addUserMessage(message: String): UserMessageCardPanel {
        // Create the panel instance synchronously so caller receives a reference to it (e.g., for streaming updates)
        val messagePanel = UserMessageCardPanel(message)

        // Schedule adding to UI on EDT
        ApplicationManager.getApplication().invokeLater {
            mainToolPanel?.add(messagePanel)
            mainToolPanel?.revalidate()
            mainToolPanel?.repaint()
            // Scroll to bottom safely
            messageScrollPane?.verticalScrollBar?.value = messageScrollPane?.verticalScrollBar?.maximum ?: 0
            messagePanel.repaint()
            messagePanel.validate()
        }

        project.service<AIVoiceService>().stopTalking()
        return messagePanel
    }

    fun addToolingMessage(
        toolName: String,
        arguments: String,
    ) {
        ApplicationManager.getApplication().invokeLater {
            mainToolPanel?.add(ToolExecCardPanel(toolName, arguments))
            mainToolPanel?.revalidate()
            mainToolPanel?.repaint()
            messageScrollPane?.verticalScrollBar?.value = messageScrollPane?.verticalScrollBar?.maximum!!
        }
    }

    fun addDebugMessage(
        tag: String,
        text: String,
    ) {
        val enabled =
            try {
                com.github.quanta_dance.quanta.plugins.intellij.settings.QuantaAISettingsState.instance.state.debugEnabled
            } catch (_: Throwable) {
                false
            }
        if (!enabled) return

        val safe =
            try {
                if (text.length <= 2_000) text else text.take(2_000) + "\n... (truncated)"
            } catch (_: Throwable) {
                "<debug message unavailable>"
            }
        addToolingMessage("AI(debug):$tag", safe)
    }

    fun clearDebugMessages() {
        ApplicationManager.getApplication().invokeLater {
            val container = mainToolPanel ?: return@invokeLater
            val toRemove =
                container.components.filter { c ->
                    val border = (c as? javax.swing.JComponent)?.border
                    val title = (border as? javax.swing.border.TitledBorder)?.title
                    title != null && (title.startsWith("AI(debug):") || title == "Usage")
                }
            if (toRemove.isEmpty()) return@invokeLater
            toRemove.forEach { container.remove(it) }
            container.revalidate()
            container.repaint()
        }
    }

    inner class ToolExecHandle(
        private val container: JPanel,
        private val panel: ToolExecCardPanel,
    ) {
        fun setText(text: String) {
            ApplicationManager.getApplication().invokeLater {
                panel.setText(text)
                container.revalidate()
                container.repaint()
                SwingUtilities.invokeLater {
                    messageScrollPane?.verticalScrollBar?.value = messageScrollPane?.verticalScrollBar?.maximum!!
                }
            }
        }

        fun appendLine(line: String) {
            ApplicationManager.getApplication().invokeLater {
                panel.appendLine(line)
                container.revalidate()
                container.repaint()
                SwingUtilities.invokeLater {
                    messageScrollPane?.verticalScrollBar?.value = messageScrollPane?.verticalScrollBar?.maximum!!
                }
            }
        }
    }

    fun startToolingMessage(
        toolName: String,
        initialText: String,
    ): ToolExecHandle? {
        val container = mainToolPanel ?: return null
        val panel = ToolExecCardPanel(toolName, initialText)
        ApplicationManager.getApplication().invokeLater {
            container.add(panel)
            container.revalidate()
            container.repaint()
            SwingUtilities.invokeLater {
                messageScrollPane?.verticalScrollBar?.value = messageScrollPane?.verticalScrollBar?.maximum!!
            }
        }
        return ToolExecHandle(container, panel)
    }

    fun addImage(
        title: String,
        url: String,
    ) {
        ApplicationManager.getApplication().invokeLater {
            mainToolPanel?.add(ImageCardPanel(title, url))
            mainToolPanel?.revalidate()
            mainToolPanel?.repaint()
            messageScrollPane?.verticalScrollBar?.value = messageScrollPane?.verticalScrollBar?.maximum!!
        }
    }

    fun addSuggestions(suggestions: List<Suggestion>?) {
        ApplicationManager.getApplication().invokeLater {
            suggestions?.forEach { suggestion ->
                mainToolPanel?.add(SuggestionCardPanel(suggestion))
                mainToolPanel?.add(Box.createVerticalStrut(5))
            }
            mainToolPanel?.revalidate()
            mainToolPanel?.repaint()
            messageScrollPane?.verticalScrollBar?.value = messageScrollPane?.verticalScrollBar?.maximum!!
        }
    }

    fun setToolWindowFactory(toolPanel: MainPanel) {
        this.messageScrollPane = toolPanel.messageScrollPane
        this.mainToolPanel = toolPanel.messagePanel

        // Restore chat history when UI is ready (only once per tool window creation)
        if (!restoredOnce) {
            restoredOnce = true
            restorePersistedChat()
        }
    }

    private fun restorePersistedChat() {
        try {
            val state = com.github.quanta_dance.quanta.plugins.intellij.settings.QuantaAISettingsState.instance.state
            val projectService = project.service<OpenAIService>()

            // Use same conversation key logic as OpenAIService via reflection
            val key =
                try {
                    val m = projectService.javaClass.getDeclaredMethod("conversationKeyForMain")
                    m.isAccessible = true
                    m.invoke(projectService) as String
                } catch (_: Throwable) {
                    "main@no-branch"
                }

            val messages = state.conversations[key].orEmpty()

            ApplicationManager.getApplication().invokeLater {
                messages.forEach { msg ->
                    when (msg.role.lowercase()) {
                        "user" -> addUserMessage(msg.text)
                        "assistant" -> addToolingMessage("AI(manager)", msg.text)
                        else -> addToolingMessage(msg.role, msg.text)
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    inner class SpinnerHandle(
        private val container: JPanel,
        private val panel: SpinnerCardPanel,
        private val timer: Timer,
    ) {
        fun stopSuccess() {
            ApplicationManager.getApplication().invokeLater {
                timer.cancel()
                panel.stop()
                container.remove(panel)
                container.revalidate()
                container.repaint()
            }
        }

        fun stopError(errorText: String) {
            ApplicationManager.getApplication().invokeLater {
                timer.cancel()
                panel.showError(errorText)
                container.revalidate()
                container.repaint()
            }
        }
    }

    fun startSpinner(title: String = "AI is thinking"): SpinnerHandle? {
        val container = mainToolPanel ?: return null
        val card = SpinnerCardPanel(title)
        val timer = Timer("toolwindow-spinner", true)
        val start = System.currentTimeMillis()
        ApplicationManager.getApplication().invokeLater {
            container.add(card)
            container.revalidate()
            container.repaint()
            SwingUtilities.invokeLater {
                messageScrollPane?.verticalScrollBar?.value = messageScrollPane?.verticalScrollBar?.maximum!!
            }
        }
        timer.scheduleAtFixedRate(
            object : TimerTask() {
                override fun run() {
                    val elapsed = ((System.currentTimeMillis() - start) / 1000)
                    SwingUtilities.invokeLater { card.setSeconds(elapsed) }
                }
            },
            0L,
            1000L,
        )
        return SpinnerHandle(container, card, timer)
    }
}
