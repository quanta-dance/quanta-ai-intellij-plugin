// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models

import kotlinx.serialization.Serializable

@Serializable
data class AgentInfoDto(
    val id: String,
    val role: String,
    val model: String? = null,
    val instructions: String? = null,
)
