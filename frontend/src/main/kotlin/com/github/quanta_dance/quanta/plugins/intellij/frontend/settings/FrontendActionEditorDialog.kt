package com.github.quanta_dance.quanta.plugins.intellij.frontend.settings

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JPanel

class FrontendActionEditorDialog(actionsJson: String) : DialogWrapper(true) {
    private val listModel = DefaultListModel<FrontendActionCatalog.ActionConfig>()
    private val list = JBList(listModel).apply {
        cellRenderer = ActionConfigListRenderer()
    }

    init {
        list.selectionMode = javax.swing.ListSelectionModel.SINGLE_SELECTION
        FrontendActionCatalog.decode(actionsJson).forEach { listModel.addElement(it) }
        if (listModel.isEmpty) {
            FrontendActionCatalog.defaultActions.forEach { listModel.addElement(it) }
        }
        title = "Edit Actions"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val center = JPanel(BorderLayout())
        val decorator =
            ToolbarDecorator.createDecorator(list)
                .setAddAction {
                    listModel.addElement(
                        FrontendActionCatalog.ActionConfig(
                            id = "action${listModel.size + 1}",
                            label = "Action",
                            instruction = "Describe what this action should do.",
                        ),
                    )
                }
                .setRemoveAction {
                    val idx = list.selectedIndex
                    if (idx >= 0) listModel.remove(idx)
                }
                .setEditAction {
                    val idx = list.selectedIndex
                    if (idx >= 0) {
                        val current = listModel.getElementAt(idx)
                        val result = ActionConfigEditDialog(current).showAndGetResult() ?: return@setEditAction
                        listModel.set(idx, result)
                    }
                }
                .disableUpDownActions()
                .createPanel()

        center.add(decorator, BorderLayout.CENTER)
        center.add(
            JBLabel("Use Add, Remove, and Edit to manage actions. Labels are capped at 20 characters."),
            BorderLayout.SOUTH,
        )
        return center
    }

    fun getActionsJson(): String =
        FrontendActionCatalog.encode(
            FrontendActionCatalog.normalized((0 until listModel.size()).map { listModel.getElementAt(it) }),
        )

    fun createContentComponent(): JComponent = createCenterPanel()

    private class ActionConfigEditDialog(
        private val initial: FrontendActionCatalog.ActionConfig,
    ) : DialogWrapper(true) {
        private val labelField = JBTextField(initial.label)
        private val instructionField = JBTextArea(initial.instruction, 6, 36).apply {
            lineWrap = true
            wrapStyleWord = true
        }

        init {
            title = "Action"
            init()
        }

        override fun createCenterPanel(): JComponent =
            panel {
                row("Title") { cell(labelField).align(AlignX.FILL) }
                row("Instruction") { cell(instructionField).align(AlignX.FILL) }
            }

        fun showAndGetResult(): FrontendActionCatalog.ActionConfig? {
            if (!showAndGet()) return null
            return initial.copy(
                label = labelField.text.trim().take(20),
                instruction = instructionField.text.trim(),
            )
        }
    }

    private class ActionConfigListRenderer : javax.swing.ListCellRenderer<FrontendActionCatalog.ActionConfig> {
        private val panel = JPanel(BorderLayout())
        private val title = JBLabel()
        private val instruction = JBLabel()

        override fun getListCellRendererComponent(
            list: javax.swing.JList<out FrontendActionCatalog.ActionConfig>,
            value: FrontendActionCatalog.ActionConfig,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            panel.removeAll()
            title.text = value.label
            instruction.text = value.instruction
            panel.add(title, BorderLayout.NORTH)
            panel.add(instruction, BorderLayout.SOUTH)
            panel.background = if (isSelected) list.selectionBackground else list.background
            title.foreground = if (isSelected) list.selectionForeground else list.foreground
            instruction.foreground = title.foreground
            return panel
        }
    }
}
