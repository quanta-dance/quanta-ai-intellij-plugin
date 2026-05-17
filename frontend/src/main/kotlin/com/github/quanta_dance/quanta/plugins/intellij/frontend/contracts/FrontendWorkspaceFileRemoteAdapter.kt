// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.frontend.contracts

import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.WorkspaceFileReadRequest
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.WorkspaceFileReadResult
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.WorkspaceFileService
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.WorkspaceFileWriteRequest
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.WorkspaceFileWriteResult
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.WorkspaceFileRpcApi
import com.intellij.openapi.project.Project

/**
 * RPC-backed frontend implementation of the shared workspace file service.
 *
 * It translates the shared contract into calls on [WorkspaceFileRpcApi] so frontend callers can
 * reach backend-owned file access in split-mode.
 */
class FrontendWorkspaceFileRemoteAdapter(
    private val project: Project,
) : WorkspaceFileService {
    override suspend fun read(request: WorkspaceFileReadRequest): WorkspaceFileReadResult =
        WorkspaceFileRpcApi.getInstance().read(request.path).let { content ->
            WorkspaceFileReadResult(success = true, content = content, source = "frontend-rpc")
        }

    override suspend fun write(request: WorkspaceFileWriteRequest): WorkspaceFileWriteResult =
        WorkspaceFileRpcApi.getInstance().write(request.path, request.content).let { success ->
            WorkspaceFileWriteResult(success = success, source = "frontend-rpc")
        }
}
