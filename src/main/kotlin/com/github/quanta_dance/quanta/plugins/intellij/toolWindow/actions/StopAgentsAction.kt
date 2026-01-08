// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.toolWindow.actions

import com.github.quanta_dance.quanta.plugins.intellij.services.AgentManagerService
import com.github.quanta_dance.quanta.plugins.intellij.settings.QuantaAISettingsState
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware

class StopAgentsAction : AnAction("Stop All Agents", "Stop all agents", AllIcons.Debugger.KillProcess), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        project.service<AgentManagerService>().stopAllAgents()
    }

    override fun update(e: AnActionEvent) {
        // Hide when agentic mode is disabled
        val agentic = QuantaAISettingsState.instance.state.agenticEnabled ?: true
        e.presentation.isVisible = agentic
        e.presentation.isEnabled = agentic
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}
