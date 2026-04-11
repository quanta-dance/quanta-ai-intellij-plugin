package com.github.quanta_dance.quanta.plugins.intellij.shared.rpc

import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.QuantaSettingsDto
import fleet.rpc.RemoteApi
import fleet.rpc.Rpc
import fleet.rpc.remoteApiDescriptor
import com.intellij.platform.rpc.RemoteApiProviderService

@Rpc
interface QuantaSettingsApi : RemoteApi<Unit> {
    companion object {
        suspend fun getInstance(): QuantaSettingsApi {
            return RemoteApiProviderService.resolve(remoteApiDescriptor<QuantaSettingsApi>())
        }
    }

    suspend fun getSettings(): QuantaSettingsDto
    suspend fun updateSettings(settings: QuantaSettingsDto)
}
