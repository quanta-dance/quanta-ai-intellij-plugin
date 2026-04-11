package com.github.quanta_dance.quanta.plugins.intellij.frontend.settings

import com.github.quanta_dance.quanta.plugins.intellij.frontend.rpc.FrontendSettingsRpcService
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import kotlinx.coroutines.runBlocking

class FrontendSettingsStartupActivity : ProjectActivity {
    private val logger = thisLogger()

    override suspend fun execute(project: Project) {
        runCatching {
            val rpc = FrontendSettingsRpcService.getInstance(project)
            runBlocking { rpc.updateSettings(FrontendQuantaSettingsState.instance.state.toDto()) }
            logger.info("Quanta AI frontend settings synced to backend on startup")
        }.onFailure { error ->
            logger.warn("Quanta AI frontend settings sync on startup failed", error)
        }
    }
}