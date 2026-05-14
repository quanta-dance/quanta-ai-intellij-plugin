// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.openai

import com.github.quanta_dance.quanta.plugins.intellij.backend.settings.BackendRuntimeSettingsService
import com.intellij.openapi.project.Project
import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient

/**
 * Provider for obtaining OpenAIClient instances configured from the backend runtime settings.
 */
object OpenAIClientProvider {
    /**
     * Builds a fresh OpenAI client from the current backend runtime settings snapshot.
     *
     * Callers that can outlive settings synchronization in split-mode should re-read the client
     * through this provider instead of assuming startup-time credentials remain current.
     */
    fun get(project: Project): OpenAIClient {
        val state = BackendRuntimeSettingsService.instance.settings
        return OpenAIOkHttpClient
            .builder()
            .apiKey(state.openAiToken)
            .baseUrl(state.openAiUrl)
            .maxRetries(2)
            .build()
    }
}
