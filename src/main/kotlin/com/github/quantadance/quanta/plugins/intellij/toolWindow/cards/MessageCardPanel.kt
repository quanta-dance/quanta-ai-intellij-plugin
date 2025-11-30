// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quantadance.quanta.plugins.intellij.toolWindow.cards

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import javax.swing.BorderFactory
import javax.swing.JTextArea
import javax.swing.UIManager

class MessageCardPanel(
    private val title: String,
    private val message: String,
) :
    JBPanel<Nothing>(BorderLayout()) {
    init {
        initializeUI()
    }

    private fun initializeUI() {
        val descriptionArea =
            JTextArea(message).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                font = font.deriveFont(font.size2D - 1)
                foreground = JBColor.DARK_GRAY
                background = UIManager.getColor("Panel.background")
                border = BorderFactory.createEmptyBorder(5, 5, 5, 5)
                maximumSize = this.preferredSize // Set maximum size to preferred size
            }

        val descriptionScrollPane =
            JBScrollPane(descriptionArea).apply {
                border = BorderFactory.createTitledBorder(BorderFactory.createLineBorder(JBColor.LIGHT_GRAY), title)
            }

        val topPanel =
            JBPanel<Nothing>(BorderLayout()).apply {
                add(descriptionScrollPane, BorderLayout.NORTH) // Align to North to prevent expanding
                border = BorderFactory.createEmptyBorder(5, 5, 5, 5)
                background = UIManager.getColor("Panel.background")
            }

        add(topPanel, BorderLayout.NORTH)
    }
}
