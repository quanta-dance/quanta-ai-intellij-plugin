// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.toolWindow.panels

import com.github.quanta_dance.quanta.plugins.intellij.services.AgentManagerService
import com.github.quanta_dance.quanta.plugins.intellij.services.OpenAIService
import com.github.quanta_dance.quanta.plugins.intellij.services.ToolWindowService
import com.github.quanta_dance.quanta.plugins.intellij.settings.QuantaAISettingsListener
import com.github.quanta_dance.quanta.plugins.intellij.settings.QuantaAISettingsState
import com.github.quanta_dance.quanta.plugins.intellij.toolWindow.actions.MicAction
import com.github.quanta_dance.quanta.plugins.intellij.toolWindow.actions.SpeakerAction
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.beans.PropertyChangeListener
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

    private val agenticToggle =
        JBCheckBox("Agentic").apply {
            val state = QuantaAISettingsState.instance.state
            isSelected = state.agenticEnabled ?: true
            toolTipText = "Enable/Disable agentic mode (manager can spawn sub-agents)"
            addActionListener {
                val s = QuantaAISettingsState.instance.state
                s.agenticEnabled = isSelected
                val snapshot = s.copy()
                ApplicationManager.getApplication().messageBus.syncPublisher(QuantaAISettingsListener.TOPIC).onSettingsChanged(snapshot)
                refreshAgentsBar()
            }
        }

    private val agentsBar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
        border = BorderFactory.createTitledBorder("Agents")
        isVisible = QuantaAISettingsState.instance.state.agenticEnabled ?: true
    }

    private val promptButtonPanel =
        JPanel().apply {
            val group = DefaultActionGroup().apply { add(MicAction()); add(SpeakerAction()) }
            val toolbar: ActionToolbar = ActionManager.getInstance().createActionToolbar("MyToolbar", group, true)
            toolbar.targetComponent = this
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(toolbar.component)
            add(Box.createHorizontalStrut(8))
            add(JBLabel("Mode:"))
            add(Box.createHorizontalStrut(4))
            add(agenticToggle)
            add(Box.createHorizontalGlue())
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

        project.service<AgentManagerService>().addPropertyChangeListener(PropertyChangeListener { evt ->
            if (evt.propertyName == "agents") refreshAgentsBar()
        })
        project.messageBus.connect().subscribe(
            QuantaAISettingsListener.TOPIC,
            object : QuantaAISettingsListener {
                override fun onSettingsChanged(newState: QuantaAISettingsState.QuantaAIState) {
                    agentsBar.isVisible = newState.agenticEnabled ?: true
                    refreshAgentsBar()
                }
            },
        )
        refreshAgentsBar()
    }

    private fun refreshAgentsBar() {
        val agentic = QuantaAISettingsState.instance.state.agenticEnabled ?: true
        agentsBar.isVisible = agentic
        if (!agentic) {
            agentsBar.removeAll()
            agentsBar.revalidate(); agentsBar.repaint()
            return
        }
        val manager = project.service<AgentManagerService>()
        val agents = manager.getAgentsSnapshot()
        agentsBar.removeAll()
        agents.forEach { a ->
            val label = JLabel()
            label.icon = AllIcons.CodeWithMe.Users
            label.text = a.role
            label.iconTextGap = 4
            label.toolTipText = buildTooltip(a.role, a.model, a.instructions)
            agentsBar.add(label)
        }
        agentsBar.revalidate(); agentsBar.repaint()
    }

    private fun buildTooltip(role: String, model: String?, instructions: String?): String {
        val safeInstr = (instructions ?: "").trim()
        val safeModel = (model ?: "").trim()
        val html = StringBuilder("<html>")
        html.append("<b>").append(role).append("</b>")
        if (safeModel.isNotEmpty()) {
            html.append(" &nbsp; <i>(").append(escapeHtml(safeModel)).append(")</i>")
        }
        if (safeInstr.isNotEmpty()) {
            html.append("<br/>")
            html.append(escapeHtml(safeInstr).replace("\n", "<br/>"))
        }
        html.append("</html>")
        return html.toString()
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

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
            actionMap.put("submit", object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) {
                    if (submitButton.icon != AllIcons.Actions.Suspend) {
                        submitButton.doClick()
                    }
                }
            })
            inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK), "insert-break")
            inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "submit")
        }
}
