// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models

import kotlinx.serialization.Serializable

/** A short-lived localhost pairing invitation for another Quanta IDE session. */
@Serializable
data class QuantaAcpShareDto(
    val invite: String? = null,
    val port: Int? = null,
    val expiresAtMillis: Long? = null,
    val peerName: String? = null,
    val connected: Boolean = false,
    val error: String? = null,
)
