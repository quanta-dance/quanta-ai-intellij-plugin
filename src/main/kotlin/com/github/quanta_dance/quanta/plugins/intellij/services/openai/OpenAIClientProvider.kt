// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.services.openai

import com.github.quanta_dance.quanta.plugins.intellij.settings.QuantaAISettingsState
import com.intellij.openapi.project.Project
import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient

/**
 * Provider for obtaining OpenAIClient instances configured from the plugin state.
 * Note: Current SDK version does not expose custom OkHttp injection; rely on default transport.
 */
object OpenAIClientProvider {
    /**
     * Returns a new OpenAIClient instance configured with API key and base URL
     * derived from the current plugin settings.
     */
    fun get(project: Project): OpenAIClient {
        val state = QuantaAISettingsState.instance.state
        return OpenAIOkHttpClient.builder()
            .apiKey(state.token)
            .baseUrl(state.host)
            .build()
    }
}
