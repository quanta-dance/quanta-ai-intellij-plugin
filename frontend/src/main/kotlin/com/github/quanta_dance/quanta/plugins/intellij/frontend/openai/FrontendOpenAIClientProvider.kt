package com.github.quanta_dance.quanta.plugins.intellij.frontend.openai

import com.github.quanta_dance.quanta.plugins.intellij.frontend.settings.FrontendQuantaSettingsState
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient

object FrontendOpenAIClientProvider {

    private var client: OpenAIClient? = null

    fun get(project: Project): OpenAIClient {
        val state = FrontendQuantaSettingsState.instance.state

        NotificationGroupManager
            .getInstance()
            .getNotificationGroup("Plugin Notifications")
            .createNotification(
                "Quanta AI",
                "init broo", // response.toString() might be too long/unfriendly
                NotificationType.INFORMATION,
            ).notify(project)

        if (client == null) {
            try {
                client = OpenAIOkHttpClient.builder()
                    .apiKey(state.openAiToken)
                    .baseUrl(state.openAiUrl)
                    .maxRetries(2)
                    .build()
            } catch (e: Throwable) {
                NotificationGroupManager
                    .getInstance()
                    .getNotificationGroup("Plugin Notifications")
                    .createNotification(
                        "Quanta AI",
                        e.cause.toString(), // response.toString() might be too long/unfriendly
                        NotificationType.INFORMATION,
                    ).notify(project)
            }
        }
        return client!!
    }
}
