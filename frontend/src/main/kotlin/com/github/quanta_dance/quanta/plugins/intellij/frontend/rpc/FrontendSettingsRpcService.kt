package com.github.quanta_dance.quanta.plugins.intellij.frontend.rpc

import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.QuantaSettingsApi
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.QuantaSettingsDto
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.Service.Level
import com.intellij.openapi.project.Project
import fleet.rpc.client.durable
import kotlinx.coroutines.CoroutineScope

@Service(Level.PROJECT)
class FrontendSettingsRpcService(
    private val project: Project,
) {
    companion object {
        fun getInstance(project: Project): FrontendSettingsRpcService =
            project.getService(FrontendSettingsRpcService::class.java)
    }

    suspend fun updateSettings(settings: QuantaSettingsDto) {
        QuantaSettingsApi.getInstance().updateSettings(settings)
    }
}
