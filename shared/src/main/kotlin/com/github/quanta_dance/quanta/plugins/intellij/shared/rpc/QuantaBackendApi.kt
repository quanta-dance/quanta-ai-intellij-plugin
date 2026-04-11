@file:Suppress("UnstableApiUsage")

package com.github.quanta_dance.quanta.plugins.intellij.shared.rpc

import com.intellij.platform.project.ProjectId
import com.intellij.platform.rpc.RemoteApiProviderService
import fleet.rpc.RemoteApi
import fleet.rpc.Rpc
import fleet.rpc.remoteApiDescriptor

@Rpc
interface QuantaBackendApi : RemoteApi<Unit> {
    companion object {
        suspend fun getInstance(): QuantaBackendApi {
            return RemoteApiProviderService.resolve(remoteApiDescriptor<QuantaBackendApi>())
        }
    }

    suspend fun ping(): String

   // projectId: ProjectId
}
