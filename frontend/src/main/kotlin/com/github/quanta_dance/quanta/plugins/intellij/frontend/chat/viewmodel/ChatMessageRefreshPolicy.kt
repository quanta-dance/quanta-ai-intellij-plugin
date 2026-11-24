// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.frontend.chat.viewmodel

/**
 * Decides whether a periodic snapshot refresh must transfer full state after a cheap revision check.
 *
 * An unavailable revision deliberately refreshes the snapshot so split-mode RPC failures cannot leave the UI stale.
 */
internal object ChatMessageRefreshPolicy {
    fun shouldRefresh(
        cachedRevision: Long?,
        backendRevision: Long?,
    ): Boolean = cachedRevision == null || backendRevision == null || cachedRevision != backendRevision
}
