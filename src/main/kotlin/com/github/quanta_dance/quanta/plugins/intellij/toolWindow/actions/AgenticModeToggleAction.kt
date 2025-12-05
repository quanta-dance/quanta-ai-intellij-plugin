// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.toolWindow.actions

import com.github.quanta_dance.quanta.plugins.intellij.settings.QuantaAISettingsListener
import com.github.quanta_dance.quanta.plugins.intellij.settings.QuantaAISettingsState
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread

class AgenticModeToggleAction : ToggleAction("Agentic Mode", "Agentic mode", AllIcons.CodeWithMe.Users), DumbAware {
    override fun isSelected(e: AnActionEvent): Boolean {
        return QuantaAISettingsState.instance.state.agenticEnabled ?: true
    }

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        val s = QuantaAISettingsState.instance.state
        s.agenticEnabled = state
        val snapshot = s.copy()
        ApplicationManager.getApplication().messageBus.syncPublisher(QuantaAISettingsListener.TOPIC).onSettingsChanged(snapshot)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}
