// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.frontend.actions

import com.github.quanta_dance.quanta.plugins.intellij.frontend.prompts.FrontendCustomPromptServices
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.CustomPromptRequest
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import kotlinx.coroutines.runBlocking

/**
 * Frontend action that dispatches a canned custom-prompt request.
 *
 * It is primarily a user-facing entry point into the custom prompt contract and related adapter
 * chain.
 */
class CustomUserAction : AnAction("Custom User Action") {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val response =
            runBlocking {
                FrontendCustomPromptServices.customPromptService().run(
                    CustomPromptRequest("Custom prompt execution requested from frontend action."),
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

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
