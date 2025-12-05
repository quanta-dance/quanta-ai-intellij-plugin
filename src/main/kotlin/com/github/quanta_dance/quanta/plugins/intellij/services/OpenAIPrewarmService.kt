// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.services

import com.github.quanta_dance.quanta.plugins.intellij.settings.QuantaAISettingsState
import com.github.quanta_dance.quanta.plugins.intellij.services.openai.OpenAIClientProvider
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.openai.client.OpenAIClient
import java.net.URI
import java.net.URL
import java.util.concurrent.Executors

/**
 * DNS/connection prewarm helper: resolves API host and initializes the OpenAI client off the UI thread.
 */
@Service(Service.Level.PROJECT)
class OpenAIPrewarmService(private val project: Project) {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "openai-prewarm") }

    fun prewarm() {
        executor.submit {
            try {
                val hostUrl = QuantaAISettingsState.instance.state.host
                val uri = try { URI(hostUrl) } catch (_: Throwable) { null }
                val host = uri?.host ?: try { URL(hostUrl).host } catch (_: Throwable) { null }
                if (!host.isNullOrBlank()) {
                    try { java.net.InetAddress.getAllByName(host) } catch (_: Throwable) {}
                }
                // Ensure client is constructed (connection pool established)
                val cli: OpenAIClient = OpenAIClientProvider.get(project)
                thisLogger().info("OpenAI client prewarmed for host=$hostUrl")
            } catch (t: Throwable) {
                thisLogger().warn("OpenAI prewarm failed: ${t.message}")
            }
        }
    }
}
