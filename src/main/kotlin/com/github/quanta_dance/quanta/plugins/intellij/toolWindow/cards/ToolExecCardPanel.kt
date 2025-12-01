// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.toolWindow.cards

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.BorderFactory
import javax.swing.JTextArea
import javax.swing.UIManager

class ToolExecCardPanel(
    private val title: String,
    message: String,
) : JBPanel<Nothing>(BorderLayout()) {
    private val descriptionArea =
        JTextArea(message).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = font.deriveFont(font.size2D - 1)
            foreground = JBColor.GRAY
            background = UIManager.getColor("Label.background")
            maximumSize = preferredSize
        }

    init {
        initializeUI()
    }

    private fun initializeUI() {
        this.border = BorderFactory.createTitledBorder(title)

        val descriptionScrollPane =
            JBScrollPane(descriptionArea).apply {
                border = BorderFactory.createEmptyBorder(5, 5, 5, 1)
                maximumSize = Dimension(500, descriptionArea.preferredSize.height)
            }

        val topPanel =
            JBPanel<Nothing>(BorderLayout()).apply {
                add(descriptionScrollPane, BorderLayout.NORTH)
                border = BorderFactory.createEmptyBorder(5, 5, 5, 5)
                background = UIManager.getColor("Panel.background")
            }

        add(topPanel, BorderLayout.NORTH)
    }

    fun setText(text: String) {
        descriptionArea.text = text
        // auto-scroll to bottom
        descriptionArea.caretPosition = descriptionArea.document.length
    }

    fun appendLine(line: String) {
        if (descriptionArea.text.isEmpty()) {
            descriptionArea.text = line
        } else {
            descriptionArea.append("\n")
            descriptionArea.append(line)
        }
        descriptionArea.caretPosition = descriptionArea.document.length
    }
}
