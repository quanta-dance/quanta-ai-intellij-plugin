// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.frontend.actions

import com.github.quanta_dance.quanta.plugins.intellij.frontend.settings.FrontendQuantaPluginConfigurable
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project

/**
 * Convenience action that opens the Quanta frontend settings configurable.
 *
 * It provides a user-facing entry point from menus and tool windows into the same configuration
 * surface that later synchronizes settings to the backend.
 */
class SettingsAction : AnAction("Quanta AI Settings", "Open Quanta AI plugin settings", AllIcons.General.Settings) {
    override fun actionPerformed(e: AnActionEvent) {
        val project: Project? = e.project
        if (project != null) {
            ShowSettingsUtil.getInstance().showSettingsDialog(
                project,
                FrontendQuantaPluginConfigurable::class.java,
            )
        } else {
            ShowSettingsUtil.getInstance().showSettingsDialog(
                null as Project?,
                FrontendQuantaPluginConfigurable::class.java,
            )
        }
    }
}
