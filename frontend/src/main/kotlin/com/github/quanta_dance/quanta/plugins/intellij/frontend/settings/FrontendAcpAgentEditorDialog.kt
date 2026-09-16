// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.frontend.settings

import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.AcpManualAgentDto
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/** Edits explicit ACP discovery targets without relying on an error-prone text format. */
internal class FrontendAcpAgentEditorDialog(
    agents: List<AcpManualAgentDto>,
) : DialogWrapper(true) {
    private val listModel = DefaultListModel<AcpManualAgentDto>()
    private val list =
        JBList(listModel).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = AcpAgentListRenderer()
        }

    init {
        agents.forEach(listModel::addElement)
        title = "Configure ACP Agents"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val content = JPanel(BorderLayout())
        val decorator =
            ToolbarDecorator
                .createDecorator(list)
                .setAddAction {
                    AgentEditDialog().showAndGetAgent()?.let { addAgent(it) }
                }.setRemoveAction {
                    val index = list.selectedIndex
                    if (index >= 0) listModel.remove(index)
                }.setEditAction {
                    val index = list.selectedIndex
                    if (index >= 0) {
                        AgentEditDialog(listModel.getElementAt(index)).showAndGetAgent()?.let { updateAgent(index, it) }
                    }
                }.disableUpDownActions()
                .createPanel()
        content.add(decorator, BorderLayout.CENTER)
        content.add(
            JBLabel(
                "Configured entries are probed only when ACP discovery is requested; only a successful ACP handshake makes an agent available.",
            ),
            BorderLayout.SOUTH,
        )
        return content
    }

    fun getAgents(): List<AcpManualAgentDto> = (0 until listModel.size()).map(listModel::getElementAt)

    private fun addAgent(agent: AcpManualAgentDto) {
        if (hasDuplicate(agent)) {
            showDuplicateError(agent)
            return
        }
        listModel.addElement(agent)
    }

    private fun updateAgent(
        index: Int,
        agent: AcpManualAgentDto,
    ) {
        if (hasDuplicate(agent, ignoredIndex = index)) {
            showDuplicateError(agent)
            return
        }
        listModel.set(index, agent)
    }

    private fun hasDuplicate(
        candidate: AcpManualAgentDto,
        ignoredIndex: Int = -1,
    ): Boolean =
        (0 until listModel.size()).any { index ->
            index != ignoredIndex && normalizedTarget(listModel.getElementAt(index)) == normalizedTarget(candidate)
        }

    private fun normalizedTarget(agent: AcpManualAgentDto): String =
        agent.executable?.trim()?.lowercase() ?: "${agent.host?.trim()?.lowercase()}:${agent.port}"

    private fun showDuplicateError(agent: AcpManualAgentDto) {
        Messages.showErrorDialog(
            contentPanel,
            "The ${targetDescription(
                agent,
            )} target is already configured. Each application path or TCP host and port can be added only once.",
            title,
        )
    }

    private fun targetDescription(agent: AcpManualAgentDto): String = agent.executable ?: "${agent.host}:${agent.port}"

    private class AgentEditDialog(
        private val initial: AcpManualAgentDto? = null,
    ) : DialogWrapper(true) {
        private val nameField = JBTextField(initial?.name.orEmpty())
        private val executableField = JBTextField(initial?.executable.orEmpty())
        private val hostField = JBTextField(initial?.host.orEmpty())
        private val portField = JBTextField(initial?.port?.toString().orEmpty())
        private val applicationTypeField = javax.swing.JRadioButton("Local application", initial?.host == null)
        private val tcpTypeField = javax.swing.JRadioButton("TCP endpoint", initial?.host != null)

        init {
            javax.swing.ButtonGroup().apply {
                add(applicationTypeField)
                add(tcpTypeField)
            }
            applicationTypeField.addActionListener { updateTransportFields() }
            tcpTypeField.addActionListener { updateTransportFields() }
            title = if (initial == null) "Add ACP Agent" else "Edit ACP Agent"
            init()
            updateTransportFields()
        }

        override fun createCenterPanel(): JComponent =
            panel {
                row("Name") {
                    cell(nameField)
                        .align(AlignX.FILL)
                        .comment("A clear label shown to AI when this agent passes discovery.")
                }
                buttonsGroup {
                    row { cell(applicationTypeField) }
                    row { cell(tcpTypeField) }
                }
                row("Executable path") {
                    cell(executableField)
                        .align(AlignX.FILL)
                        .comment("Executable path or command to launch directly, without a shell.")
                }
                row("Host") {
                    cell(hostField)
                        .align(AlignX.FILL)
                        .comment("Hostname, IPv4 address, or IPv6 address without a URL scheme.")
                }
                row("Port") {
                    cell(portField)
                        .align(AlignX.FILL)
                        .comment("TCP port from 1 to 65535. The endpoint uses newline-delimited ACP JSON-RPC.")
                }
            }

        fun showAndGetAgent(): AcpManualAgentDto? = if (showAndGet()) createAgent() else null

        override fun doValidate(): ValidationInfo? =
            runCatching(::createAgent)
                .exceptionOrNull()
                ?.let { error -> ValidationInfo(error.message ?: "Invalid ACP agent configuration.") }

        private fun updateTransportFields() {
            val application = applicationTypeField.isSelected
            executableField.isEnabled = application
            hostField.isEnabled = !application
            portField.isEnabled = !application
        }

        private fun createAgent(): AcpManualAgentDto {
            val name = nameField.text.trim()
            require(name.isNotEmpty()) { "ACP agent name is required." }
            require(name.length <= MAX_NAME_LENGTH) { "ACP agent name must be $MAX_NAME_LENGTH characters or fewer." }
            require(name.none { it.isISOControl() }) { "ACP agent name cannot contain control characters." }

            if (applicationTypeField.isSelected) {
                val executable = executableField.text.trim()
                require(executable.isNotEmpty()) { "An executable path or command is required for a local ACP application." }
                require(!executable.contains('\n') && !executable.contains('\r')) { "Executable path must be a single line." }
                return AcpManualAgentDto(name = name, executable = executable)
            }

            val host = hostField.text.trim()
            val port = portField.text.trim().toIntOrNull()
            require(host.isNotEmpty()) { "A TCP host is required." }
            require(host.all { it.isLetterOrDigit() || it in ".-:" }) {
                "TCP host must be a hostname, IPv4 address, or IPv6 address without a URL scheme, path, or port."
            }
            require(port in 1..65_535) { "TCP port must be a whole number from 1 to 65535." }
            return AcpManualAgentDto(name = name, host = host, port = port)
        }

        private companion object {
            const val MAX_NAME_LENGTH = 80
        }
    }

    private class AcpAgentListRenderer : javax.swing.ListCellRenderer<AcpManualAgentDto> {
        private val panel = JPanel(BorderLayout())
        private val name = JBLabel()
        private val target = JBLabel()

        override fun getListCellRendererComponent(
            list: javax.swing.JList<out AcpManualAgentDto>,
            value: AcpManualAgentDto,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            val localApplication = value.executable != null
            name.text = "${if (localApplication) "Application" else "TCP"}: ${value.name}"
            target.text = value.executable ?: "${value.host}:${value.port}"
            panel.removeAll()
            panel.add(name, BorderLayout.NORTH)
            panel.add(target, BorderLayout.SOUTH)
            panel.background = if (isSelected) list.selectionBackground else list.background
            name.foreground = if (isSelected) list.selectionForeground else list.foreground
            target.foreground = name.foreground
            return panel
        }
    }
}
