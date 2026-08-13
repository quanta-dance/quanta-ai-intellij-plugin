// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.shared.rpc

import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.QuantaSettingsDto
import com.intellij.platform.rpc.RemoteApiProviderService
import fleet.rpc.RemoteApi
import fleet.rpc.Rpc
import fleet.rpc.remoteApiDescriptor

/**
 * Shared RPC API for synchronizing Quanta settings between frontend UI and backend runtime state.
 *
 * In split-mode IDEs the frontend owns the user-facing configuration UI, while the backend owns the
 * effective runtime configuration used by OpenAI and other backend services.
 */
@Rpc
interface QuantaSettingsApi : RemoteApi<Unit> {
    companion object {
        suspend fun getInstance(): QuantaSettingsApi = RemoteApiProviderService.resolve(remoteApiDescriptor<QuantaSettingsApi>())
    }

    suspend fun getSettings(): QuantaSettingsDto

    suspend fun updateSettings(settings: QuantaSettingsDto)
}
