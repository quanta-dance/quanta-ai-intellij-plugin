// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.frontend.actions

import com.github.quanta_dance.quanta.plugins.intellij.frontend.refactor.FrontendRefactorServices
import com.github.quanta_dance.quanta.plugins.intellij.frontend.settings.FrontendActionCatalog
import com.github.quanta_dance.quanta.plugins.intellij.frontend.settings.FrontendQuantaSettingsState
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.RefactorRequest
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import kotlinx.coroutines.runBlocking

class RefactorSelectedAction : AnAction("Refactor") {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val selectedText = event.getData(CommonDataKeys.EDITOR)?.selectionModel?.selectedText
        val filePath = event.getData(CommonDataKeys.VIRTUAL_FILE)?.path
        val response =
            runBlocking {
                FrontendRefactorServices.refactorService().refactor(
                    RefactorRequest(
                        filePath = filePath,
                        selectedText = selectedText,
                    ),
                )
            }
        NotificationGroupManager
            .getInstance()
            .getNotificationGroup("Plugin Notifications")
            .createNotification(
                "Quanta AI",
                response.message,
                if (response.success) NotificationType.INFORMATION else NotificationType.WARNING,
            ).notify(project)
    }

    override fun update(event: AnActionEvent) {
        templatePresentation.text =
            FrontendActionCatalog
                .actionById(
                    FrontendQuantaSettingsState.instance.state.actionConfigsJson,
                    "refactor",
                )?.label
                ?: "Refactor"
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
