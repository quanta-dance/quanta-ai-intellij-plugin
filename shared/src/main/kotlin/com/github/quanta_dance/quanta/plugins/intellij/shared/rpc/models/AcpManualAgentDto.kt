// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models

import kotlinx.serialization.Serializable

/** A user-configured ACP endpoint, connected through a local application or newline-delimited TCP. */
@Serializable
data class AcpManualAgentDto(
    val name: String,
    val executable: String? = null,
    val host: String? = null,
    val port: Int? = null,
)
