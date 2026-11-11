// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models

import kotlinx.serialization.Serializable

/** A discovered MCP tool that can be presented in the per-server catalog. */
@Serializable
data class McpToolDescriptorDto(
    val name: String,
    val description: String? = null,
)

/** Current connection state of an IDE-configured MCP server for chat session controls. */
@Serializable
data class McpServerStatusDto(
    val name: String,
    val enabledForCurrentChat: Boolean = true,
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val toolCount: Int = 0,
    val tools: List<McpToolDescriptorDto> = emptyList(),
    val requiresAuthorization: Boolean = false,
    val error: String? = null,
)
