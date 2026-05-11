// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.shared.contracts

import java.time.Instant

enum class ToolProgressKind {
    START,
    UPDATE,
    SUCCESS,
    ERROR,
    DEBUG,
}

data class ToolProgressEvent(
    val toolName: String,
    val kind: ToolProgressKind,
    val message: String,
    val detail: String? = null,
    val timestamp: Instant = Instant.now(),
)

interface ToolProgressService {
    fun publish(event: ToolProgressEvent)
}
