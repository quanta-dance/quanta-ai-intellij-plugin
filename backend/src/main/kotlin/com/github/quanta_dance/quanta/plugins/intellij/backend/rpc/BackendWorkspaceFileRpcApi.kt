package com.github.quanta_dance.quanta.plugins.intellij.backend.rpc

import com.github.quanta_dance.quanta.plugins.intellij.backend.contracts.BackendWorkspaceFileService
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.WorkspaceFileReadRequest
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.WorkspaceFileWriteRequest
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.WorkspaceFileRpcApi

/**
 * Backend RPC adapter for workspace file read/write operations.
 *
 * It keeps filesystem access in the backend process and delegates to the backend workspace file
 * service used by split-mode callers.
 */
class BackendWorkspaceFileRpcApi : WorkspaceFileRpcApi {
    private val service = BackendWorkspaceFileService()

    override suspend fun read(path: String): String =
        service.read(WorkspaceFileReadRequest(path)).content

    override suspend fun write(path: String, content: String): Boolean =
        service.write(WorkspaceFileWriteRequest(path, content)).success
}
