// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quantadance.quanta.plugins.intellij.toolWindow.cards

import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.AsyncProcessIcon
import java.awt.BorderLayout
import javax.swing.Box
import javax.swing.BoxLayout

class SpinnerCardPanel(message: String) : JBPanel<SpinnerCardPanel>(BorderLayout()) {
    private val icon = AsyncProcessIcon("ai-thinking")
    private val statusLabel = JBLabel("$message: 0s")

    init {
        isOpaque = false
        val row =
            JBPanel<Nothing>().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                isOpaque = false
                add(icon)
                add(Box.createHorizontalStrut(8))
                add(statusLabel)
            }
        add(row, BorderLayout.WEST)
    }

    fun setSeconds(sec: Long) {
        val base = statusLabel.text.substringBefore(": ")
        statusLabel.text = "$base: ${sec}s"
    }

    fun stop() {
        icon.suspend()
    }

    fun showError(text: String) {
        // Replace spinner with an exclamation/error icon and message
        icon.suspend()
        icon.isVisible = false
        statusLabel.icon = AllIcons.General.Error
        statusLabel.text = text
        revalidate()
        repaint()
    }
}
