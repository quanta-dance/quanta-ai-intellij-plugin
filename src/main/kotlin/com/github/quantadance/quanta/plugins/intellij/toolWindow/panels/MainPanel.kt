// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quantadance.quanta.plugins.intellij.toolWindow.panels

import com.github.quantadance.quanta.plugins.intellij.services.OpenAIService
import com.github.quantadance.quanta.plugins.intellij.services.ToolWindowService
import com.github.quantadance.quanta.plugins.intellij.toolWindow.actions.MicAction
import com.github.quantadance.quanta.plugins.intellij.toolWindow.actions.SpeakerAction
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Component
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
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

    private val promptButtonPanel =
        JPanel().apply {
            val group =
                DefaultActionGroup().apply {
                    add(MicAction())
                    add(SpeakerAction())
                }

            val toolbar: ActionToolbar =
                ActionManager.getInstance()
                    .createActionToolbar("MyToolbar", group, true)
            toolbar.targetComponent = this
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(toolbar.component)
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
        add(promptPanel, BorderLayout.SOUTH)
    }

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
