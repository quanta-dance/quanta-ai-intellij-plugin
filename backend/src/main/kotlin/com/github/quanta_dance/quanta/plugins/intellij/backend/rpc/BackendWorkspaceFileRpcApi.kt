package com.github.quanta_dance.quanta.plugins.intellij.backend.rpc

import com.github.quanta_dance.quanta.plugins.intellij.backend.contracts.BackendWorkspaceFileService
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.WorkspaceFileReadRequest
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.WorkspaceFileWriteRequest
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.WorkspaceFileRpcApi

class BackendWorkspaceFileRpcApi : WorkspaceFileRpcApi {
    private val service = BackendWorkspaceFileService()

    override suspend fun read(path: String): String =
        service.read(WorkspaceFileReadRequest(path)).content

    override suspend fun write(path: String, content: String): Boolean =
        service.write(WorkspaceFileWriteRequest(path, content)).success
}
