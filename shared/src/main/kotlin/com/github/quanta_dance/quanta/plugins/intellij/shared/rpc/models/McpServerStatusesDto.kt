// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models

import kotlinx.serialization.Serializable

/** Snapshot of MCP server state, including whether the backend is still applying synced configuration. */
@Serializable
data class McpServerStatusesDto(
    val servers: List<McpServerStatusDto> = emptyList(),
    val configurationLoading: Boolean = false,
    val configurationError: String? = null,
)
