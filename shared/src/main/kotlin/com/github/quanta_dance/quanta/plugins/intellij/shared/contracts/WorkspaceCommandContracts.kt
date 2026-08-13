// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.shared.contracts

data class WorkspaceCommandRequest(
    val command: String,
    val workingDirectory: String? = null,
    val environment: Map<String, String> = emptyMap(),
)

data class WorkspaceCommandResult(
    val success: Boolean,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

/**
 * Shared contract for backend-owned workspace command execution.
 *
 * It models shell-like command requests in a transport-neutral shape so frontend callers can ask
 * the backend to execute commands without assuming local process access.
 */
interface WorkspaceCommandService {
    suspend fun execute(request: WorkspaceCommandRequest): WorkspaceCommandResult
}
