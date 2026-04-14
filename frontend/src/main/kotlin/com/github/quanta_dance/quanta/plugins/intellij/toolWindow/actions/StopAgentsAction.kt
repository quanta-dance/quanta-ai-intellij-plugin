// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.frontend.toolwindow.actions

import com.github.quanta_dance.quanta.plugins.intellij.frontend.settings.FrontendQuantaSettingsState
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware

class StopAgentsAction : AnAction("Stop All Agents", "Stop all agents", AllIcons.Debugger.KillProcess), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        // TODO fix this functionality in new flow. we click stop - but send signal to backend to stop actual agents
        //    project.service<AgentManagerService>().stopAllAgents()
    }

    override fun update(e: AnActionEvent) {
        // Hide when agentic mode is disabled
        val agentic = FrontendQuantaSettingsState.instance.state.agenticEnabled ?: true
        e.presentation.isVisible = agentic
        e.presentation.isEnabled = agentic
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}
