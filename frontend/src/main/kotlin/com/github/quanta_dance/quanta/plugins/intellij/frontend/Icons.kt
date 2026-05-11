// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.frontend

import com.intellij.ui.IconManager

@Suppress("unused")
object Icons {
    @JvmField
    val ToolWindow = IconManager.getInstance().getIcon("/icons/toolWindow.svg", javaClass.getClassLoader())
}
