// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.frontend.settings

import com.github.quanta_dance.quanta.plugins.intellij.frontend.logging.FrontendBackendLogBridge
import com.github.quanta_dance.quanta.plugins.intellij.frontend.rpc.FrontendSettingsRpcService
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks frontend-to-backend settings synchronization readiness for UI consumers.
 *
 * The chat UI uses this service to keep send actions disabled until startup sync completes, avoiding
 * confusing pre-sync sends in split-mode.
 */
@Service(Service.Level.PROJECT)
class FrontendSettingsSyncStateService(
    private val project: Project,
) {
    enum class Status {
        SYNCING,
        READY,
        FAILED,
    }

    data class State(
        val status: Status = Status.SYNCING,
        val lastErrorMessage: String? = null,
    )

    private val logger = thisLogger()

    private fun backendLogBridge(): FrontendBackendLogBridge? =
        runCatching { project.service<FrontendBackendLogBridge>() }.getOrNull()

    private val _stateFlow = MutableStateFlow(State())
    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    suspend fun syncOnStartup(): Boolean =
        syncWithRetry(
            retryDelaysMs = listOf(0L, 1_000L, 3_000L),
            reason = "startup",
        )

    suspend fun retryNow(): Boolean =
        syncWithRetry(
            retryDelaysMs = listOf(0L),
            reason = "manual retry",
        )

    private suspend fun syncWithRetry(
        retryDelaysMs: List<Long>,
        reason: String,
    ): Boolean {
        _stateFlow.value = State(status = Status.SYNCING)

        val currentState = FrontendQuantaSettingsState.instance.state
        val rpc = FrontendSettingsRpcService.getInstance(project)
        var lastError: Throwable? = null

        for ((attemptIndex, delayMs) in retryDelaysMs.withIndex()) {
            if (delayMs > 0) {
                logger.info(
                    "Quanta AI frontend settings sync retry scheduled for project=${project.name}, " +
                            "reason=$reason, attempt=${attemptIndex + 1}, delayMs=$delayMs",
                )
                delay(delayMs)
            }

            try {
                logger.info(
                    "Quanta AI frontend settings sync attempt ${attemptIndex + 1} starting for project=${project.name}, reason=$reason",
                )
                val mcpServersJson = project.service<FrontendMcpConfigService>().readForSync()
                if (mcpServersJson == null) {
                    backendLogBridge()?.warn(
                        "Skipping frontend settings sync because MCP config is empty or unreadable for project=${project.name}, reason=$reason",
                    )
                    return false
                }
                backendLogBridge()?.info(
                    "Frontend settings sync sending MCP config to backend for project=${project.name}, reason=$reason, chars=${mcpServersJson.length}",
                )
                rpc.updateSettings(currentState.toDto(project, mcpServersJson))
                val backendSettings = rpc.getSettings()
                FrontendQuantaSettingsState.instance.loadState(backendSettings.toFrontendState())
                _stateFlow.value = State(status = Status.READY)
                logger.info(
                    "Quanta AI frontend settings synced to backend after attempt ${attemptIndex + 1} for project=${project.name}, reason=$reason",
                )
                return true
            } catch (error: Throwable) {
                lastError = error
                logger.warn(
                    "Quanta AI frontend settings sync attempt ${attemptIndex + 1} failed for project=${project.name}, reason=$reason",
                    error,
                )
                logger.warn(
                    "Quanta AI frontend settings sync failure type=${error::class.qualifiedName}, message=${error.message}",
                )
            }
        }

        _stateFlow.value = State(status = Status.FAILED, lastErrorMessage = lastError?.message)
        logger.warn(
            "Quanta AI frontend settings sync giving up for project=${project.name} after ${retryDelaysMs.size} attempts, reason=$reason",
            lastError,
        )
        return false
    }
}
