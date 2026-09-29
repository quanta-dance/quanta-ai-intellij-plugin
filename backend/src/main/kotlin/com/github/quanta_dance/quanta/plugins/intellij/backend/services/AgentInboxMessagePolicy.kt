// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

/** Keeps non-actionable agent inbox notifications from consuming an agent turn. */
internal object AgentInboxMessagePolicy {
    private const val ROSTER_UPDATE = "roster_update"

    fun shouldDeliver(kind: String?): Boolean = !kind.equals(ROSTER_UPDATE, ignoreCase = true)
}
