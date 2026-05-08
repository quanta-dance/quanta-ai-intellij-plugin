package com.github.quanta_dance.quanta.plugins.intellij.frontend.settings

import com.github.quanta_dance.quanta.plugins.intellij.frontend.rpc.FrontendSettingsRpcService
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.*
import com.intellij.util.ui.FormBuilder
import kotlinx.coroutines.runBlocking
import java.awt.Color
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane

class FrontendQuantaPluginConfigurable : Configurable {
    private val settingsComponent: FrontendQuantaSettingsComponent by lazy(::FrontendQuantaSettingsComponent)

    override fun getDisplayName(): String = "QuantaDance Settings"

    override fun createComponent(): JComponent = settingsComponent.panel

    override fun isModified(): Boolean {
        val settings = FrontendQuantaSettingsState.instance.state
        return settingsComponent.run {
            hostValue != settings.openAiUrl ||
                    tokenValue != settings.openAiToken ||
                    voiceEnabled != settings.voiceEnabled ||
                    voiceByLocalTTS != settings.voiceByLocalTTS ||
                    maxTokensValue != settings.maxTokens ||
                    aiChatModelValue != settings.aiChatModel ||
                    dynamicModelEnabled != (settings.dynamicModelEnabled ?: false) ||
                    agenticEnabled != (settings.agenticEnabled ?: true) ||
                    debugEnabled != settings.debugEnabled ||
                    maxAutomaticTurns != settings.maxAutomaticTurns ||
                    terminalToolEnabled != (settings.terminalToolEnabled ?: false) ||
                    terminalAllowedCommandsCsv != settings.terminalAllowedCommandsCsv ||
                    extraInstructionsValue != (settings.extraInstructions ?: "") ||
                    actionConfigsValue != settings.actionConfigsJson
        }
    }

    override fun apply() {
        val settings = FrontendQuantaSettingsState.instance.state
        settingsComponent.run {
            settings.openAiUrl = hostValue
            settings.openAiToken = tokenValue
            settings.voiceEnabled = voiceEnabled
            settings.voiceByLocalTTS = voiceByLocalTTS
            settings.maxTokens = maxTokensValue
            settings.aiChatModel = aiChatModelValue
            settings.dynamicModelEnabled = dynamicModelEnabled
            settings.agenticEnabled = agenticEnabled
            settings.debugEnabled = debugEnabled
            settings.maxAutomaticTurns = maxAutomaticTurns
            settings.terminalToolEnabled = terminalToolEnabled
            settings.terminalAllowedCommandsCsv = terminalAllowedCommandsCsv
            settings.extraInstructions = extraInstructionsValue.ifBlank { null }
            settings.followEnabled = followEnabled
            settings.actionConfigsJson =
                actionConfigsValue.ifBlank { FrontendActionCatalog.encode(FrontendActionCatalog.defaultActions) }
        }
        syncSettingsToBackend(settings)
    }

    override fun reset() {
        val settings = FrontendQuantaSettingsState.instance.state
        settingsComponent.run {
            hostValue = settings.openAiUrl
            tokenValue = settings.openAiToken
            voiceEnabled = settings.voiceEnabled
            voiceByLocalTTS = settings.voiceByLocalTTS
            maxTokensValue = settings.maxTokens
            setAvailableModels(settings.availableChatModels)
            aiChatModelValue = settings.aiChatModel
            dynamicModelEnabled = settings.dynamicModelEnabled ?: false
            agenticEnabled = settings.agenticEnabled ?: true
            debugEnabled = settings.debugEnabled
            maxAutomaticTurns = settings.maxAutomaticTurns
            terminalToolEnabled = settings.terminalToolEnabled ?: false
            terminalAllowedCommandsCsv = settings.terminalAllowedCommandsCsv
            extraInstructionsValue = settings.extraInstructions ?: ""
            actionConfigsValue = settings.actionConfigsJson
            followEnabled = settings.followEnabled
        }
    }

    private fun syncSettingsToBackend(settings: FrontendQuantaSettingsState.State) {
        val project = ProjectManager.getInstance().openProjects.firstOrNull() ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching {
                val rpc = FrontendSettingsRpcService.getInstance(project)
                runBlocking { rpc.updateSettings(settings.toDto()) }
            }
        }
    }
}

private class FrontendQuantaSettingsComponent {
    private val hostField = JBTextField().apply {
        emptyText.text = FrontendQuantaSettingsState.DEFAULT_OPENAI_URL
        toolTipText = "Default host is ${FrontendQuantaSettingsState.DEFAULT_OPENAI_URL}"
    }
    private val tokenField = JBPasswordField().apply {
        columns = 30
        toolTipText = "JWT token for authentication"
    }
    private val voiceEnabledField = JBCheckBox("Voice enabled")
    private val voiceByLocalTTSField = JBCheckBox("Use Local TTS")
    private val maxOutputTokensField = JBTextField()
    private val modelField = ComboBox<String>()
    private val dynamicModelEnabledField = JBCheckBox("Enable dynamic model switching")
    private val agenticEnabledField = JBCheckBox("Enable agentic mode")
    private val debugEnabledField = JBCheckBox("Enable debug messages in tool window")
    private val maxAutomaticTurnsField = JBTextField().apply {
        toolTipText = "Maximum automatic CONTINUE turns per user request (1..100)."
    }
    private val followEnabledField = JBCheckBox("Follow enabled")
    private val terminalToolEnabledField = JBCheckBox("Enable Terminal tool (dangerous)")
    private val terminalAllowedCommandsCsvField = JBTextField().apply {
        toolTipText = "Comma-separated allowed command prefixes (strict token-prefix match)."
    }
    private val extraInstructionsArea = JBTextArea(8, 60).apply {
        lineWrap = true
        wrapStyleWord = true
        toolTipText = "These lines will be appended to the system instructions for every request."
    }
    private val extraInstructionsScroll = JScrollPane(extraInstructionsArea)
    private val actionConfigsArea = JBTextArea(10, 60).apply {
        lineWrap = true
        wrapStyleWord = true
        toolTipText = "JSON array of actions. Each item needs id, label, and instruction. Label max is 20 chars."
    }
    private val actionConfigsScroll = JScrollPane(actionConfigsArea)

    private val actionEditorButton = JButton("Edit Actions…").apply {
        toolTipText = "Open the action list editor"
        addActionListener {
            val dialog = FrontendActionEditorDialog(FrontendQuantaSettingsState.instance.state.actionConfigsJson)
            if (dialog.showAndGet()) {
                actionConfigsValue = dialog.getActionsJson()
            }
        }
    }

    private val editMcpButton = JButton("Edit MCP Servers…").apply {
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
                    file.writeText("{\n  \"mcpServers\": { }\n}")
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

    private val linkLabel = JBLabel(
        "<html>Model Pricing details available at <a href=\"https://platform.openai.com/docs/pricing\">https://platform.openai.com/docs/pricing</a></html>",
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
            .addLabeledComponent(JBLabel("AI chat model: "), modelField, 1, false)
            .addComponent(dynamicModelEnabledField)
            .addComponent(agenticEnabledField)
            .addComponent(debugEnabledField)
            .addLabeledComponent(JBLabel("Max automatic turns: "), maxAutomaticTurnsField, 1, false)
            .addComponent(terminalToolEnabledField)
            .addLabeledComponent(JBLabel("Terminal allowed commands: "), terminalAllowedCommandsCsvField, 1, false)
            .addSeparator()
            .addLabeledComponent(JBLabel("Custom instructions: "), extraInstructionsScroll, 1, false)
            .addComponent(actionEditorButton)
            .addComponent(editMcpButton)
            .addComponent(linkLabel)
            .addComponentFillVertically(JPanel(), 0)
            .panel

    var hostValue: String
        get() = hostField.text.trim().ifBlank { FrontendQuantaSettingsState.DEFAULT_OPENAI_URL }
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

    fun setAvailableModels(models: List<String>) {
        val selectedBefore = modelField.selectedItem as? String
        modelField.removeAllItems()
        val values =
            if (models.isNotEmpty()) {
                models
            } else {
                listOf(FrontendQuantaSettingsState.DEFAULT_MODEL)
            }
        values.forEach(modelField::addItem)
        modelField.selectedItem =
            when {
                selectedBefore != null && values.contains(selectedBefore) -> selectedBefore
                values.isNotEmpty() -> values.first()
                else -> null
            }
    }

    var aiChatModelValue: String
        get() = (modelField.selectedItem as? String).orEmpty()
        set(value) {
            if ((0 until modelField.itemCount).none { modelField.getItemAt(it) == value }) {
                modelField.addItem(value)
            }
            modelField.selectedItem = value
        }

    var extraInstructionsValue: String
        get() = extraInstructionsArea.text
        set(value) {
            extraInstructionsArea.text = value
        }

    var actionConfigsValue: String
        get() = actionConfigsArea.text
        set(value) {
            actionConfigsArea.text = value
        }

    var maxTokensValue: Long?
        get() = maxOutputTokensField.text.toLongOrNull()
        set(value) {
            maxOutputTokensField.text = value.toString()
        }

    var maxAutomaticTurns: Int
        get() = maxAutomaticTurnsField.text.toIntOrNull()?.coerceIn(1, 100) ?: 10
        set(value) {
            maxAutomaticTurnsField.text = value.coerceIn(1, 100).toString()
        }

    var terminalAllowedCommandsCsv: String
        get() = terminalAllowedCommandsCsvField.text
        set(value) {
            terminalAllowedCommandsCsvField.text = value
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

    var debugEnabled: Boolean
        get() = debugEnabledField.isSelected
        set(value) {
            debugEnabledField.isSelected = value
        }

    var terminalToolEnabled: Boolean
        get() = terminalToolEnabledField.isSelected
        set(value) {
            terminalToolEnabledField.isSelected = value
        }

    var followEnabled: Boolean
        get() = followEnabledField.isSelected
        set(value) {
            followEnabledField.isSelected = value
        }
}
