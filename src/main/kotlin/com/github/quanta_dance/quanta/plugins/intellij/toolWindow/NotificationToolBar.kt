package com.github.quanta_dance.quanta.plugins.intellij.toolWindow

import com.intellij.notification.*
import com.intellij.openapi.project.Project

object NotificationToolBar {

    /**
     *
     */
    fun showNotification(project: Project?, content: String) {
        val notificationGroup = NotificationGroupManager.getInstance()
            .getNotificationGroup("Plugin Notifications") // Matches plugin.xml ID

        val notification = notificationGroup.createNotification(
            content,
            NotificationType.INFORMATION
        )

        notification.notify(project)
    }
}
