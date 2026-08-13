// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.frontend.logging

import com.github.quanta_dance.quanta.plugins.intellij.frontend.QDLog
import com.github.quanta_dance.quanta.plugins.intellij.frontend.rpc.rpcProjectPath
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.QuantaBackendApi
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.FrontendLogDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.FrontendLogLevel
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Small frontend-side bridge that mirrors important frontend logs to the backend over the existing
 * logging RPC so split-mode diagnostics are visible in one place.
 */
@Service(Service.Level.PROJECT)
class FrontendBackendLogBridge(
    private val project: Project,
) : Disposable {
    private val logger = Logger.getInstance(FrontendBackendLogBridge::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun debug(message: String) = log(FrontendLogLevel.DEBUG, message)

    fun info(message: String) = log(FrontendLogLevel.INFO, message)

    fun warn(message: String) = log(FrontendLogLevel.WARN, message)

    fun error(message: String) = log(FrontendLogLevel.ERROR, message)

    fun log(
        level: FrontendLogLevel,
        message: String,
    ) {
        when (level) {
            FrontendLogLevel.DEBUG -> QDLog.debug(logger) { message }
            FrontendLogLevel.INFO -> QDLog.info(logger) { message }
            FrontendLogLevel.WARN -> QDLog.warn(logger) { message }
            FrontendLogLevel.ERROR -> QDLog.error(logger, { message }, null)
        }
        scope.launch {
            runCatching {
                QuantaBackendApi.getInstance().logFrontend(
                    project.rpcProjectPath(),
                    FrontendLogDto(level = level, message = message),
                )
            }
        }
    }

    override fun dispose() {
        scope.cancel()
    }
}
