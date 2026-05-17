// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.frontend.actions

import com.github.quanta_dance.quanta.plugins.intellij.frontend.chat.viewmodel.FrontendChatRepositoryModel
import com.github.quanta_dance.quanta.plugins.intellij.frontend.coroutines.CoroutineScopeHolder
import com.github.quanta_dance.quanta.plugins.intellij.frontend.settings.FrontendActionCatalog
import com.github.quanta_dance.quanta.plugins.intellij.frontend.settings.FrontendQuantaSettingsState
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import kotlinx.coroutines.launch

/**
 * Dynamic editor action group backed by the configurable frontend action catalog.
 *
 * It turns persisted action definitions into runtime action instances that forward intent into chat.
 */
class FrontendConfiguredActionsGroup : ActionGroup() {
    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val actions = FrontendActionCatalog.decode(FrontendQuantaSettingsState.instance.state.actionConfigsJson)
        return FrontendActionCatalog.normalized(actions).map { FrontendConfiguredAction(it) }.toTypedArray()
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

private class FrontendConfiguredAction(
    private val config: FrontendActionCatalog.ActionConfig,
) : AnAction(config.label, config.instruction, null) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val text =
            buildString {
                append(config.label)
                append(": ")
                append(config.instruction)
            }
        CoroutineScopeHolder.getInstance(project).getPluginScope().launch {
            runCatching {
                FrontendChatRepositoryModel.getInstance(project).sendMessage(text)
            }
        }
    }

    override fun update(e: AnActionEvent) {
        templatePresentation.text = config.label.take(20)
        templatePresentation.description = config.instruction
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
