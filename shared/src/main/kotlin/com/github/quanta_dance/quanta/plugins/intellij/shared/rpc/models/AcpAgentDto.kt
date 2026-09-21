// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models

import kotlinx.serialization.Serializable

/** A locally installed agent that completed the ACP initialization handshake. */
@Serializable
data class AcpAgentDto(
    val id: String,
    val name: String,
    val command: List<String>,
    val executablePath: String,
    /** Environment overrides applied only when launching this local ACP application. */
    val environment: Map<String, String> = emptyMap(),
    val version: String? = null,
    val protocolVersion: Int,
)
