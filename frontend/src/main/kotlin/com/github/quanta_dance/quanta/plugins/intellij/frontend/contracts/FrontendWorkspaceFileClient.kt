// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.frontend.contracts

import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.WorkspaceFileReadRequest
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.WorkspaceFileReadResult
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.WorkspaceFileService
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.WorkspaceFileWriteRequest
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.WorkspaceFileWriteResult

/**
 * Small frontend adapter over the shared workspace file service contract.
 *
 * It keeps frontend callers on the contract surface instead of constructing request DTOs directly.
 */
class FrontendWorkspaceFileClient(
    private val workspaceFileService: WorkspaceFileService,
) {
    suspend fun read(path: String): WorkspaceFileReadResult = workspaceFileService.read(WorkspaceFileReadRequest(path))

    suspend fun write(
        path: String,
        content: String,
    ): WorkspaceFileWriteResult = workspaceFileService.write(WorkspaceFileWriteRequest(path, content))
}
