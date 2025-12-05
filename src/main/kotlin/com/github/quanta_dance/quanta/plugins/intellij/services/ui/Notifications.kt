// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.services.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

object Notifications {
    fun show(project: Project?, content: String, type: NotificationType = NotificationType.INFORMATION) {
        val notificationGroup = NotificationGroupManager.getInstance().getNotificationGroup("Plugin Notifications")
        val notification = notificationGroup.createNotification(content, type)
        notification.notify(project)
    }
}
