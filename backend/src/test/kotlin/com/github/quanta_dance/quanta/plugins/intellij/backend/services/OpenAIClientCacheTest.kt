// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

class OpenAIClientCacheTest {
    private data class FakeClient(
        val id: Int,
    )

    @Test
    fun `client is created after settings sync and replaced when credentials change`() {
        var nextId = 0
        val closedClients = mutableListOf<Int>()
        val refreshedKeys = mutableListOf<Pair<String, String>>()
        val cache =
            OpenAIClientCache(
                initialKey = "https://initial.test" to "initial-token",
                createClient = { FakeClient(++nextId) },
                closeClient = { closedClients += it.id },
                onRefresh = { refreshedKeys += it },
            )
        val initialKey = "https://initial.test" to "initial-token"

        assertNull(cache.getOrRefresh(initialKey, settingsSynchronized = false))
        assertEquals(0, nextId)

        val firstClient = assertNotNull(cache.getOrRefresh(initialKey, settingsSynchronized = true))
        assertSame(firstClient, cache.getOrRefresh(initialKey, settingsSynchronized = true))

        val updatedKey = "https://updated.test" to "updated-token"
        val updatedClient = assertNotNull(cache.getOrRefresh(updatedKey, settingsSynchronized = true))

        assertNotSame(firstClient, updatedClient)
        assertEquals(listOf(firstClient.id), closedClients)
        assertEquals(listOf(initialKey, updatedKey), refreshedKeys)

        cache.dispose()
        assertEquals(listOf(firstClient.id, updatedClient.id), closedClients)
    }
}
