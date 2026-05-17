// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.shared.rpc

import fleet.rpc.RemoteApi
import fleet.rpc.Rpc
import fleet.rpc.remoteApiDescriptor

/**
 * Minimal shared RPC API for backend-owned workspace file reads and writes.
 *
 * The backend remains responsible for filesystem access so frontend callers can work safely in
 * split-mode and remote environments.
 */
@Rpc
interface WorkspaceFileRpcApi : RemoteApi<Unit> {
    companion object {
        suspend fun getInstance(): WorkspaceFileRpcApi =
            com.intellij.platform.rpc.RemoteApiProviderService
                .resolve(remoteApiDescriptor<WorkspaceFileRpcApi>())
    }

    suspend fun read(path: String): String

    suspend fun write(
        path: String,
        content: String,
    ): Boolean
}
