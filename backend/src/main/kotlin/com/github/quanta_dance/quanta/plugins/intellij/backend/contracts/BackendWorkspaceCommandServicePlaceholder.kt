// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.contracts

import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.WorkspaceCommandRequest
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.WorkspaceCommandResult
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.WorkspaceCommandService

class BackendWorkspaceCommandServicePlaceholder : WorkspaceCommandService {
    override suspend fun execute(request: WorkspaceCommandRequest): WorkspaceCommandResult =
        WorkspaceCommandResult(
            success = false,
            exitCode = -1,
            stdout = "",
            stderr = "Backend workspace command service not wired yet.",
        )
}
