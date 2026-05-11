// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.frontend.contracts

import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.WorkspaceFileReadResult
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.WorkspaceFileService
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.WorkspaceFileWriteResult

class FrontendWorkspaceFileClientExample(
    private val client: FrontendWorkspaceFileClient,
) {
    suspend fun readExample(path: String): WorkspaceFileReadResult = client.read(path)

    suspend fun writeExample(
        path: String,
        content: String,
    ): WorkspaceFileWriteResult = client.write(path, content)

    companion object {
        fun from(service: WorkspaceFileService): FrontendWorkspaceFileClientExample =
            FrontendWorkspaceFileClientExample(FrontendWorkspaceFileClient(service))
    }
}
