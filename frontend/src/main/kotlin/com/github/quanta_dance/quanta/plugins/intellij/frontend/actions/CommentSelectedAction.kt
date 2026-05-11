// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.frontend.actions

import com.github.quanta_dance.quanta.plugins.intellij.frontend.comment.FrontendCommentServices
import com.github.quanta_dance.quanta.plugins.intellij.frontend.settings.FrontendActionCatalog
import com.github.quanta_dance.quanta.plugins.intellij.frontend.settings.FrontendQuantaSettingsState
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.CommentRequest
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import kotlinx.coroutines.runBlocking

class CommentSelectedAction : AnAction("Comment") {
    override fun actionPerformed(event: AnActionEvent) {

        val project = event.project ?: return
        val selectedText = event.getData(CommonDataKeys.EDITOR)?.selectionModel?.selectedText
        val filePath = event.getData(CommonDataKeys.VIRTUAL_FILE)?.path
        val response =
            runBlocking {
                FrontendCommentServices.commentService().comment(
                    CommentRequest(
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
            FrontendActionCatalog.actionById(
                FrontendQuantaSettingsState.instance.state.actionConfigsJson,
                "comment"
            )?.label
                ?: "Comment"
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
