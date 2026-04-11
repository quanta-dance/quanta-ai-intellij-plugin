package com.github.quanta_dance.quanta.plugins.intellij.shared.rpc

import fleet.rpc.RemoteApi
import fleet.rpc.Rpc
import fleet.rpc.remoteApiDescriptor

@Rpc
interface WorkspaceFileRpcApi : RemoteApi<Unit> {
    companion object {
        suspend fun getInstance(): WorkspaceFileRpcApi =
            com.intellij.platform.rpc.RemoteApiProviderService.resolve(remoteApiDescriptor<WorkspaceFileRpcApi>())
    }

    suspend fun read(path: String): String

    suspend fun write(path: String, content: String): Boolean
}
