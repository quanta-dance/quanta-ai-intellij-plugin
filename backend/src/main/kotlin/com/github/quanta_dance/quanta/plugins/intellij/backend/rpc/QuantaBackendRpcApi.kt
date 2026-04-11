package com.github.quanta_dance.quanta.plugins.intellij.backend.rpc

import com.github.quanta_dance.quanta.plugins.intellij.backend.contracts.BackendWorkspaceFileService
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.WorkspaceFileReadRequest
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.WorkspaceFileWriteRequest
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.QuantaBackendApi
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.WorkspaceFileRpcApi

class QuantaBackendRpcApi : QuantaBackendApi {
    override suspend fun ping(): String = "pong-from-backend"
}

class BackendWorkspaceFileRpcApi : WorkspaceFileRpcApi {
    private val workspaceFileService = BackendWorkspaceFileService()

    override suspend fun read(path: String): String =
        workspaceFileService.read(WorkspaceFileReadRequest(path)).content

    override suspend fun write(path: String, content: String): Boolean =
        workspaceFileService.write(WorkspaceFileWriteRequest(path, content)).success
}