// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.toolWindow.cards

import com.intellij.ui.components.JBPanel
import java.awt.BorderLayout
import java.awt.image.BufferedImage
import java.net.URI
import javax.imageio.ImageIO
import javax.swing.BorderFactory
import javax.swing.ImageIcon
import javax.swing.JLabel
import javax.swing.UIManager

class ImageCardPanel(
    private val title: String,
    private val url: String,
) :
    JBPanel<Nothing>(BorderLayout()) {
    init {
        initializeUI()
    }

    private fun initializeUI() {
        this.border = BorderFactory.createTitledBorder(title)
        maximumSize = this.preferredSize // Set maximum size to preferred size

        val imageUrl = URI(url).toURL()
        val image: BufferedImage? = ImageIO.read(imageUrl)
        val label = JLabel(ImageIcon(image))

        val topPanel =
            JBPanel<Nothing>(BorderLayout()).apply {
                add(label, BorderLayout.CENTER)
                border = BorderFactory.createEmptyBorder(5, 5, 5, 5)
                background = UIManager.getColor("Panel.background")
            }

        add(topPanel, BorderLayout.CENTER)
    }
}
