// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.toolWindow.cards

import com.intellij.ui.JBColor
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.border.EmptyBorder

class UserMessageCardPanel(
    private val message: String,
) :
    JPanel(BorderLayout()) {
    // Exposed text area so external callers can update the text (e.g., streaming transcription deltas)
    val updateText: JTextArea

    init {
        updateText =
            JTextArea(message).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                font = font.deriveFont(font.style or java.awt.Font.ITALIC, font.size2D - 1)
                foreground = UIManager.getColor("Panel.foreground")
                background = JBColor.LIGHT_GRAY
                border = EmptyBorder(5, 10, 5, 5) // Padding inside the TextArea
                maximumSize = preferredSize // Set maximum size to preferred size
            }

        // preferredSize = Dimension(Int.MAX_VALUE, textArea.preferredSize.height)
        // maximumSize = Dimension(Int.MAX_VALUE, textArea.preferredSize.height + 5)
        border = EmptyBorder(5, 10, 5, 5) // Margin for JPanel
        add(updateText, BorderLayout.NORTH)
    }

    /**
     * Safely update the text area from any thread. If append==true, append the text; otherwise replace content.
     * This method schedules the update on Swing EDT.
     */
    fun updateTextSafe(
        text: String,
        append: Boolean = false,
    ) {
        if (SwingUtilities.isEventDispatchThread()) {
            if (append) {
                updateText.append(text)
            } else {
                updateText.text = text
            }
            // Optionally move caret to end so new appended text is visible
            updateText.caretPosition = updateText.document.length
        } else {
            SwingUtilities.invokeLater {
                if (append) {
                    updateText.append(text)
                } else {
                    updateText.text = text
                }
                updateText.caretPosition = updateText.document.length
            }
        }
    }

    /** Convenience for appending partial deltas from streaming transcription */
    fun appendPartial(delta: String) {
        updateTextSafe(delta, append = true)
    }
}
