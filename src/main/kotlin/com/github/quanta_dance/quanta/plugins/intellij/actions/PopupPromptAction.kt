package com.github.quanta_dance.quanta.plugins.intellij.actions

import com.github.quanta_dance.quanta.plugins.intellij.services.OpenAIService
import com.github.quanta_dance.quanta.plugins.intellij.services.QDLog
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBScrollPane
import java.awt.Component.LEFT_ALIGNMENT
import java.awt.Component.TOP_ALIGNMENT
import java.awt.Dimension
import javax.swing.*

class PopupPromptAction : AnAction() {

    private var isRecording = false
    companion object { private val logger = Logger.getInstance(PopupPromptAction::class.java) }

    fun showInputPopup() { /* reserved for future */ }

    override fun actionPerformed(event: AnActionEvent) {
        val file: VirtualFile? = event.getData(CommonDataKeys.VIRTUAL_FILE)
        val project: Project? = event.project
        val editor = event.getData(CommonDataKeys.EDITOR)
        val inputField = JTextField(20)
        val panel = JTextField().apply {
            columns = 50
            preferredSize = Dimension(500, 40)
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = LEFT_ALIGNMENT
            alignmentY = TOP_ALIGNMENT
            add(inputField)
        }
        val scrollPane = JBScrollPane(panel).apply {
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        }
        val buttonOk = JButton("OK")
        val buttonCancel = JButton("Cancel")
        val buttonPanel = JPanel().apply {
            add(buttonOk)
            add(buttonCancel)
        }
        val mainPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(scrollPane)
            add(Box.createVerticalStrut(10))
            add(buttonPanel)
        }
        val popup: JBPopup = JBPopupFactory.getInstance().createComponentPopupBuilder(mainPanel, inputField)
            .setTitle("Enter Prompt to Quanta AI")
            .setRequestFocus(true)
            .setMovable(true)
            .setResizable(true)
            .setCancelOnClickOutside(true)
            .createPopup()
        popup.showInFocusCenter()
        buttonOk.addActionListener {
            val enteredText = inputField.text
            popup.closeOk(null)
            if (enteredText.length > 5) {
                ApplicationManager.getApplication().executeOnPooledThread {
                    project?.service<OpenAIService>()?.sendMessage(enteredText)
                }
            }
        }
        buttonCancel.addActionListener {
            QDLog.info(logger) { "Popup action cancelled" }
            popup.cancel()
        }
    }

    override fun update(e: AnActionEvent) {}
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
