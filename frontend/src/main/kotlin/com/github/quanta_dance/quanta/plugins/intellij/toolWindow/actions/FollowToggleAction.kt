// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.frontend.toolwindow.actions

import com.github.quanta_dance.quanta.plugins.intellij.frontend.settings.FrontendQuantaSettingsState
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service

class FollowToggleAction : ToggleAction(
    "Follow",
    "When enabled, the editor will navigate/focus to AI changes. When disabled, focus/caret won't jump.",
    AllIcons.General.Locate,
) {
    override fun isSelected(e: AnActionEvent): Boolean {
        return ApplicationManager.getApplication().service<FrontendQuantaSettingsState>().state.followEnabled
    }

    override fun setSelected(
        e: AnActionEvent,
        sel: Boolean,
    ) {
        ApplicationManager.getApplication().service<FrontendQuantaSettingsState>().state.followEnabled = sel
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}
