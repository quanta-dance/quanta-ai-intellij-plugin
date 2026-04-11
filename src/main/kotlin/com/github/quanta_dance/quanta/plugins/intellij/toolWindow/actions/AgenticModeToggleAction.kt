// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.toolWindow.actions

import com.github.quanta_dance.quanta.plugins.intellij.frontend.settings.FrontendQuantaSettingsState
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.DumbAware

class AgenticModeToggleAction : ToggleAction("Agentic Mode", "Agentic mode", AllIcons.CodeWithMe.Users), DumbAware {
    override fun isSelected(e: AnActionEvent): Boolean =
        FrontendQuantaSettingsState.instance.state.agenticEnabled ?: true

    override fun setSelected(
        e: AnActionEvent,
        state: Boolean,
    ) {
        FrontendQuantaSettingsState.instance.state.agenticEnabled = state
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}