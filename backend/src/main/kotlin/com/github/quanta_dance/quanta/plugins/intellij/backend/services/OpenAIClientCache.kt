// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

/** Owns a settings-keyed client, rebuilding and closing it when synchronized settings change. */
internal class OpenAIClientCache<K, C : Any>(
    initialKey: K,
    private val createClient: () -> C,
    private val closeClient: (C) -> Unit,
    private val onRefresh: (K) -> Unit = {},
) {
    private var key: K = initialKey
    private var client: C? = null

    @Synchronized
    fun getOrRefresh(
        latestKey: K,
        settingsSynchronized: Boolean,
    ): C? {
        if (!settingsSynchronized) return null

        if (client == null || key != latestKey) {
            onRefresh(latestKey)
            client?.let(closeClient)
            client = createClient()
            key = latestKey
        }
        return client
    }

    @Synchronized
    fun dispose() {
        val current = client ?: return
        client = null
        closeClient(current)
    }
}
