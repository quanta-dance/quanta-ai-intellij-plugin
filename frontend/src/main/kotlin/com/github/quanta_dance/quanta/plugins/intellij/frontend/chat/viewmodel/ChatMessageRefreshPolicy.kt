// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.frontend.chat.viewmodel

/** Keeps full message snapshots off the periodic RPC path until the backend reports a change. */
internal object ChatMessageRefreshPolicy {
    fun shouldRefresh(
        cachedRevision: Long?,
        backendRevision: Long?,
    ): Boolean = cachedRevision == null || backendRevision == null || cachedRevision != backendRevision
}
