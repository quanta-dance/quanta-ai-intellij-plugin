// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.frontend.toolwindow.panels


//import com.github.quanta_dance.quanta.plugins.intellij.services.SessionPlanService
import com.github.quanta_dance.quanta.plugins.intellij.frontend.services.ToolWindowService
import com.github.quanta_dance.quanta.plugins.intellij.frontend.settings.FrontendQuantaSettingsState
import com.github.quanta_dance.quanta.plugins.intellij.frontend.toolwindow.actions.*
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.*

// TODO: this is legacy class and must be rework into ChatApp
class MainPanel(var project: Project) : JPanel(BorderLayout()) {
    val messagePanel =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(5, 5, 5, 5)
            background = UIManager.getColor("Panel.background")
            alignmentX = Component.LEFT_ALIGNMENT
        }

    val messageScrollPane =
        JBScrollPane(messagePanel).apply {
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_ALWAYS
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }

    private val promptTextArea = newPromptTextArea()

    private val submitButton =
        JButton(AllIcons.Actions.RunAll).apply {
            isFocusable = false
            // TODO: this functionality we already have in ChatApp
//            addActionListener { e ->
//                project.service<OpenAIService>().let { service ->
//                    service.addPropertyChangeListener { evt ->
//                        this.setIcon(if (evt.newValue == true) AllIcons.Actions.Suspend else AllIcons.Actions.RunAll)
//                    }
//                    if (this.icon == AllIcons.Actions.Suspend) {
//                        service.stopProcessing()
//                    }
//                }
//                submitPrompt(e)
//            }
        }

    private val agentsBar =
        JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
            border = BorderFactory.createTitledBorder("Agents")
            isVisible = FrontendQuantaSettingsState.instance.state.agenticEnabled ?: true
        }


    private val usageLabel =
        JLabel("Tokens: 0").apply {
            toolTipText = "Total tokens used in current session (input + output)"
            font = font.deriveFont((font.size2D - 2f).coerceAtLeast(10f))
        }

    private val modeLabel =
        JLabel("").apply {
            toolTipText = ""
            font = font.deriveFont((font.size2D - 2f).coerceAtLeast(10f))
        }

    private val modelSelector =
        ComboBox(FrontendQuantaSettingsState.instance.state.availableChatModels.toTypedArray()).apply {
            isFocusable = false
            font = font.deriveFont((font.size2D - 2f).coerceAtLeast(10f))
            val models = FrontendQuantaSettingsState.instance.state.availableChatModels
            val current = FrontendQuantaSettingsState.instance.state.aiChatModel
            selectedItem = if (models.contains(current)) current else models.firstOrNull()
            toolTipText = "Current model for requests (or max model cap when dynamic switching is enabled)."
            addActionListener {
                val selected = (selectedItem as? String).orEmpty().trim()
                if (selected.isNotBlank()) {
                    FrontendQuantaSettingsState.instance.state.aiChatModel = selected
                    try {
                        project.service<ToolWindowService>().addToolingMessage("Model", "Selected: $selected")
                    } catch (_: Throwable) {
                    }
                }
            }
        }

    private val promptButtonPanel =
        JPanel().apply {
            val group =
                DefaultActionGroup().apply {
                    add(FollowToggleAction())
                    add(MicAction())
                    add(SpeakerAction())
                    add(AgenticModeToggleAction())
                    add(StopAgentsAction())
                }

            val toolbar: ActionToolbar = ActionManager.getInstance().createActionToolbar("MyToolbar", group, true)
            toolbar.targetComponent = this
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(toolbar.component)
            add(Box.createHorizontalStrut(8))
            add(modeLabel)
            add(Box.createHorizontalStrut(10))
            add(modelSelector)

            add(Box.createHorizontalGlue())
            add(usageLabel)
            add(Box.createHorizontalStrut(8))
            add(submitButton, BorderLayout.EAST)
        }

    private val promptPanel =
        JPanel().apply {
            layout = BorderLayout()
            border = BorderFactory.createTitledBorder("Enter your prompt:")
            add(JScrollPane(promptTextArea), BorderLayout.CENTER)
            add(promptButtonPanel, BorderLayout.SOUTH)
        }

    init {
        add(messageScrollPane, BorderLayout.CENTER)
        val bottom = JPanel(BorderLayout())
        bottom.add(agentsBar, BorderLayout.NORTH)
        bottom.add(promptPanel, BorderLayout.SOUTH)
        add(bottom, BorderLayout.SOUTH)

        // TODO: this shall be fixed with new ChatApp
        /*
        val agentService = project.service<AgentManagerService>()
        agentService.addPropertyChangeListener(
            PropertyChangeListener { evt ->
                when (evt.propertyName) {
                    "agents" -> refreshAgentsBar()
                    "agent_task_started" -> {
                        val data = evt.newValue as? Map<*, *> ?: return@PropertyChangeListener
                        val agentId = data["agentId"] as? String ?: return@PropertyChangeListener
                        val cnt = busyCounts.merge(agentId, 1) { a, _ -> (a ?: 0) + 1 } ?: 1
                        updateAgentIcon(agentId, cnt)
                    }

                    "agent_task_finished" -> {
                        val res = evt.newValue as? AgentManagerService.AgentTaskResult ?: return@PropertyChangeListener
                        val agentId = res.agentId
                        val current = busyCounts[agentId] ?: 0
                        val next = (current - 1).coerceAtLeast(0)
                        busyCounts[agentId] = next
                        updateAgentIcon(agentId, next)
                    }

                    "agents_stopped" -> {
                        busyCounts.keys.forEach { k -> busyCounts[k] = 0 }
                        refreshAgentsBar()
                    }

                    "agent_stopped" -> {
                        val id = evt.newValue as? String ?: return@PropertyChangeListener
                        busyCounts[id] = 0
                        updateAgentIcon(id, 0)
                    }
                }
            },
        )
*/
        refreshAgentsBar()
        refreshModeLabel()

        // Keep mode status reasonably fresh (plan.md can change during a session)
        Timer(2_000) {
            refreshModeLabel()
        }.apply {
            isRepeats = true
            start()
        }
    }

    private fun updateAgentIcon(
        agentId: String,
        count: Int,
    ) {
        // TODO: fix it in new ChatApp
//        val label = agentLabels[agentId]
//        if (label == null) {
//            refreshAgentsBar()
//            return
//        }
//        val icon = if (count > 0) AllIcons.CodeWithMe.CwmAccessOn else AllIcons.CodeWithMe.Users
//        ApplicationManager.getApplication().invokeLater {
//            label.icon = icon
//            label.repaint()
//        }
    }

    private fun refreshAgentsBar() {
        // Agent roster changes can be emitted from background threads; Swing must be updated on EDT.
        if (!ApplicationManager.getApplication().isDispatchThread) {
            // TODO: fix it in new ChatApp
//            if (agentsBarRefreshScheduled.compareAndSet(false, true)) {
//                ApplicationManager.getApplication().invokeLater {
//                    agentsBarRefreshScheduled.set(false)
//                    refreshAgentsBar()
//                }
//            }
            return
        }

        val agentic = FrontendQuantaSettingsState.instance.state.agenticEnabled ?: true
        agentsBar.isVisible = agentic

        if (!agentic) {
            agentsBar.removeAll()
            agentsBar.revalidate()
            agentsBar.repaint()
            return
        }
        agentsBar.removeAll()
        agentsBar.add(JLabel("Agentic mode enabled"))
        agentsBar.revalidate()
        agentsBar.repaint()
    }

    private fun refreshModeLabel() {
        try {
            // TODO fix it in new ChatApp
            /*
            val svc = SessionPlanService(project)

            val hasPlan = svc.hasPlan()
            val status = if (hasPlan) svc.getStatus() else ""

            val modeText =
                if (!hasPlan) {
                    ""
                } else {
                    when (status.trim().uppercase()) {
                        "ACTIVE" -> "Plan"
                        "DONE" -> "Done"
                        else -> "Draft"
                    }
                }

            val planText = if (hasPlan) svc.loadText(maxChars = 6_000) else ""
            val tip =
                if (planText.isBlank()) {
                    ""
                } else {
                    "<html><pre>${escapeHtml(planText)}</pre></html>"
                }

            ApplicationManager.getApplication().invokeLater {
                modeLabel.text = modeText
                modeLabel.toolTipText = tip
                modeLabel.repaint()
            }

       */
        } catch (_: Throwable) {
        }
    }

    private fun escapeHtml(s: String): String =
        s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    private fun submitPrompt(e: ActionEvent) {
        // TODO: this already exists in ChatApp
        val promptText = promptTextArea.text
        if (promptText.isNotEmpty()) {
            project.service<ToolWindowService>().addUserMessage(promptText)
            //    project.service<OpenAIService>().sendMessage(promptText) { }
            promptTextArea.text = ""
        }
    }

    private fun newPromptTextArea() =
        JTextArea().apply {
            lineWrap = true
            wrapStyleWord = true
            rows = 4
            actionMap.put(
                "submit",
                object : AbstractAction() {
                    override fun actionPerformed(e: ActionEvent) {
                        if (submitButton.icon != AllIcons.Actions.Suspend) {
                            submitButton.doClick()
                        }
                    }
                },
            )
            inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK), "insert-break")
            inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "submit")
        }
}
