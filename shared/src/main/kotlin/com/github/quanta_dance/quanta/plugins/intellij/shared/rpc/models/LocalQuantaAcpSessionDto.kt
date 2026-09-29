// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models

import kotlinx.serialization.Serializable

/** A currently shared Quanta ACP session discoverable on this user's local machine. */
@Serializable
data class LocalQuantaAcpSessionDto(
    val peerIdentity: String,
    val projectName: String,
    val invite: String,
    val expiresAtMillis: Long,
    /** Local project path used only to avoid listing this project after a backend restart. */
    val publisherProjectPath: String? = null,
    /** Process that published this ephemeral entry, retained for diagnostics only. */
    val publisherProcessId: Long? = null,
)
