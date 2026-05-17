// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.frontend.contracts

import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.WorkspaceFileReadResult
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.Service.Level
import com.intellij.openapi.project.Project
import fleet.rpc.client.durable

/**
 * Frontend service that reads workspace files through the split-mode backend path.
 *
 * It wraps the remote adapter in a durable RPC call and normalizes the result for frontend users.
 */
@Service(Level.PROJECT)
class FrontendWorkspaceFileReadService(
    private val project: Project,
) {
    companion object {
        fun getInstance(project: Project): FrontendWorkspaceFileReadService =
            project.getService(FrontendWorkspaceFileReadService::class.java)
    }

    suspend fun readCurrentFile(path: String): WorkspaceFileReadResult =
        durable {
            val result =
                FrontendWorkspaceFileClient(
                    FrontendWorkspaceFileRemoteAdapter(project),
                ).read(path)
            result.copy(source = "backend")
        }
}
