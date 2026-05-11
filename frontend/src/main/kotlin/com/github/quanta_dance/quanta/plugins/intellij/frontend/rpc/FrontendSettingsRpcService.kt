// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.frontend.rpc

import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.QuantaSettingsApi
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.QuantaSettingsDto
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.Service.Level
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project

/**
 * Frontend-side adapter for the shared settings RPC.
 *
 * The frontend uses this service to push UI-edited settings to the backend and to reload the
 * backend-accepted snapshot afterward, which is especially important in remote split-mode.
 */
@Service(Level.PROJECT)
class FrontendSettingsRpcService(
    private val project: Project,
) {
    private val logger = thisLogger()

    companion object {
        fun getInstance(project: Project): FrontendSettingsRpcService =
            project.getService(FrontendSettingsRpcService::class.java)
    }

    suspend fun getSettings(): QuantaSettingsDto {
        val api = QuantaSettingsApi.getInstance()
        return api.getSettings()
    }

    suspend fun updateSettings(settings: QuantaSettingsDto) {
        logger.info(
            "Quanta AI frontend settings RPC update requested for project=${project.name}: " +
                    "model=${settings.model}, aiChatModel=${settings.aiChatModel}, openAiUrl=${settings.openAiUrl}, " +
                    "voiceEnabled=${settings.voiceEnabled}, dynamicModelEnabled=${settings.dynamicModelEnabled}, " +
                    "agenticEnabled=${settings.agenticEnabled}, terminalToolEnabled=${settings.terminalToolEnabled}",
        )

        try {
            val api = QuantaSettingsApi.getInstance()
            logger.info("Quanta AI backend settings API resolved: ${api::class.java.name}")
            api.updateSettings(settings)
            logger.info("Quanta AI backend settings API update completed")
        } catch (error: Throwable) {
            logger.warn("Quanta AI backend settings API update failed", error)
            logger.warn("Quanta AI backend settings API failure type=${error::class.qualifiedName}, message=${error.message}")
            throw error
        }
    }
}
