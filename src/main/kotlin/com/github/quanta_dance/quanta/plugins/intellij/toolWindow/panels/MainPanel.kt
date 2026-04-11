// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.toolWindow.panels

import com.github.quanta_dance.quanta.plugins.intellij.services.AgentManagerService
import com.github.quanta_dance.quanta.plugins.intellij.services.OpenAIService
import com.github.quanta_dance.quanta.plugins.intellij.services.SessionPlanService
import com.github.quanta_dance.quanta.plugins.intellij.services.ToolWindowService
import com.github.quanta_dance.quanta.plugins.intellij.frontend.settings.FrontendQuantaSettingsListener
import com.github.quanta_dance.quanta.plugins.intellij.frontend.settings.FrontendQuantaSettingsState
import com.github.quanta_dance.quanta.plugins.intellij.toolWindow.actions.AgenticModeToggleAction
import com.github.quanta_dance.quanta.plugins.intellij.toolWindow.actions.FollowToggleAction
import com.github.quanta_dance.quanta.plugins.intellij.toolWindow.actions.MicAction
import com.github.quanta_dance.quanta.plugins.intellij.toolWindow.actions.SpeakerAction
import com.github.quanta_dance.quanta.plugins.intellij.toolWindow.actions.StopAgentsAction
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBScrollPane
import com.openai.models.ChatModel
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.beans.PropertyChangeListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.KeyStroke
import javax.swing.Timer
import javax.swing.UIManager

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
            addActionListener { e ->
                project.service<OpenAIService>().let { service ->
                    service.addPropertyChangeListener { evt ->
                        this.setIcon(if (evt.newValue == true) AllIcons.Actions.Suspend else AllIcons.Actions.RunAll)
                    }
                    if (this.icon == AllIcons.Actions.Suspend) {
                        service.stopProcessing()
                    }
                }
                submitPrompt(e)
            }
        }

    private val agentsBar =
        JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
            border = BorderFactory.createTitledBorder("Agents")
            isVisible = QuantaAISettingsState.instance.state.agenticEnabled ?: true
        }

    private val agentLabels = ConcurrentHashMap<String, JLabel>()
    private val busyCounts = ConcurrentHashMap<String, Int>()
    private val agentsBarRefreshScheduled = AtomicBoolean(false)

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

    private val models =
        arrayOf(
            ChatModel.GPT_5_4.toString(),
            ChatModel.GPT_5_4_MINI.toString(),
            ChatModel.GPT_5_4_NANO.toString(),
            ChatModel.GPT_5_2.toString(),
            ChatModel.GPT_5_1_CODEX.toString(),
            ChatModel.GPT_5_1.toString(),
            ChatModel.GPT_5.toString(),
            ChatModel.GPT_5_MINI.toString(),
            ChatModel.GPT_5_NANO.toString(),
        )

    private val modelSelector =
        ComboBox(models).apply {
            isFocusable = false
            font = font.deriveFont((font.size2D - 2f).coerceAtLeast(10f))
            val current = QuantaAISettingsState.instance.state.aiChatModel
            selectedItem = if (models.contains(current)) current else models.last()
            toolTipText = "Current model for requests (or max model cap when dynamic switching is enabled)."
            addActionListener {
                val selected = (selectedItem as? String).orEmpty().trim()
                if (selected.isNotBlank()) {
                    QuantaAISettingsState.instance.state.aiChatModel = selected
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

        // Usage total label
        try {
            val svc = project.service<OpenAIService>()
            val snap = svc.getUsageSnapshot()
            usageLabel.text = "Tokens: ${snap.totalTokens}"
            usageLabel.toolTipText =
                "Input: ${snap.inputTokens} | Output: ${snap.outputTokens} | Total: ${snap.totalTokens}"
            svc.addPropertyChangeListener(
                PropertyChangeListener { evt ->
                    if (evt.propertyName == "usage") {
                        val s = evt.newValue as? OpenAIService.UsageSnapshot ?: return@PropertyChangeListener
                        ApplicationManager.getApplication().invokeLater {
                            usageLabel.text = "Tokens: ${s.totalTokens}"
                            usageLabel.toolTipText =
                                "Input: ${s.inputTokens} | Output: ${s.outputTokens} | Total: ${s.totalTokens}"
                        }
                    }
                },
            )
        } catch (_: Throwable) {
        }

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

        project.messageBus.connect().subscribe(
            QuantaAISettingsListener.TOPIC,
            object : QuantaAISettingsListener {
                override fun onSettingsChanged(newState: QuantaAISettingsState.QuantaAIState) {
                    agentsBar.isVisible = newState.agenticEnabled ?: true
                    if (!newState.debugEnabled) {
                        try {
                            project.service<ToolWindowService>().clearDebugMessages()
                        } catch (_: Throwable) {
                        }
                    }

                    // Sync UI widgets
                    try {
                        val current = newState.aiChatModel
                        if (current.isNotBlank() && models.contains(current)) {
                            modelSelector.selectedItem = current
                        }
                    } catch (_: Throwable) {
                    }
                    refreshModeLabel()

                    refreshAgentsBar()
                }
            },
        )
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
        val label = agentLabels[agentId]
        if (label == null) {
            refreshAgentsBar()
            return
        }
        val icon = if (count > 0) AllIcons.CodeWithMe.CwmAccessOn else AllIcons.CodeWithMe.Users
        ApplicationManager.getApplication().invokeLater {
            label.icon = icon
            label.repaint()
        }
    }

    private fun refreshAgentsBar() {
        // Agent roster changes can be emitted from background threads; Swing must be updated on EDT.
        if (!ApplicationManager.getApplication().isDispatchThread) {
            if (agentsBarRefreshScheduled.compareAndSet(false, true)) {
                ApplicationManager.getApplication().invokeLater {
                    agentsBarRefreshScheduled.set(false)
                    refreshAgentsBar()
                }
            }
            return
        }

        val agentic = QuantaAISettingsState.instance.state.agenticEnabled ?: true
        agentsBar.isVisible = agentic

        if (!agentic) {
            agentsBar.removeAll()
            agentsBar.revalidate()
            agentsBar.repaint()
            agentLabels.clear()
            busyCounts.clear()
            return
        }
        val manager = project.service<AgentManagerService>()
        val agents = manager.getAgentsSnapshot()
        agentsBar.removeAll()
        agentLabels.clear()

        if (agents.isEmpty()) {
            val btn =
                JButton("Create default team").apply {
                    isFocusable = false
                    toolTipText = "Create Developer Agent, Test Agent, and Project Analyst"
                    addActionListener {
                        isEnabled = false
                        ApplicationManager.getApplication().executeOnPooledThread {
                            try {
                                manager.createDefaultTeam()
                            } finally {
                                ApplicationManager.getApplication().invokeLater {
                                    isEnabled = true
                                    refreshAgentsBar()
                                }
                            }
                        }
                    }
                }
            agentsBar.add(btn)
            agentsBar.revalidate()
            agentsBar.repaint()
            return
        }

        agents.forEach { a ->
            val label = JLabel()
            val currentBusy = busyCounts[a.id] ?: 0
            label.icon = if (currentBusy > 0) AllIcons.CodeWithMe.CwmAccessOn else AllIcons.CodeWithMe.Users
            label.text = a.role
            label.iconTextGap = 4
            label.toolTipText = buildTooltip(a.role, a.model, a.instructions)
            label.addMouseListener(
                object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        if (e.clickCount >= 2 && e.button == MouseEvent.BUTTON1) {
                            project.service<AgentManagerService>().stopAgent(a.id)
                        }
                    }
                },
            )
            agentsBar.add(label)
            agentLabels[a.id] = label
        }
        agentsBar.revalidate()
        agentsBar.repaint()
    }

    private fun buildTooltip(
        role: String,
        model: String?,
        instructions: String?,
    ): String {
        val safeInstr = (instructions ?: "").trim()
        val safeModel = (model ?: "").trim()

        val header =
            buildString {
                append("<b>").append(escapeHtml(role)).append("</b>")
                if (safeModel.isNotEmpty()) {
                    append(" &nbsp; <i>(").append(escapeHtml(safeModel)).append(")</i>")
                }
            }

        if (safeInstr.isBlank()) return "<html>$header</html>"

        // Make tooltip wider and more readable (similar feel to the plan tooltip).
        // 'width' is a hint used by Swing HTML renderer for wrapping.
        val body = "<pre>${escapeHtml(safeInstr)}</pre>"
        return "<html><div style='width:520px;'>$header<br/>$body</div></html>"
    }

    private fun refreshModeLabel() {
        try {
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
        val promptText = promptTextArea.text
        if (promptText.isNotEmpty()) {
            project.service<ToolWindowService>().addUserMessage(promptText)
            project.service<OpenAIService>().sendMessage(promptText) { }
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
