package com.github.quanta_dance.quanta.plugins.intellij.frontend.settings

import com.github.quanta_dance.quanta.plugins.intellij.frontend.rpc.FrontendSettingsRpcService
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import kotlinx.coroutines.delay

/**
 * Startup sync that pushes the local frontend settings snapshot to the backend.
 *
 * This is especially important in split-mode, where backend services may start before the frontend
 * settings UI has synchronized the effective URL, token, and related runtime configuration.
 */
class FrontendSettingsStartupActivity : ProjectActivity {
    private val logger = thisLogger()

    override suspend fun execute(project: Project) {
        logger.info("Quanta AI frontend settings startup sync beginning for project=${project.name}")

        val currentState = FrontendQuantaSettingsState.instance.state
        logger.info(
            "Quanta AI frontend settings snapshot before sync: " +
                    "model=${currentState.model}, aiChatModel=${currentState.aiChatModel}, " +
                    "openAiUrl=${currentState.openAiUrl}, voiceEnabled=${currentState.voiceEnabled}, " +
                    "dynamicModelEnabled=${currentState.dynamicModelEnabled}, agenticEnabled=${currentState.agenticEnabled}, " +
                    "terminalToolEnabled=${currentState.terminalToolEnabled}",
        )

        val rpc = FrontendSettingsRpcService.getInstance(project)
        logger.info("Quanta AI frontend settings RPC service resolved: ${rpc::class.java.name}")

        val retryDelaysMs = listOf(0L, 1_000L, 3_000L)
        var lastError: Throwable? = null

        for ((attemptIndex, delayMs) in retryDelaysMs.withIndex()) {
            if (delayMs > 0) {
                logger.info(
                    "Quanta AI frontend settings sync retry scheduled for project=${project.name}, " +
                            "attempt=${attemptIndex + 1}, delayMs=$delayMs",
                )
                delay(delayMs)
            }

            try {
                logger.info(
                    "Quanta AI frontend settings sync attempt ${attemptIndex + 1} starting for project=${project.name}",
                )
                rpc.updateSettings(currentState.toDto())
                val backendSettings = rpc.getSettings()
                FrontendQuantaSettingsState.instance.loadState(backendSettings.toFrontendState())
                logger.info(
                    "Quanta AI frontend settings synced to backend on startup after attempt ${attemptIndex + 1}",
                )
                return
            } catch (error: Throwable) {
                lastError = error
                logger.warn(
                    "Quanta AI frontend settings sync attempt ${attemptIndex + 1} failed for project=${project.name}",
                    error,
                )
                logger.warn(
                    "Quanta AI frontend settings sync failure type=${error::class.qualifiedName}, message=${error.message}",
                )
            }
        }

        logger.warn(
            "Quanta AI frontend settings sync giving up for project=${project.name} after ${retryDelaysMs.size} attempts",
            lastError,
        )
    }
}