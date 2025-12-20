// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.settings

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.openai.models.AllModels
import com.openai.models.ChatModel
import com.openai.models.ResponsesModel
import java.awt.Color
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JScrollPane

class QuantaAISettingsComponent {
    private var hostField =
        JBTextField().apply {
            this.emptyText.text = QuantaAISettingsState.DEFAULT_HOST
            this.toolTipText = "Default host is ${QuantaAISettingsState.DEFAULT_HOST}"
        }
    private var tokenField =
        JBPasswordField().apply {
            columns = 30
            toolTipText = "JWT token for authentication"
        }
    private var voiceEnabledField =
        JBCheckBox("Voice enabled").apply {
            this.toolTipText = "AI will process messages with voice. Require tokens for transcription"
        }
    private var voiceByLocalTTSField =
        JBCheckBox("Use Local TTS").apply {
            this.toolTipText = "Use local TTS. Will save OpenAI TTS Tokens"
        }

    private var maxOutputTokensField = JBTextField("Max output tokens")

    private var models: Array<String> =
        arrayOf(
            // ChatModel.GPT_5_2_PRO.toString(),
            ChatModel.GPT_5_2.toString(),
            AllModels.ResponsesOnlyModel.GPT_5_1_CODEX_MAX.toString(),
            ChatModel.GPT_5_1_CODEX.toString(),
            ChatModel.GPT_5_1.toString(),
            ChatModel.GPT_5.toString(),
            ChatModel.GPT_5_MINI.toString(),
            ChatModel.GPT_5_NANO.toString(),
        )

    private var aiChatModelField = ComboBox(models)

    private var dynamicModelEnabledField =
        JBCheckBox("Enable dynamic model switching").apply {
            toolTipText = "Allow the assistant to switch model tier within the configured cap."
        }

    // Agentic mode toggle
    private var agenticEnabledField =
        JBCheckBox("Enable agentic mode").apply {
            toolTipText = "Allow the manager to create role-based sub-agents and use agent tools."
        }

    // Terminal tool toggle (dangerous)
    private var terminalToolEnabledField =
        JBCheckBox("Enable Terminal tool (dangerous)").apply {
            toolTipText = "Allows the assistant to run shell commands. Disabled by default."
        }

    private var customPromptField = JBTextField()

    private var extraInstructionsArea =
        JBTextArea(8, 60).apply {
            lineWrap = true
            wrapStyleWord = true
            toolTipText = "These lines will be appended to the system instructions for every request."
        }
    private var extraInstructionsScroll = JScrollPane(extraInstructionsArea)

    private val editMcpButton =
        JButton("Edit MCP Servers…").apply {
            toolTipText = "Open or create .quantadance/mcp-servers.json in the current project"
            addActionListener {
                val project: Project? = ProjectManager.getInstance().openProjects.firstOrNull()
                if (project == null) {
                    Messages.showWarningDialog(
                        "No open project found. Open a project to edit its MCP servers file.",
                        "QuantaDance",
                    )
                    return@addActionListener
                }
                val basePath = project.basePath
                if (basePath == null) {
                    Messages.showWarningDialog(project, "Project base path is unavailable.", "QuantaDance")
                    return@addActionListener
                }
                val file = File(basePath, ".quantadance/mcp-servers.json")
                try {
                    if (!file.parentFile.exists()) file.parentFile.mkdirs()
                    if (!file.exists()) {
                        file.writeText(
                            """
                            {
                            """.trimIndent() +
                                    "\"mcpServers\": { }\n" +
                                    "}".trimIndent(),
                        )
                    }
                    LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)?.let { vFile ->
                        ApplicationManager.getApplication().invokeLater {
                            FileEditorManager.getInstance(project).openFile(vFile, true)
                        }
                    } ?: run {
                        Messages.showErrorDialog(project, "Failed to open mcp-servers.json in editor.", "QuantaDance")
                    }
                } catch (e: Exception) {
                    Messages.showErrorDialog(
                        project,
                        "Failed to create or open mcp-servers.json: ${e.message}",
                        "QuantaDance",
                    )
                }
            }
        }

    val linkLabel =
        JBLabel(
            "<html>Model Pricing details available at <a href=\"https://platform.openai.com/docs/pricing\">" +
                    "https://platform.openai.com/docs/pricing</a></html>",
        ).apply {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            foreground = Color(42, 122, 255)
            addMouseListener(
                object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent?) {
                        BrowserUtil.browse("https://platform.openai.com/docs/pricing")
                    }
                },
            )
        }

    val panel: JPanel =
        FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Host: "), hostField, 1, false)
            .addLabeledComponent(JBLabel("Token: "), tokenField, 1, false)
            .addSeparator()
            .addComponent(voiceEnabledField)
            .addComponent(voiceByLocalTTSField)
            .addSeparator()
            .addLabeledComponent(JBLabel("Max output tokens: "), maxOutputTokensField, 1, false)
            .addSeparator()
            .addLabeledComponent(JBLabel("AI chat model: "), aiChatModelField, 1, false)
            .addComponent(dynamicModelEnabledField)
            .addComponent(agenticEnabledField)
            .addComponent(terminalToolEnabledField)
            .addSeparator()
            .addLabeledComponent(JBLabel("Custom prompt: "), customPromptField, 1, false)
            .addLabeledComponent(JBLabel("Custom instructions: "), extraInstructionsScroll, 1, false)
            .addComponent(editMcpButton)
            .addComponent(linkLabel)
            .addComponentFillVertically(JPanel(), 0)
            .panel

    var hostValue: String
        get() = hostField.text.trim().ifBlank { QuantaAISettingsState.DEFAULT_HOST }
        set(value) {
            hostField.text = value
        }

    var tokenValue: String
        get() = String(tokenField.password)
        set(value) {
            tokenField.text = value
        }

    var voiceEnabled: Boolean
        get() = voiceEnabledField.isSelected
        set(value) {
            voiceEnabledField.isSelected = value
        }

    var voiceByLocalTTS: Boolean
        get() = voiceByLocalTTSField.isSelected
        set(value) {
            voiceByLocalTTSField.isSelected = value
        }

    var aiChatModelValue: String
        get() = aiChatModelField.selectedItem as String
        set(value) {
            aiChatModelField.selectedItem = value
        }

    var customPromptValue: String
        get() = customPromptField.text
        set(value) {
            customPromptField.text = value
        }

    var extraInstructionsValue: String
        get() = extraInstructionsArea.text
        set(value) {
            extraInstructionsArea.text = value
        }

    var maxTokensValue: Long?
        get() =
            try {
                Integer.parseInt(maxOutputTokensField.text).toLong()
            } catch (e: NumberFormatException) {
                null
            }
        set(value) {
            maxOutputTokensField.text = value.toString()
        }

    var dynamicModelEnabled: Boolean
        get() = dynamicModelEnabledField.isSelected
        set(value) {
            dynamicModelEnabledField.isSelected = value
        }

    var agenticEnabled: Boolean
        get() = agenticEnabledField.isSelected
        set(value) {
            agenticEnabledField.isSelected = value
        }

    var terminalToolEnabled: Boolean
        get() = terminalToolEnabledField.isSelected
        set(value) {
            terminalToolEnabledField.isSelected = value
        }
}
