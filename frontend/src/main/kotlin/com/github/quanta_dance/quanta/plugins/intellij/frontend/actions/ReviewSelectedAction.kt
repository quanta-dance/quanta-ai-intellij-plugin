package com.github.quanta_dance.quanta.plugins.intellij.frontend.actions

import com.github.quanta_dance.quanta.plugins.intellij.frontend.settings.FrontendActionCatalog
import com.github.quanta_dance.quanta.plugins.intellij.frontend.settings.FrontendQuantaSettingsState
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.QuantaBackendApi
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import kotlinx.coroutines.runBlocking

/**
 * Frontend action entry point for the review flow.
 *
 * TODO: this action currently exercises backend connectivity with a simple ping and should evolve
 * into the real review request flow described in the migration map.
 */
class ReviewSelectedAction : AnAction("Review") {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return

        val response = runBlocking {
            QuantaBackendApi.getInstance().ping()
        }

        NotificationGroupManager
            .getInstance()
            .getNotificationGroup("Plugin Notifications")
            .createNotification(
                "Quanta AI",
                "backend replied: $response",
                NotificationType.INFORMATION,
            ).notify(project)
    }

    override fun update(event: AnActionEvent) {
        templatePresentation.text =
            FrontendActionCatalog.actionById(
                FrontendQuantaSettingsState.instance.state.actionConfigsJson,
                "review"
            )?.label
                ?: "Review"
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}